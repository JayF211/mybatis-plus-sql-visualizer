package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.SetClause
import cn.song.mybatisplus.visualizer.model.SqlValue

/**
 * JPQL/HQL UPDATE 语句文本解析器（J2，覆盖矩阵 §3「UPDATE ... SET 语句」行）。
 *
 * 输入是转换管道**已完成**参数替换（`:param`/`?1` → `?`）、实体名→表名、属性名→列名
 * 映射之后的 UPDATE 文本，例如：
 *
 * ```
 * UPDATE user u SET u.status = ?, u.remark = 'locked' WHERE u.id = ? AND u.age > 18
 * ```
 *
 * 解析产物直接映射既有 MP 侧模型 [UpdateStatement]：
 *  - SET 对：`[别名.]列 = 值` → [SetClause]（值支持 `?` 占位符 / 字面量）；
 *    别名前缀剥除（T-HqlUpdateEntityMap S2：单表 UPDATE 语义，对齐 Hibernate
 *    真实产物）；简单算术、函数、列引用表达式（如 `u.age = u.age + 1`、`u.updatedAt = NOW()`）
 *    超出模型 value 语义 → [SetClause.rawSql] 原样透传（raw 文本不剥别名，已知边界）
 *  - WHERE：只精细解析「AND 连接的简单比较 / IS [NOT] NULL / IN (值清单)」，
 *    列引用同样剥别名前缀；含 OR、括号嵌套、LIKE、BETWEEN、子查询等复杂形态 →
 *    整体作 [Condition.Raw] 透传（参数已替换为 `?`，渲染语义无损；Raw 文本不剥别名）
 *
 * 无状态单例，纯函数逻辑，可独立单测。解析失败返回 null（调用方维持原语句透传），
 * 绝不抛异常。
 */
internal object JpaUpdateParser {

    /**
     * UPDATE 语句解析产物。
     *
     * @param tableAlias 恒为 null（T-HqlUpdateEntityMap S2：HQL/JPQL UPDATE 是单表
     *        语义，Hibernate 真实产物丢弃别名——`update t set col=? where id=?`；
     *        头部别名仅用于识别结构，不再透出，渲染头部不拼 `表 别名`）
     * @param sets       SET 赋值列表（raw 透传形态含完整 `lhs = rhs` 文本；结构化形态
     *        的 [SetClause.column] 不带 tableAlias——单表 UPDATE 的 SET 左值带表别名
     *        在 MySQL 报 Unknown column、Oracle 不支持 UPDATE 别名）
     * @param conditions WHERE 条件（不可精细解析时为单个 [Condition.Raw]；无 WHERE 为空列表）
     */
    data class ParsedUpdate(
        val tableAlias: String?,
        val sets: List<SetClause>,
        val conditions: List<Condition>,
    )

    /** UPDATE 头部：`UPDATE 表 [别名] SET `（表名可能已被实体映射为带下划线的表名）。 */
    private val UPDATE_HEAD = Regex(
        "^\\s*UPDATE\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)(?:\\s+(?:AS\\s+)?([a-zA-Z_][a-zA-Z0-9_$]*))?\\s+SET\\s+",
        RegexOption.IGNORE_CASE,
    )

    /** SET 左值：`列` 或 `别名.列`（管道已完成属性→列名映射）。 */
    private val COLUMN_PATH = Regex("^[a-zA-Z_][a-zA-Z0-9_$]*(\\.[a-zA-Z_][a-zA-Z0-9_$]*)?$")

    /** 数值字面量（含负号与小数）。 */
    private val NUMERIC = Regex("^-?\\d+(\\.\\d+)?$")

    /** 解析 UPDATE 文本；非 UPDATE 或结构异常返回 null。 */
    fun parse(sql: String): ParsedUpdate? {
        val head = UPDATE_HEAD.find(sql) ?: return null
        val rest = sql.substring(head.value.length)

        // SET ... [WHERE ...]：顶层 WHERE 分界（括号内的 WHERE 属于子查询，不算）
        val whereIdx = indexOfTopLevelKeyword(rest, "WHERE")
        val setPart = if (whereIdx >= 0) rest.substring(0, whereIdx) else rest
        val wherePart = if (whereIdx >= 0) rest.substring(whereIdx + "WHERE".length).trim() else ""

        val sets = parseSetAssignments(setPart.trim().trimEnd(','))
        if (sets.isEmpty()) return null
        // T-HqlUpdateEntityMap S2：头部别名（head.groupValues[2]）仅用于结构识别，
        // 不透出——单表 UPDATE 渲染为 `UPDATE 表 SET 列 = ?`（Hibernate 真实产物形态）
        return ParsedUpdate(tableAlias = null, sets = sets, conditions = parseWhere(wherePart))
    }

