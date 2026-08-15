package cn.song.mybatisplus.visualizer.reverse

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
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
        val cursor = Cursor(tokens)

        // SELECT 语句头
        if (!cursor.isKeyword(cursor.current, "SELECT")) {
            val first = cursor.current
            throw SqlReverseException(
                first?.offset ?: 0,
                KEY_NOT_SELECT,
                first?.takeIf { it.type == TokenType.WORD }?.text ?: "?",
            )
        }
        cursor.advance()

        val selectColumns = parseSelectColumns(cursor)

        // FROM 表名（可选库前缀/别名）
        if (!cursor.isKeyword(cursor.current, "FROM")) {
            throw SqlReverseException(cursor.current?.offset ?: sqlText.length, KEY_PARSE, "缺少 FROM 子句")
        }
        cursor.advance()
        val tableName = parseTableName(cursor)
        consumeOptionalAlias(cursor)

        // 子句序列：WHERE / ORDER BY / LIMIT（顺序可任意，仅各出现一次）
        val warnings = mutableListOf<String>()
        var conditions = emptyList<Condition>()
        var orderBy = emptyList<OrderByItem>()
        var limit: LimitClause? = null
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

        return SqlReverseResult(
            tableName = tableName,
            selectColumns = selectColumns,
            conditions = conditions,
            orderBy = orderBy,
            limit = limit,
            warnings = warnings,
        )
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
                    if (isFunctionCall(cursor)) {
                        anyComplex = true
                        skipExpression(cursor)
                        continue
                    }
                    columns.add(stripQualified(consumeQualified(cursor)))
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

    /** 可选表别名：`FROM user u`——u 非子句关键字时视为别名消费。 */
    private fun consumeOptionalAlias(cursor: Cursor) {
        val t = cursor.current ?: return
        if (t.type == TokenType.WORD && !cursor.isKeyword(t, "WHERE") && !cursor.isKeyword(t, "GROUP") &&
            !cursor.isKeyword(t, "ORDER") && !cursor.isKeyword(t, "LIMIT") && !cursor.isKeyword(t, "HAVING") &&
            !cursor.isKeyword(t, "UNION") && !cursor.isKeyword(t, "JOIN") && !cursor.isKeyword(t, "FOR")
        ) {
            cursor.advance()
        }
    }

    // ------------------------------------------------------------------
    // WHERE 条件序列
    // ------------------------------------------------------------------

    /** 条件序列：顶层 AND 连接；OR / 空条件直接报错（P1 范围）。 */
    private fun parseConditionList(cursor: Cursor, warnings: MutableList<String>): List<Condition> {
        val conditions = mutableListOf<Condition>()
        while (true) {
            val t = cursor.current ?: break
            if (t.type == TokenType.WORD && isClauseStart(cursor)) break
            if (t.type == TokenType.SYMBOL && t.text == ";") break
            if (cursor.isKeyword(t, "AND")) {
                cursor.advance()
                continue
            }
            if (cursor.isKeyword(t, "OR")) unsupported(cursor, "OR 嵌套条件")
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

    /** 单个条件：`列 操作符 值`。 */
    private fun parseCondition(cursor: Cursor, warnings: MutableList<String>): Condition {
        val column = parseColumnRef(cursor)
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
            // NOT IN / NOT BETWEEN / NOT LIKE
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
            // 列名后紧跟 `(` → 函数表达式（DATE_FORMAT(...) 等，P1 范围）
            t.type == TokenType.SYMBOL && t.text == "(" ->
                unsupported(cursor, "函数表达式 ${column.columnName}(...)")
            else -> throw SqlReverseException(
                t.offset,
                KEY_PARSE,
                "无法识别的条件操作符：${t.text}",
            )
        }
    }

    /** 二元比较：值可为 NULL（= NULL → IS NULL，!= NULL → IS NOT NULL）。 */
    private fun parseBinary(cursor: Cursor, column: ColumnRef, sqlOp: String): Condition {
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

    /** 列引用：`col` / `t.col` / `` `col` `` / `` `t`.`col` ``（前缀剥离，取末段）。 */
    private fun parseColumnRef(cursor: Cursor): ColumnRef {
        val text = consumeQualified(cursor)
        return ColumnRef(columnName = stripQualified(text))
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
    private class Cursor(val tokens: List<Token>) {
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

/** 解析结果（P0）：表名 / select 列（null=不生成）/ 条件 / 排序 / 分页。 */
data class SqlReverseResult(
    val tableName: String,
    val selectColumns: List<String>? = null,
    val conditions: List<Condition> = emptyList(),
    val orderBy: List<OrderByItem> = emptyList(),
    val limit: LimitClause? = null,
    val warnings: List<String> = emptyList(),
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
