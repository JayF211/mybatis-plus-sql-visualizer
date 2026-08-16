package cn.song.mybatisplus.visualizer.reverse

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.reverse.SqlTokenizer.Token
import cn.song.mybatisplus.visualizer.reverse.SqlTokenizer.TokenType

/**
 * SQL → MyBatis-Plus 代码 反向解析器（P0）。
 *
 * 把单表 SELECT SQL 文本解析为可复用的条件模型（[Condition] 家族——与正向
 * SqlGenerator 同一模型，round-trip 自洽），供 [JavaCodeGenerator] 生成 QueryWrapper 代码。
 *
 * P0 支持范围（超出即抛 [SqlReverseException]，错误文案走 bundle key）：
 *  - `SELECT 列 FROM 表 [WHERE 顶层 AND 链] [ORDER BY ...] [LIMIT ...]`；
 *  - 条件算子：`= != <> > >= < <=`、`IS [NOT] NULL`、`[NOT] IN (...)`、
 *    `[NOT] BETWEEN a AND b`、`[NOT] LIKE '模式'`（通配符位置 → LikePosition）；
 *  - 列名/表名支持反引号与点分前缀（`t.col` / `` `t`.`col` ``），前缀在生成时剥离（单表无别名）。
 *
 * 明确不支持（P1+ 范围，报错而非猜测）：OR/嵌套括号、函数表达式、子查询、
 * JOIN、DISTINCT、GROUP BY/HAVING、UNION、`?` 占位符、列对列比较、多条语句。
 */
object SqlReverseParser {

    /** 解析入口。 @throws SqlReverseException 不支持或语法错误 */
    fun parse(sqlText: String): SqlReverseResult {
        val tokens = SqlTokenizer().tokenize(sqlText)
        if (tokens.isEmpty()) {
            throw SqlReverseException(0, KEY_PARSE, "SQL 文本为空")
        }
        val cursor = Cursor(tokens, sqlText)

        // 语句头分发（T-M36.7：增删改查全支持）
        return when {
            cursor.isKeyword(cursor.current, "SELECT") -> {
                cursor.advance()
                parseSelectBody(cursor)
            }
            cursor.isKeyword(cursor.current, "UPDATE") -> {
                cursor.advance()
                parseUpdateBody(cursor)
            }
            cursor.isKeyword(cursor.current, "DELETE") -> {
                cursor.advance()
                parseDeleteBody(cursor)
            }
            cursor.isKeyword(cursor.current, "INSERT") -> {
                cursor.advance()
                parseInsertBody(cursor)
            }
            else -> {
                val first = cursor.current
                throw SqlReverseException(
                    first?.offset ?: 0,
                    KEY_NOT_SELECT,
                    first?.takeIf { it.type == TokenType.WORD }?.text ?: "?",
                )
            }
        }
    }

    /** SELECT 主体：列清单 + FROM 表 + 子句序列（T-M37：含 JOIN 解析）。 */
    private fun parseSelectBody(cursor: Cursor): SqlReverseResult {
        val selectColumns = parseSelectColumns(cursor)
        if (!cursor.isKeyword(cursor.current, "FROM")) {
            throw SqlReverseException(cursor.current?.offset ?: 0, KEY_PARSE, "缺少 FROM 子句")
        }
        cursor.advance()
        val tableName = parseTableName(cursor)
        consumeOptionalAlias(cursor)
        val (conditions, orderBy, limit, warnings, joins) = parseTailClauses(cursor, allowJoin = true)
        return SqlReverseResult(
            tableName = tableName,
            selectColumns = selectColumns,
            conditions = conditions,
            orderBy = orderBy,
            limit = limit,
            warnings = warnings,
            joins = joins,
        )
    }

    /** UPDATE 主体：表 + SET 赋值 + 子句序列（T-M36.7）。 */
    private fun parseUpdateBody(cursor: Cursor): SqlReverseResult {
        val tableName = parseTableName(cursor)
        consumeOptionalAlias(cursor)
        if (!cursor.isKeyword(cursor.current, "SET")) {
            throw SqlReverseException(cursor.current?.offset ?: 0, KEY_PARSE, "UPDATE 缺少 SET 子句")
        }
        cursor.advance()
        val sets = parseSets(cursor)
        val (conditions, orderBy, limit, warnings) = parseTailClauses(cursor)
        return SqlReverseResult(
            tableName = tableName,
            conditions = conditions,
            orderBy = orderBy,
            limit = limit,
            warnings = warnings,
            kind = ReverseStatementKind.UPDATE,
            sets = sets,
        )
    }

    /** DELETE 主体：FROM 表 + 子句序列（T-M36.7）。 */
    private fun parseDeleteBody(cursor: Cursor): SqlReverseResult {
        if (!cursor.isKeyword(cursor.current, "FROM")) {
            throw SqlReverseException(cursor.current?.offset ?: 0, KEY_PARSE, "DELETE 缺少 FROM 子句")
        }
        cursor.advance()
        val tableName = parseTableName(cursor)
        consumeOptionalAlias(cursor)
        val (conditions, orderBy, limit, warnings) = parseTailClauses(cursor)
        return SqlReverseResult(
            tableName = tableName,
            conditions = conditions,
            orderBy = orderBy,
            limit = limit,
            warnings = warnings,
            kind = ReverseStatementKind.DELETE,
        )
    }