    // ------------------------------------------------------------------
    // SET 子句
    // ------------------------------------------------------------------

    /**
     * 解析 SET 赋值列表：按顶层逗号切分后逐个拆 `lhs = rhs`。
     * 任一赋值无法结构化时以 rawSql 形态保留原文（不丢弃）。
     */
    private fun parseSetAssignments(setPart: String): List<SetClause> {
        if (setPart.isBlank()) return emptyList()
        return splitTopLevel(setPart, ',').mapNotNull { raw ->
            val assignment = raw.trim()
            if (assignment.isEmpty()) return@mapNotNull null
            parseAssignment(assignment)
        }
    }

    /** 解析单个赋值 `lhs = rhs`。 */
    private fun parseAssignment(assignment: String): SetClause {
        val eqIdx = indexOfTopLevel(assignment, '=')
        if (eqIdx <= 0) return SetClause(rawSql = assignment) // 无 = 或括号形态，raw 透传

        val lhs = assignment.substring(0, eqIdx).trim()
        val rhs = assignment.substring(eqIdx + 1).trim()
        if (lhs.isEmpty() || rhs.isEmpty()) return SetClause(rawSql = assignment)

        val value = classifyValue(rhs)
            ?: return SetClause(rawSql = assignment) // 算术/函数/列引用等复杂表达式 → raw

        if (!COLUMN_PATH.matches(lhs)) return SetClause(rawSql = assignment)
        // T-HqlUpdateEntityMap S2：剥别名前缀——单表 UPDATE 的 SET 左值不带表别名
        // （`u.status` → `status`；COLUMN_PATH 最多一段点号，substringAfterLast 安全）
        val column = ColumnRef(columnName = lhs.substringAfterLast('.'))
        return SetClause(column = column, value = value)
    }

    /**
     * 值表达式分类：`?` 占位符 / 字符串字面量 / 数值 / 布尔 / NULL → [SqlValue]；
     * 其余（含 `+ - * /` 算术、函数调用、列引用）→ null（调用方降级 raw）。
     */
    private fun classifyValue(expr: String): SqlValue? {
        if (expr == "?") return SqlValue.Placeholder(source = "?")
        if (expr.length >= 2 && expr.startsWith('\'') && expr.endsWith('\'')) {
            return SqlValue.Literal(raw = expr, unquoted = expr.substring(1, expr.length - 1))
        }
        if (NUMERIC.matches(expr)) return SqlValue.Literal(raw = expr)
        val upper = expr.uppercase()
        if (upper == "TRUE" || upper == "FALSE" || upper == "NULL") return SqlValue.Literal(raw = expr)
        return null
    }

    // ------------------------------------------------------------------
    // WHERE 子句
    // ------------------------------------------------------------------

    /**
     * 解析 WHERE 为条件树。只精细解析保守白名单形态：
     *  - `列 =|!=|<>|>|>=|<|<= 值`（值为 `?` 或字面量）
     *  - `列 IS [NOT] NULL`
     *  - `列 [NOT] IN (值清单)`
     * 其余（OR / 括号 / LIKE / BETWEEN / 子查询）整体 [Condition.Raw] 透传。
     */
    private fun parseWhere(whereText: String): List<Condition> {
        if (whereText.isBlank()) return emptyList()

        val upper = whereText.uppercase()
        // 复杂形态守卫：OR、LIKE、BETWEEN、EXISTS → Raw 透传。
        // 注意括号不在此守卫——`IN (?, ?)` 值清单合法含括号，分组括号/子查询
        // 会在逐项白名单解析失败后自然落到 Raw 兜底。
        val complex = Regex("\\bOR\\b").containsMatchIn(upper) ||
            Regex("\\bLIKE\\b").containsMatchIn(upper) ||
            Regex("\\bBETWEEN\\b").containsMatchIn(upper) ||
            Regex("\\bEXISTS\\b").containsMatchIn(upper)
        if (complex) return listOf(Condition.Raw(sqlText = whereText))

        val terms = splitTopLevel(whereText, marker = " AND ")
        val conditions = terms.map { parseSimpleTerm(it.trim()) }
        return if (conditions.all { it != null }) {
            conditions.filterNotNull()
        } else {
            listOf(Condition.Raw(sqlText = whereText))
        }
    }

