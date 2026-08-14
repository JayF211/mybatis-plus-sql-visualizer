package cn.song.mybatisplus.visualizer.xml

/**
 * MP 占位符展开辅助（T-M26-B，P-立项-03）：`${变量.customSqlSegment}` / `${变量.sqlSelect}` / `${变量.sqlSet}`
 * 的识别与 SQL 片段提取。纯逻辑（无 PSI），Java 侧关联在
 * [cn.song.mybatisplus.visualizer.ui.XmlEwResolver]。
 *
 * 片段语义对齐 MP（AbstractWrapper）：
 * - customSqlSegment → `WHERE (条件...)`（wrapper 条件非空时；空则整段空串）
 * - sqlSelect → SELECT 列清单（`SELECT ` 与首个 ` FROM ` 之间）
 * - sqlSet → UPDATE 的 SET 片段（`SET ` 与 ` WHERE ` 之间）
 */
object XmlEwExpander {

    /** `${变量.片段类型}` 占位识别（customSqlSegment/sqlSelect/sqlSet）。 */
    val EW_PLACEHOLDER_REGEX = Regex("""\$\{(\w+)\.(customSqlSegment|sqlSelect|sqlSet)\}""")

    /** 扫描文本中的占位（去重，保持出现顺序）。 */
    fun placeholders(text: String): List<EwPlaceholder> =
        EW_PLACEHOLDER_REGEX.findAll(text)
            .map { EwPlaceholder(it.groupValues[1], it.groupValues[2]) }
            .distinct()
            .toList()

    /** customSqlSegment 片段：`WHERE (条件)`（wrapper 生成 SQL 的 WHERE 之后部分）。 */
    fun extractWhere(sql: String): String? {
        val compact = sql.replace(WHITESPACE_REGEX, " ").trim()
        val idx = compact.indexOf(" WHERE ")
        if (idx < 0) return null
        val wherePart = compact.substring(idx + " WHERE ".length).trim()
        return if (wherePart.isEmpty()) null else "WHERE ($wherePart)"
    }

    /** sqlSelect 片段：SELECT 列清单（首个 FROM 之前）。 */
    fun extractSelect(sql: String): String? {
        val compact = sql.replace(WHITESPACE_REGEX, " ").trim()
        val start = compact.indexOf("SELECT ")
        if (start < 0) return null
        val fromIdx = compact.indexOf(" FROM ", start)
        if (fromIdx < 0) return null
        return compact.substring(start + "SELECT ".length, fromIdx).trim()
    }

    /** sqlSet 片段：UPDATE 的 SET 内容（SET 与 WHERE 之间）。 */
    fun extractSet(sql: String): String? {
        val compact = sql.replace(WHITESPACE_REGEX, " ").trim()
        val start = compact.indexOf("SET ")
        if (start < 0) return null
        val whereIdx = compact.indexOf(" WHERE ", start)
        val end = if (whereIdx > 0) whereIdx else compact.length
        return compact.substring(start + "SET ".length, end).trim()
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
}

/** MP 占位：变量名（@Param 名）+ 片段类型。 */
data class EwPlaceholder(val variable: String, val kind: String)