    /** INSERT 主体：INTO 表 (列清单) VALUES (值清单)（T-M36.7；多 VALUES 行 P1）。 */
    private fun parseInsertBody(cursor: Cursor): SqlReverseResult {
        if (!cursor.isKeyword(cursor.current, "INTO")) {
            throw SqlReverseException(cursor.current?.offset ?: 0, KEY_PARSE, "INSERT 缺少 INTO 子句")
        }
        cursor.advance()
        val tableName = parseTableName(cursor)

        expectSymbol(cursor, "(")
        val columns = mutableListOf<String>()
        while (true) {
            val t = cursor.current
                ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "INSERT 列清单不完整")
            if (t.type != TokenType.WORD) {
                throw SqlReverseException(t.offset, KEY_PARSE, "INSERT 列名格式不正确")
            }
            columns.add(stripQualified(consumeQualified(cursor)))
            if (cursor.isSymbol(cursor.current, ",")) {
                cursor.advance()
                continue
            }
            break
        }
        expectSymbol(cursor, ")")
        if (!cursor.isKeyword(cursor.current, "VALUES")) {
            throw SqlReverseException(cursor.current?.offset ?: 0, KEY_PARSE, "INSERT 缺少 VALUES")
        }
        cursor.advance()
        expectSymbol(cursor, "(")
        val values = mutableListOf<SqlValue>()
        while (true) {
            val value = parseValue(cursor)
                ?: throw SqlReverseException(cursor.previousOffset(), KEY_PARSE, "INSERT 值不支持 NULL")
            values.add(value)
            if (cursor.isSymbol(cursor.current, ",")) {
                cursor.advance()
                continue
            }
            break
        }
        expectSymbol(cursor, ")")
        if (columns.size != values.size) {
            throw SqlReverseException(cursor.previousOffset(), KEY_PARSE, "INSERT 列数与值数不一致")
        }
        if (cursor.isSymbol(cursor.current, ",")) {
            unsupported(cursor, "多行 VALUES（一次转换一行）")
        }
        return SqlReverseResult(
            tableName = tableName,
            warnings = emptyList(),
            kind = ReverseStatementKind.INSERT,
            insertColumns = columns,
            insertValues = values,
        )
    }

    /** SET 赋值清单（T-M36.7）：`列 = 值` 逗号分隔；函数/表达式值降级 rawSql 透传。 */
    private fun parseSets(cursor: Cursor): List<ReverseSet> {
        val sets = mutableListOf<ReverseSet>()
        while (true) {
            val column = parseColumnRef(cursor)
            expectSymbol(cursor, "=")
            val value = try {
                parseValue(cursor)
            } catch (e: SqlReverseException) {
                if (e.key == KEY_UNSUPPORTED) {
                    // 函数/表达式值（如 update_time = NOW()）→ 收集到逗号边界的原文 rawSql 透传
                    val startIdx = cursor.tokens.indexOfFirst { it.offset == e.offset }.coerceAtLeast(0)
                    val endIdx = cursor.tokens.indexOfFirst {
                        it.offset > e.offset && it.type == TokenType.SYMBOL && it.text == ","
                    }.let { if (it < 0) cursor.tokens.size else it }
                    val endOffset = if (endIdx > 0) {
                        val last = cursor.tokens[endIdx - 1]
                        last.offset + last.text.length
                    } else {
                        e.offset
                    }
                    cursor.pos = endIdx
                    sets.add(ReverseSet(column = column.columnName, value = null, rawSql = "${column.columnName} = ${cursor.source.substring(e.offset, endOffset).trim()}"))
                    if (cursor.isSymbol(cursor.current, ",")) {
                        cursor.advance()
                        continue
                    }
                    break
                }
                throw e
            }
            sets.add(ReverseSet(column = column.columnName, value = value))
            if (cursor.isSymbol(cursor.current, ",")) {
                cursor.advance()
                continue
            }
            break
        }
        return sets
    }

    /**
     * 尾子句序列（SELECT/UPDATE/DELETE 共用）：WHERE / ORDER BY / LIMIT 及不支持子句报错。
     * T-M37：allowJoin=true（SELECT）时解析 JOIN 链。
     */
    private fun parseTailClauses(cursor: Cursor, allowJoin: Boolean = false): TailClauses {
        val warnings = mutableListOf<String>()
        var conditions = emptyList<Condition>()
        var orderBy = emptyList<OrderByItem>()
        var limit: LimitClause? = null
        val joins = mutableListOf<ReverseJoin>()
        while (true) {
            val t = cursor.current ?: break
            when {
                cursor.isKeyword(t, "WHERE") -> {
                    cursor.advance()
                    conditions = parseConditionList(cursor, warnings)
                }
                cursor.isKeyword(t, "ORDER") -> {
                    cursor.advance()
                    expectKeyword(cursor, "BY")
                    orderBy = parseOrderBy(cursor)
                }
                cursor.isKeyword(t, "LIMIT") -> {
                    cursor.advance()
                    limit = parseLimit(cursor)
                }
                allowJoin && isJoinStart(cursor) -> joins.add(parseJoin(cursor, warnings))
                cursor.isKeyword(t, "GROUP") -> unsupported(cursor, "GROUP BY")
                cursor.isKeyword(t, "HAVING") -> unsupported(cursor, "HAVING")
                cursor.isKeyword(t, "UNION") -> unsupported(cursor, "UNION")
                cursor.isKeyword(t, "FOR") -> unsupported(cursor, "FOR UPDATE")
                cursor.isKeyword(t, "JOIN") || cursor.isKeyword(t, "LEFT") || cursor.isKeyword(t, "RIGHT") ||
                    cursor.isKeyword(t, "INNER") || cursor.isKeyword(t, "FULL") || cursor.isKeyword(t, "CROSS") ->
                    unsupported(cursor, "JOIN")
                t.type == TokenType.SYMBOL && t.text == ";" -> {
                    cursor.advance()
                    if (cursor.current != null) unsupported(cursor, "多条语句（一次转换一条）")
                    break
                }
                else -> throw SqlReverseException(
                    t.offset,
                    KEY_PARSE,
                    "无法识别的子句：${t.text}",
                )
            }
        }
        return TailClauses(conditions, orderBy, limit, warnings, joins)
    }

    /** 尾子句解析产物。 */
    private data class TailClauses(
        val conditions: List<Condition>,
        val orderBy: List<OrderByItem>,
        val limit: LimitClause?,
        val warnings: List<String>,
        val joins: List<ReverseJoin> = emptyList(),
    )

    // ------------------------------------------------------------------
    // JOIN 解析（T-M37）
    // ------------------------------------------------------------------

    /** JOIN 起始判定（含修饰词 + [OUTER] JOIN）。 */
    private fun isJoinStart(cursor: Cursor): Boolean {
        val t = cursor.current ?: return false
        if (cursor.isKeyword(t, "JOIN")) return true
        if (!cursor.isKeyword(t, "LEFT") && !cursor.isKeyword(t, "RIGHT") && !cursor.isKeyword(t, "INNER") &&
            !cursor.isKeyword(t, "FULL") && !cursor.isKeyword(t, "CROSS")
        ) {
            return false
        }
        // 修饰词后须跟 [OUTER] JOIN 才算 JOIN 起始（LEFT JOIN / LEFT OUTER JOIN）
        val next = cursor.peek()
        if (next != null && cursor.isKeyword(next, "JOIN")) return true
        if (next != null && cursor.isKeyword(next, "OUTER")) {
            val after = cursor.tokens.getOrNull(cursor.pos + 2)
            return after != null && cursor.isKeyword(after, "JOIN")
        }
        return false
    }

    /** 解析一个 JOIN 子句：类型 + 副表[别名] + ON 条件（等值列对 → ColumnComparison）。 */
    private fun parseJoin(cursor: Cursor, warnings: MutableList<String>): ReverseJoin {
        val type = when {
            cursor.isKeyword(cursor.current, "JOIN") -> {
                cursor.advance()
                cn.song.mybatisplus.visualizer.model.JoinType.INNER
            }
            else -> {
                val modifier = cursor.current?.text?.uppercase()
                cursor.advance()
                if (cursor.isKeyword(cursor.current, "OUTER")) cursor.advance()
                expectKeyword(cursor, "JOIN")
                when (modifier) {
                    "LEFT" -> cn.song.mybatisplus.visualizer.model.JoinType.LEFT
                    "RIGHT" -> cn.song.mybatisplus.visualizer.model.JoinType.RIGHT
                    "FULL" -> cn.song.mybatisplus.visualizer.model.JoinType.FULL
                    else -> cn.song.mybatisplus.visualizer.model.JoinType.INNER
                }
            }
        }
        val tableName = parseTableName(cursor)
        val alias = consumeJoinAlias(cursor)
        if (!cursor.isKeyword(cursor.current, "ON")) {
            throw SqlReverseException(cursor.current?.offset ?: 0, KEY_PARSE, "JOIN 缺少 ON 子句")
        }
        cursor.advance()
        // 收集 ON 原文（raw join 字符串透传用；从 ON 后首个 token 到条件解析结束）
        val onStart = cursor.current?.offset
        val onConditions = parseOnConditions(cursor, warnings)
        val rawOn = if (onStart != null && cursor.tokens.isNotEmpty()) {
            val last = cursor.tokens.getOrNull(cursor.pos - 1)
            if (last != null && last.offset >= onStart) {
                cursor.source.substring(onStart, last.offset + last.text.length).trim()
            } else {
                null
            }
        } else {
            null
        }
        return ReverseJoin(type, tableName, alias, onConditions, rawOn)
    }

    /** JOIN 副表别名（ON 为边界；无别名返回 null）。 */
    private fun consumeJoinAlias(cursor: Cursor): String? {
        val t = cursor.current ?: return null
        if (t.type == TokenType.WORD && !cursor.isKeyword(t, "ON") && !cursor.isKeyword(t, "WHERE") &&
            !cursor.isKeyword(t, "GROUP") && !cursor.isKeyword(t, "ORDER") && !cursor.isKeyword(t, "LIMIT") &&
            !cursor.isKeyword(t, "LEFT") && !cursor.isKeyword(t, "RIGHT") && !cursor.isKeyword(t, "INNER") &&
            !cursor.isKeyword(t, "FULL") && !cursor.isKeyword(t, "CROSS") && !cursor.isKeyword(t, "JOIN")
        ) {
            val alias = t.text.trim('`')
            cursor.advance()
            return alias
        }
        return null
    }

    /** ON 条件列表（AND 连接；等值列对自动识别为 ColumnComparison；遇下一个 JOIN/子句结束）。 */
    private fun parseOnConditions(cursor: Cursor, warnings: MutableList<String>): List<Condition> {
        val conditions = mutableListOf<Condition>()
        while (true) {
            val t = cursor.current ?: break
            if (isJoinStart(cursor)) break
            if (t.type == TokenType.WORD && (isClauseStart(cursor) || cursor.isKeyword(t, "WHERE"))) break
            if (t.type == TokenType.SYMBOL && t.text == ";") break
            if (cursor.isKeyword(t, "AND")) {
                cursor.advance()
                continue
            }
            if (cursor.isKeyword(t, "OR")) unsupported(cursor, "ON 内 OR 嵌套条件")
            conditions.add(parseCondition(cursor, warnings))
        }
        return conditions
    }

    // ------------------------------------------------------------------
    // SELECT 列清单
    // ------------------------------------------------------------------

    /**
     * 解析 SELECT 与 FROM 之间的列清单。
     *
     * 仅收集「简单列」（标识符 / 点分 / 反引号，可带 AS 别名）；出现函数调用
     * （`COUNT(...)` 等）或 `*` 时整体放弃（返回 null——生成器不生成 select() 调用）。
     * 复杂表达式整体跳过（平衡括号消费至逗号/FROM）。
     * T-M37：保留原文（含表别名前缀与反引号）——多表列归属由生成器拆分（renderSelectColumn）。
     */
    private fun parseSelectColumns(cursor: Cursor): List<String>? {
        val columns = mutableListOf<String>()
        var anyComplex = false
        while (true) {
            val t = cursor.current ?: break
            when {
                cursor.isKeyword(t, "FROM") -> break
                t.type == TokenType.SYMBOL && t.text == "," -> {
                    cursor.advance()
                    continue
                }
                t.type == TokenType.SYMBOL && t.text == "*" -> {
                    anyComplex = true
                    cursor.advance()
                    continue
                }
                t.type == TokenType.WORD -> {
                    // DISTINCT：不收集为列，标记复杂（select 生成放弃）
                    if (cursor.isKeyword(t, "DISTINCT")) {
                        anyComplex = true
                        cursor.advance()
                        continue
                    }
                    if (isFunctionCall(cursor)) {
                        anyComplex = true
                        skipExpression(cursor)
                        continue
                    }
                    columns.add(consumeQualified(cursor))
                    consumeSelectAlias(cursor)
                }
                t.type == TokenType.SYMBOL && t.text == "(" -> {
                    // 裸括号表达式（少见）→ 放弃 select 生成，整体跳过
                    anyComplex = true
                    skipBalanced(cursor)
                }
                else -> break
            }
        }
        return if (anyComplex) null else columns
    }

    /** 当前 WORD 后紧跟 `(` → 函数调用。 */
    private fun isFunctionCall(cursor: Cursor): Boolean {
        val next = cursor.peek()
        return next != null && next.type == TokenType.SYMBOL && next.text == "("
    }

    /** 消费 `AS 别名` 或直接别名（`a AS x` / `a x`；别名是普通 WORD）。 */
    private fun consumeSelectAlias(cursor: Cursor) {
        val t = cursor.current
        if (t == null || t.type != TokenType.WORD) return
        if (cursor.isKeyword(t, "AS")) {
            cursor.advance()
            cursor.advance() // 别名
        } else if (!cursor.isKeyword(t, "FROM")) {
            // `a x` 形态：x 是别名（无关键字风险——FROM 已在上层判定）
            cursor.advance()
        }
    }

    /**
     * 跳过整个表达式（平衡括号 + 词）直到顶层逗号或 FROM。
     * FROM 不消费（交回上层循环判定）；逗号消费（它是表达式的终止符）。
     */
    private fun skipExpression(cursor: Cursor) {
        var depth = 0
        while (true) {
            val t = cursor.current ?: break
            if (depth == 0 && cursor.isKeyword(t, "FROM")) break
            cursor.advance()
            if (t.type == TokenType.SYMBOL && t.text == "(") {
                depth++
                continue
            }
            if (t.type == TokenType.SYMBOL && t.text == ")") {
                depth--
                if (depth < 0) break
                continue
            }
            if (depth == 0 && t.type == TokenType.SYMBOL && t.text == ",") break
        }
    }

    /** 跳过一对平衡括号（当前 token 为 `(`）。 */
    private fun skipBalanced(cursor: Cursor) {
        var depth = 0
        while (true) {
            val t = cursor.advance() ?: break
            if (t.type == TokenType.SYMBOL && t.text == "(") depth++
            if (t.type == TokenType.SYMBOL && t.text == ")") {
                depth--
                if (depth == 0) break
            }
        }
    }

    // ------------------------------------------------------------------
    // 表名
    // ------------------------------------------------------------------

    /** 表名：`表` / `库.表`（点分/反引号，库前缀剥掉取末段）。 */
    private fun parseTableName(cursor: Cursor): String {
        val t = cursor.current
            ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "缺少表名")
        if (t.type != TokenType.WORD) {
            throw SqlReverseException(t.offset, KEY_PARSE, "表名格式不正确")
        }
        return stripQualified(consumeQualified(cursor))
    }

    /** 可选表别名：`FROM user u`——u 非子句关键字时视为别名消费（T-M36.7：SET/INTO 亦为边界）。 */
    private fun consumeOptionalAlias(cursor: Cursor) {
        val t = cursor.current ?: return
        if (t.type == TokenType.WORD && !cursor.isKeyword(t, "WHERE") && !cursor.isKeyword(t, "GROUP") &&
            !cursor.isKeyword(t, "ORDER") && !cursor.isKeyword(t, "LIMIT") && !cursor.isKeyword(t, "HAVING") &&
            !cursor.isKeyword(t, "UNION") && !cursor.isKeyword(t, "JOIN") && !cursor.isKeyword(t, "FOR") &&
            !cursor.isKeyword(t, "SET") && !cursor.isKeyword(t, "INTO") &&
            !cursor.isKeyword(t, "LEFT") && !cursor.isKeyword(t, "RIGHT") && !cursor.isKeyword(t, "INNER") &&
            !cursor.isKeyword(t, "FULL") && !cursor.isKeyword(t, "CROSS")
        ) {
            cursor.advance()
        }
    }

    // ------------------------------------------------------------------
    // WHERE 条件序列
    // ------------------------------------------------------------------

    /**
     * 条件序列（T-M36.1 升级）：顶层按 OR 分段，段内 AND 连接；出现 OR 时整体建模为
     * `Group(OR, 段们)`（段内多条件包 `Group(AND)`）——与正向链路 `or()` 分隔符语义一致。
     *
     * 形态：`a = 1 AND b = 2` → `[a, b]`（无 OR 保持扁平，P0 行为不变）；
     * `a = 1 OR b = 2` → `[Group(OR, [a, b])]`；
     * `a = 1 AND b = 2 OR c = 3` → `[Group(OR, [Group(AND, [a, b]), c])]`（AND 优先级高于 OR）。
     */
    private fun parseConditionList(cursor: Cursor, warnings: MutableList<String>): List<Condition> {
        val orSegments = mutableListOf<List<Condition>>()
        orSegments.add(parseAndSegment(cursor, warnings))
        while (cursor.isKeyword(cursor.current, "OR")) {
            cursor.advance()
            orSegments.add(parseAndSegment(cursor, warnings))
        }
        if (orSegments.size == 1) return orSegments[0]
        return listOf(
            Condition.Group(
                connector = Connector.OR,
                children = orSegments.map { segment ->
                    if (segment.size == 1) segment[0] else Condition.Group(Connector.AND, segment)
                },
            ),
        )
    }

    /** AND 连接的条件段（遇 OR / `)` / 子句起点 / 分号终止，均不消费）。 */
    private fun parseAndSegment(cursor: Cursor, warnings: MutableList<String>): List<Condition> {
        val conditions = mutableListOf<Condition>()
        while (true) {
            val t = cursor.current ?: break
            if (t.type == TokenType.WORD && isClauseStart(cursor)) break
            if (t.type == TokenType.SYMBOL && (t.text == ";" || t.text == ")")) break
            if (cursor.isKeyword(t, "AND")) {
                cursor.advance()
                continue
            }
            if (cursor.isKeyword(t, "OR")) break
            conditions.add(parseCondition(cursor, warnings))
        }
        return conditions
    }

    /**
     * 条件序列中的子句起点判定：`ORDER BY` / `GROUP BY`（须成对出现——`WHERE order = 1`
     * 的列名 order 不误判）与 HAVING / LIMIT / UNION / FOR。
     */
    private fun isClauseStart(cursor: Cursor): Boolean {
        val t = cursor.current ?: return false
        val by = cursor.isKeyword(cursor.peek(), "BY")
        return (cursor.isKeyword(t, "ORDER") && by) || (cursor.isKeyword(t, "GROUP") && by) ||
            cursor.isKeyword(t, "HAVING") || cursor.isKeyword(t, "LIMIT") ||
            cursor.isKeyword(t, "UNION") || cursor.isKeyword(t, "FOR")
    }

    /**
     * 单个条件（T-M36.1 升级）：括号组 / NOT 组 / 普通条件。
     * 解析失败且为"表达式形态不支持"（[KEY_UNSUPPORTED]：函数、子查询、列对列、`?` 占位符等）
     * → 降级为 [Condition.Raw] 整条件 apply() 透传（记 warning）；语法错误（[KEY_PARSE]）保持报错。
     */
    private fun parseCondition(cursor: Cursor, warnings: MutableList<String>): Condition {
        val startOffset = cursor.current?.offset
            ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "条件不完整")
        return try {
            parseConditionInternal(cursor, warnings)
        } catch (e: SqlReverseException) {
            if (e.key == KEY_UNSUPPORTED) {
                degradeToRaw(cursor, startOffset, warnings)
            } else {
                throw e
            }
        }
    }

    private fun parseConditionInternal(cursor: Cursor, warnings: MutableList<String>): Condition {
        val head = cursor.current ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "条件不完整")
        // 括号组 `( ... )` / `NOT ( ... )`（T-M36.1）
        if (head.type == TokenType.SYMBOL && head.text == "(") {
            cursor.advance()
            return parseGroup(cursor, warnings, negated = false)
        }
        if (cursor.isKeyword(head, "NOT") && cursor.isSymbol(cursor.peek(), "(")) {
            cursor.advance()
            cursor.advance()
            return parseGroup(cursor, warnings, negated = true)
        }
        val column = parseColumnRef(cursor)
        // 操作符判定基于列名之后的当前 token（P0 行为：t 为列名解析后的 current）
        val t = cursor.current ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "条件不完整")
        return when {
            // = != <> < <= > >= 二元比较（操作符 token 已判定，先消费再取右侧值）
            t.type == TokenType.SYMBOL && t.text == "=" -> {
                cursor.advance()
                parseBinary(cursor, column, "=")
            }
            t.type == TokenType.SYMBOL && t.text == "!" -> {
                cursor.advance()
                expectSymbol(cursor, "=")
                parseBinary(cursor, column, "!=")
            }
            t.type == TokenType.SYMBOL && t.text == "<" -> {
                cursor.advance()
                when (cursor.current?.text) {
                    "=" -> {
                        cursor.advance()
                        parseBinary(cursor, column, "<=")
                    }
                    ">" -> {
                        cursor.advance()
                        parseBinary(cursor, column, "<>")
                    }
                    else -> parseBinary(cursor, column, "<")
                }
            }
            t.type == TokenType.SYMBOL && t.text == ">" -> {
                cursor.advance()
                when (cursor.current?.text) {
                    "=" -> {
                        cursor.advance()
                        parseBinary(cursor, column, ">=")
                    }
                    else -> parseBinary(cursor, column, ">")
                }
            }
            // IS [NOT] NULL
            cursor.isKeyword(t, "IS") -> {
                cursor.advance()
                val negated = if (cursor.isKeyword(cursor.current, "NOT")) {
                    cursor.advance()
                    true
                } else {
                    false
                }
                if (!cursor.isKeyword(cursor.current, "NULL")) {
                    throw SqlReverseException(cursor.current?.offset ?: t.offset, KEY_PARSE, "IS 后应为 NULL")
                }
                cursor.advance()
                Condition.Unary(column, if (negated) "IS NOT NULL" else "IS NULL")
            }
            // NOT IN / NOT BETWEEN / NOT LIKE（NOT ( 组已在入口分支处理）
            cursor.isKeyword(t, "NOT") -> {
                cursor.advance()
                parseNegated(cursor, column, t.offset, warnings)
            }
            // IN / BETWEEN / LIKE
            cursor.isKeyword(t, "IN") -> {
                cursor.advance()
                parseIn(cursor, column, negated = false, t.offset)
            }
            cursor.isKeyword(t, "BETWEEN") -> {
                cursor.advance()
                parseBetween(cursor, column, negated = false, t.offset)
            }
            cursor.isKeyword(t, "LIKE") -> {
                cursor.advance()
                parseLike(cursor, column, negated = false, t.offset, warnings)
            }
            // 列名后紧跟 `(` → 函数表达式（DATE_FORMAT(...) 等，降级 apply 透传）
            t.type == TokenType.SYMBOL && t.text == "(" ->
                unsupported(cursor, "函数表达式 ${column.columnName}(...)")
            else -> throw SqlReverseException(
                t.offset,
                KEY_PARSE,
                "无法识别的条件操作符：${t.text}",
            )
        }
    }

    /**
     * 括号组解析（T-M36.1）：当前位于 `(` 之后；组内递归走 [parseConditionList]（OR 分段语义一致），
     * 直到 `)`。组内为单个 Group（如 `(a = 1 OR b = 2)`）时直接复用该组（避免双重括号），
     * 否则包 `Group(AND, ...)`（保留括号，round-trip 等价）。空组报语法错误。
     */
    private fun parseGroup(cursor: Cursor, warnings: MutableList<String>, negated: Boolean): Condition {
        val inner = parseConditionList(cursor, warnings)
        expectSymbol(cursor, ")")
        if (inner.isEmpty()) {
            throw SqlReverseException(cursor.previousOffset(), KEY_PARSE, "空括号组")
        }
        val single = inner.singleOrNull()
        if (single is Condition.Group) {
            return if (negated) single.copy(negated = true) else single
        }
        return Condition.Group(connector = Connector.AND, children = inner, negated = negated)
    }

    /**
     * 表达式形态降级（T-M36.1）：从条件起始偏移扫描到顶层 AND/OR/`)`/子句边界，
     * 取原文整段为 [Condition.Raw]（生成 apply() 透传），游标推进到条件末尾。
     */
    private fun degradeToRaw(cursor: Cursor, startOffset: Int, warnings: MutableList<String>): Condition.Raw {
        val startIdx = cursor.tokens.indexOfFirst { it.offset == startOffset }.coerceAtLeast(0)
        var depth = 0
        var idx = startIdx
        while (idx < cursor.tokens.size) {
            val tk = cursor.tokens[idx]
            if (depth == 0 && (
                    (tk.type == TokenType.WORD && (isKeyword(tk, "AND") || isKeyword(tk, "OR") || isClauseToken(tk))) ||
                        (tk.type == TokenType.SYMBOL && (tk.text == ";" || tk.text == ")"))
                    )
            ) {
                break
            }
            if (tk.type == TokenType.SYMBOL && tk.text == "(") depth++
            if (tk.type == TokenType.SYMBOL && tk.text == ")") depth--
            idx++
        }
        val endOffset = if (idx > startIdx) {
            val last = cursor.tokens[idx - 1]
            last.offset + last.text.length
        } else {
            startOffset
        }
        cursor.pos = idx
        val rawText = cursor.source.substring(startOffset, endOffset).trim()
        warnings.add("条件「$rawText」无法静态转换为 Wrapper 方法，已用 apply() 透传")
        return Condition.Raw(rawText)
    }

    private fun isKeyword(t: Token, kw: String): Boolean =
        t.type == TokenType.WORD && t.text.equals(kw, ignoreCase = true)

    private fun isClauseToken(t: Token): Boolean =
        isKeyword(t, "ORDER") || isKeyword(t, "GROUP") || isKeyword(t, "HAVING") ||
            isKeyword(t, "LIMIT") || isKeyword(t, "UNION") || isKeyword(t, "FOR")

    /**
     * 二元比较（T-M37：`=` 右侧为裸标识符 → 列对列 [Condition.ColumnComparison]——
     * WHERE/ON 均适用；NULL 字面量 → IS NULL/IS NOT NULL；其余值走 [parseValue]）。
     */
    private fun parseBinary(cursor: Cursor, column: ColumnRef, sqlOp: String): Condition {
        if (sqlOp == "=") {
            val next = cursor.current
            if (next != null && next.type == TokenType.WORD && !cursor.isKeyword(next, "TRUE") &&
                !cursor.isKeyword(next, "FALSE") && !cursor.isKeyword(next, "NULL")
            ) {
                // 右值为函数调用（后跟 `(`）→ 非列对列，走值解析（降级 Raw 透传）
                val after = cursor.peek()
                val isFunction = after != null && after.type == TokenType.SYMBOL && after.text == "("
                if (!isFunction) {
                    val right = parseColumnRef(cursor)
                    return Condition.ColumnComparison(column, right)
                }
            }
        }
        val value = parseValue(cursor) ?: return when (sqlOp) {
            "=" -> Condition.Unary(column, "IS NULL")
            "!=", "<>" -> Condition.Unary(column, "IS NOT NULL")
            else -> throw SqlReverseException(cursor.previousOffset(), KEY_PARSE, "NULL 只能与 = / != 比较")
        }
        return Condition.Simple(column, sqlOp, value)
    }

    /** NOT 变体分发：NOT IN / NOT BETWEEN / NOT LIKE；其余 → 报错。 */
    private fun parseNegated(cursor: Cursor, column: ColumnRef, notOffset: Int, warnings: MutableList<String>): Condition {
        val t = cursor.current ?: throw SqlReverseException(notOffset, KEY_PARSE, "NOT 后缺少条件")
        return when {
            cursor.isKeyword(t, "IN") -> {
                cursor.advance()
                parseIn(cursor, column, negated = true, t.offset)
            }
            cursor.isKeyword(t, "BETWEEN") -> {
                cursor.advance()
                parseBetween(cursor, column, negated = true, t.offset)
            }
            cursor.isKeyword(t, "LIKE") -> {
                cursor.advance()
                parseLike(cursor, column, negated = true, t.offset, warnings)
            }
            else -> unsupported(cursor, "NOT 表达式（仅支持 NOT IN / NOT BETWEEN / NOT LIKE）")
        }
    }

    /** IN (v1, v2, ...)：空列表或含 NULL → 报错。 */
    private fun parseIn(cursor: Cursor, column: ColumnRef, negated: Boolean, opOffset: Int): Condition {
        expectSymbol(cursor, "(")
        val values = mutableListOf<SqlValue>()
        while (true) {
            val t = cursor.current ?: break
            if (t.type == TokenType.SYMBOL && t.text == ")") {
                cursor.advance()
                break
            }
            val value = parseValue(cursor)
                ?: throw SqlReverseException(t.offset, KEY_PARSE, "IN 列表不支持 NULL")
            values.add(value)
            if (cursor.isSymbol(cursor.current, ",")) {
                cursor.advance()
            } else if (!cursor.isSymbol(cursor.current, ")")) {
                throw SqlReverseException(cursor.current?.offset ?: opOffset, KEY_PARSE, "IN 列表格式不正确")
            }
        }
        if (values.isEmpty()) {
            throw SqlReverseException(opOffset, KEY_PARSE, "IN 列表为空")
        }
        return Condition.In(column, negated, values)
    }

    /** BETWEEN lo AND hi。 */
    private fun parseBetween(cursor: Cursor, column: ColumnRef, negated: Boolean, opOffset: Int): Condition {
        val lo = parseValue(cursor)
            ?: throw SqlReverseException(cursor.previousOffset(), KEY_PARSE, "BETWEEN 不支持 NULL 边界")
        if (!cursor.isKeyword(cursor.current, "AND")) {
            throw SqlReverseException(cursor.current?.offset ?: opOffset, KEY_PARSE, "BETWEEN 缺少 AND")
        }
        cursor.advance()
        val hi = parseValue(cursor)
            ?: throw SqlReverseException(cursor.previousOffset(), KEY_PARSE, "BETWEEN 不支持 NULL 边界")
        return Condition.Between(column, negated, lo, hi)
    }

    /**
     * LIKE '模式'：按通配符位置映射 [LikePosition]（ANYWHERE→like 家族）。
     * 模式含 `_`/转义/中间 `%`/无通配符 → 无法用 like 家族表达 → 降级 [Condition.Raw]
     * 透传 `列 LIKE 原文`（生成器渲染为 apply()），并记入 warnings（进生成代码注释）。
     */
    private fun parseLike(
        cursor: Cursor,
        column: ColumnRef,
        negated: Boolean,
        opOffset: Int,
        warnings: MutableList<String>,
    ): Condition {
        val t = cursor.current
            ?: throw SqlReverseException(opOffset, KEY_PARSE, "LIKE 后缺少模式")
        if (t.type != TokenType.STRING) {
            throw SqlReverseException(t.offset, KEY_PARSE, "LIKE 模式必须是字符串字面量")
        }
        cursor.advance()
        val pattern = decodeString(t.text)
        val position = likePositionOf(pattern)
        if (position == null) {
            warnings.add("LIKE 模式 $pattern 无法映射为 like 家族方法，已用 apply() 透传")
            return Condition.Raw(
                "${column.columnName} ${if (negated) "NOT LIKE" else "LIKE"} ${t.text}",
            )
        }
        return Condition.Like(
            column,
            negated,
            SqlValue.Literal(raw = t.text, unquoted = stripWildcards(pattern)),
            position,
        )
    }

    /** 模式 → LikePosition；无法映射（_ / \ / 中间 % / 无 %）返回 null（降级 apply）。 */
    private fun likePositionOf(pattern: String): LikePosition? {
        if ('_' in pattern || '\\' in pattern) return null
        if ('%' !in pattern) return null
        return when {
            pattern.startsWith("%") && pattern.endsWith("%") && pattern.length >= 2 -> LikePosition.ANYWHERE
            pattern.startsWith("%") -> LikePosition.LEFT
            pattern.endsWith("%") -> LikePosition.RIGHT
            else -> null // 中间 %（如 'a%b'）
        }
    }

    /** 去掉模式首尾通配符得核心值（like 家族参数：`%abc%` → `abc`）。 */
    private fun stripWildcards(pattern: String): String = pattern.trim('%')

    // ------------------------------------------------------------------
    // 值解析
    // ------------------------------------------------------------------

    /**
     * 解析条件值。返回 null 表示 NULL 字面量（由调用方决定语义）。
     *
     * 支持：字符串（保留引号原文 + unquoted 解转义）、数字、TRUE/FALSE。
     * 明确不支持：`?` 占位符（需变量名，无法静态生成）、函数/子查询/列引用（列对列）。
     */
    private fun parseValue(cursor: Cursor): SqlValue? {
        val t = cursor.current
            ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "条件缺少值")
        return when (t.type) {
            TokenType.STRING -> {
                cursor.advance()
                SqlValue.Literal(raw = t.text, unquoted = decodeString(t.text))
            }
            TokenType.NUMBER -> {
                cursor.advance()
                SqlValue.Literal(t.text)
            }
            TokenType.WORD -> when (t.text.uppercase()) {
                "TRUE", "FALSE" -> {
                    cursor.advance()
                    SqlValue.Literal(t.text.lowercase())
                }
                "NULL" -> {
                    cursor.advance()
                    null
                }
                else -> throw SqlReverseException(
                    t.offset,
                    KEY_UNSUPPORTED,
                    "标识符值 ${t.text}（列对列比较/函数表达式暂不支持）",
                )
            }
            TokenType.SYMBOL -> when (t.text) {
                "?" -> throw SqlReverseException(t.offset, KEY_UNSUPPORTED, "参数占位符 ?")
                "(" -> throw SqlReverseException(t.offset, KEY_UNSUPPORTED, "子查询/表达式")
                else -> throw SqlReverseException(t.offset, KEY_PARSE, "条件缺少值")
            }
        }
    }

    // ------------------------------------------------------------------
    // ORDER BY / LIMIT
    // ------------------------------------------------------------------

    /** ORDER BY 列 [ASC|DESC]，逗号分隔（默认 ASC）。 */
    private fun parseOrderBy(cursor: Cursor): List<OrderByItem> {
        val items = mutableListOf<OrderByItem>()
        while (true) {
            val column = parseColumnRef(cursor)
            var asc = true
            val t = cursor.current
            if (t != null && t.type == TokenType.WORD) {
                when (t.text.uppercase()) {
                    "ASC" -> cursor.advance()
                    "DESC" -> {
                        asc = false
                        cursor.advance()
                    }
                }
            }
            items.add(OrderByItem(column, asc))
            if (cursor.isSymbol(cursor.current, ",")) {
                cursor.advance()
                continue
            }
            break
        }
        return items
    }

    /** LIMIT n / LIMIT offset, count。 */
    private fun parseLimit(cursor: Cursor): LimitClause {
        val first = cursor.current
            ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "LIMIT 缺少行数")
        if (first.type != TokenType.NUMBER) {
            throw SqlReverseException(first.offset, KEY_PARSE, "LIMIT 必须是数字")
        }
        cursor.advance()
        if (cursor.isSymbol(cursor.current, ",")) {
            cursor.advance()
            val second = cursor.current
                ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "LIMIT 缺少行数")
            if (second.type != TokenType.NUMBER) {
                throw SqlReverseException(second.offset, KEY_PARSE, "LIMIT 必须是数字")
            }
            cursor.advance()
            return LimitClause(
                offset = SqlValue.Literal(first.text),
                count = SqlValue.Literal(second.text),
            )
        }
        return LimitClause(count = SqlValue.Literal(first.text))
    }

    // ------------------------------------------------------------------
    // 列引用 / 通用工具
    // ------------------------------------------------------------------

    /** 列引用（T-M37：保留表别名供多表归属）：`col` / `t.col` / `` `col` `` / `` `t`.`col` ``。 */
    private fun parseColumnRef(cursor: Cursor): ColumnRef {
        val text = consumeQualified(cursor)
        val parts = text.split('.')
        val alias = if (parts.size > 1) parts.dropLast(1).joinToString(".").trim('`').ifEmpty { null } else null
        return ColumnRef(tableAlias = alias, columnName = parts.last().trim('`'))
    }

    /** 消费 `词[.词]` 组合（点分反引号是 3 个 token）。 */
    private fun consumeQualified(cursor: Cursor): String {
        val t = cursor.current
            ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "缺少列名/表名")
        if (t.type != TokenType.WORD) {
            throw SqlReverseException(t.offset, KEY_PARSE, "缺少列名/表名")
        }
        val sb = StringBuilder(t.text)
        cursor.advance()
        if (cursor.isSymbol(cursor.current, ".")) {
            cursor.advance()
            val after = cursor.current
            if (after != null && after.type == TokenType.WORD) {
                sb.append('.').append(after.text)
                cursor.advance()
            }
        }
        return sb.toString()
    }

    /**
     * 剥点分前缀与反引号，取末段：`t.col`→`col`、`` `t`.`col` ``→`col`、`db.user`→`user`、
     * `` `col` ``→`col`。
     */
    private fun stripQualified(text: String): String {
        val last = text.substringAfterLast('.')
        return last.trim('`')
    }

    /** 字符串解转义：去首尾引号 + `''` → `'`。 */
    private fun decodeString(quoted: String): String {
        val inner = quoted.substring(1, quoted.length - 1)
        return inner.replace("''", "'")
    }

    private fun expectKeyword(cursor: Cursor, kw: String) {
        val t = cursor.current
        if (t == null || t.type != TokenType.WORD || !t.text.equals(kw, ignoreCase = true)) {
            throw SqlReverseException(t?.offset ?: cursor.offsetAtEnd(), KEY_PARSE, "缺少关键字 $kw")
        }
        cursor.advance()
    }

    private fun expectSymbol(cursor: Cursor, symbol: String) {
        val t = cursor.current
        if (t == null || t.type != TokenType.SYMBOL || t.text != symbol) {
            throw SqlReverseException(t?.offset ?: cursor.offsetAtEnd(), KEY_PARSE, "缺少符号 $symbol")
        }
        cursor.advance()
    }

    private fun unsupported(cursor: Cursor, what: String): Nothing {
        val t = cursor.current
        throw SqlReverseException(t?.offset ?: cursor.offsetAtEnd(), KEY_UNSUPPORTED, what)
    }

    /** 偏移量 → 行号（1 起）。 */
    internal fun lineOf(text: String, offset: Int): Int =
        text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' } + 1

    // ------------------------------------------------------------------
    // bundle key（UI 层翻译；纯字符串引用，parser 保持零 IDE 依赖）
    // ------------------------------------------------------------------

    const val KEY_NOT_SELECT = "reverse.error.notSelect"
    const val KEY_UNSUPPORTED = "reverse.error.unsupported"
    const val KEY_PARSE = "reverse.error.parse"

    /** Token 游标。 */
    private class Cursor(val tokens: List<Token>, val source: String) {
        var pos = 0

        val current: Token? get() = tokens.getOrNull(pos)

        fun peek(): Token? = tokens.getOrNull(pos + 1)

        fun advance(): Token? {
            val t = current
            if (t != null) pos++
            return t
        }

        fun previousOffset(): Int = tokens.getOrNull(pos - 1)?.offset ?: 0

        fun offsetAtEnd(): Int = tokens.lastOrNull()?.offset ?: 0

        fun isKeyword(t: Token?, kw: String): Boolean =
            t != null && t.type == TokenType.WORD && t.text.equals(kw, ignoreCase = true)

        fun isSymbol(t: Token?, symbol: String): Boolean =
            t != null && t.type == TokenType.SYMBOL && t.text == symbol
    }
}