    /** 解析单个 AND 项；白名单外返回 null（调用方整体 Raw 透传）。 */
    private fun parseSimpleTerm(term: String): Condition? {
        // IS [NOT] NULL
        Regex("^(.+?)\\s+IS\\s+NOT\\s+NULL$", RegexOption.IGNORE_CASE).find(term)?.let {
            val column = parseColumnPath(it.groupValues[1].trim()) ?: return null
            return Condition.Unary(column = column, op = "IS NOT NULL")
        }
        Regex("^(.+?)\\s+IS\\s+NULL$", RegexOption.IGNORE_CASE).find(term)?.let {
            val column = parseColumnPath(it.groupValues[1].trim()) ?: return null
            return Condition.Unary(column = column, op = "IS NULL")
        }
        // [NOT] IN (值清单)
        Regex("^(.+?)\\s+(NOT\\s+)?IN\\s*\\((.*)\\)$", RegexOption.IGNORE_CASE).find(term)?.let {
            val column = parseColumnPath(it.groupValues[1].trim()) ?: return null
            val values = splitTopLevel(it.groupValues[3], ',').mapNotNull { v ->
                classifyValue(v.trim())
            }
            if (values.isEmpty()) return null
            return Condition.In(
                column = column,
                negated = it.groupValues[2].isNotBlank(),
                values = values,
            )
        }
        // 比较条件：列 op 值
        val match = Regex("^(.+?)\\s*(<=|>=|<>|!=|=|<|>)\\s*(.+)$").find(term) ?: return null
        val column = parseColumnPath(match.groupValues[1].trim()) ?: return null
        val value = classifyValue(match.groupValues[3].trim()) ?: return null
        return Condition.Simple(column = column, op = match.groupValues[2], value = value)
    }

    /** 解析 `列` / `别名.列` 路径为 [ColumnRef]；白名单外返回 null（S2：别名前缀剥除）。 */
    private fun parseColumnPath(path: String): ColumnRef? {
        if (!COLUMN_PATH.matches(path)) return null
        return ColumnRef(columnName = path.substringAfterLast('.'))
    }

    // ------------------------------------------------------------------
    // 文本切分辅助（引号 / 括号感知）
    // ------------------------------------------------------------------

    /** 按单字符分隔符切分（括号嵌套与单引号字符串内的分隔符不切分）。 */
    private fun splitTopLevel(text: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var inQuote = false
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\'' -> inQuote = !inQuote
                c == '(' && !inQuote -> depth++
                c == ')' && !inQuote -> if (depth > 0) depth--
                c == separator && depth == 0 && !inQuote -> {
                    parts.add(text.substring(start, i))
                    start = i + 1
                }
            }
            i++
        }
        parts.add(text.substring(start))
        return parts
    }

    /** 按多字符关键字（前后带空格，如 " AND "）切分；括号与引号感知。 */
    private fun splitTopLevel(text: String, marker: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var inQuote = false
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\'' -> inQuote = !inQuote
                c == '(' && !inQuote -> depth++
                c == ')' && !inQuote -> if (depth > 0) depth--
                !inQuote && depth == 0 && text.regionMatches(i, marker, 0, marker.length, ignoreCase = true) -> {
                    parts.add(text.substring(start, i))
                    start = i + marker.length
                    i += marker.length - 1
                }
            }
            i++
        }
        parts.add(text.substring(start))
        return parts
    }

    /** 查找顶层（括号深度 0、引号外）关键字的起始下标；未找到返回 -1。 */
    private fun indexOfTopLevelKeyword(text: String, keyword: String): Int {
        val upper = text.uppercase()
        var depth = 0
        var inQuote = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\'' -> inQuote = !inQuote
                c == '(' && !inQuote -> depth++
                c == ')' && !inQuote -> if (depth > 0) depth--
            }
            if (!inQuote && depth == 0 && upper.startsWith(keyword, i)) {
                val beforeOk = i == 0 || !isIdentChar(text[i - 1])
                val end = i + keyword.length
                val afterOk = end >= text.length || !isIdentChar(text[end])
                if (beforeOk && afterOk) return i
            }
            i++
        }
        return -1
    }

    /** 查找顶层（括号深度 0、引号外）单字符位置。 */
    private fun indexOfTopLevel(text: String, target: Char): Int {
        var depth = 0
        var inQuote = false
        for (i in text.indices) {
            val c = text[i]
            when {
                c == '\'' -> inQuote = !inQuote
                c == '(' && !inQuote -> depth++
                c == ')' && !inQuote -> if (depth > 0) depth--
                c == target && depth == 0 && !inQuote -> return i
            }
        }
        return -1
    }

    private fun isIdentChar(c: Char) = c.isLetterOrDigit() || c == '_'
}