/** 解析结果（T-M36.7 起支持增删改查；T-M37 支持多表 JOIN）：语句类型 + 表名 / select 列 / 条件 / 排序 / 分页。 */
data class SqlReverseResult(
    val tableName: String,
    val selectColumns: List<String>? = null,
    val conditions: List<Condition> = emptyList(),
    val orderBy: List<OrderByItem> = emptyList(),
    val limit: LimitClause? = null,
    val warnings: List<String> = emptyList(),
    val kind: ReverseStatementKind = ReverseStatementKind.SELECT,
    val sets: List<ReverseSet> = emptyList(),
    val insertColumns: List<String> = emptyList(),
    val insertValues: List<SqlValue> = emptyList(),
    val joins: List<ReverseJoin> = emptyList(),
)

/** 反向转换语句类型（T-M36.7）：增删改查全支持。 */
enum class ReverseStatementKind { SELECT, UPDATE, DELETE, INSERT }

/** UPDATE SET 赋值项（T-M36.7）：`列 = 值`；value 为 null 表示 SQL NULL（生成 Java null）；
 * rawSql 非空表示函数/表达式值原样透传（生成 setSql）。 */
data class ReverseSet(
    val column: String,
    val value: SqlValue?,
    val rawSql: String? = null,
)

/**
 * 联表子句（T-M37 多表 JOIN）：`[LEFT|RIGHT|INNER|FULL] JOIN 副表 [别名] ON 条件`。
 *
 * @param onConditions ON 条件（等值列对 → ColumnComparison；其他 → 普通条件/Raw 降级）
 * @param rawOn        ON 条件原文（token 区间收集，供生成器 raw join 字符串透传）
 */
data class ReverseJoin(
    val type: cn.song.mybatisplus.visualizer.model.JoinType,
    val tableName: String,
    val alias: String?,
    val onConditions: List<Condition>,
    val rawOn: String? = null,
)

/**
 * 反向解析错误。
 *
 * @param key    bundle 文案 key（[SqlReverseParser.KEY_*] 常量，UI 层翻译）
 * @param detail 错误详情（结构名/语法说明；含行号的定位信息由 UI 层按 offset 补充）
 */
class SqlReverseException(
    val offset: Int,
    val key: String,
    val detail: String,
) : RuntimeException("$key: $detail")
