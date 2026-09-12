package cn.song.mybatisplus.visualizer.generator

/**
 * 数据库类型 → 参数回填引号提示（T-CrudSql-03，纯函数）。
 *
 * 与实体侧 [cn.song.mybatisplus.visualizer.sql.ParamQuote.decide]（Java 字段类型判定）对应：
 * DB 侧入口只有 SQL 类型名（自省元数据），按类型名关键词判定——数值族 false（原样）、
 * 其余 true（加引号）、类型未知 null（面板数字启发式兜底）。
 */
object DbTypeQuoteHint {

    /** 数值族类型名关键词（小写子串匹配；覆盖 int/bigint/decimal/numeric/float/double/number/serial 等）。 */
    private val NUMERIC_MARKERS = listOf(
        "int", "decimal", "numeric", "float", "double", "real", "bit", "bool",
        "number", "serial", "money", "year", "tinyint", "smallint", "mediumint",
    )

    /**
     * 判定引号。
     *
     * @param dbTypeName SQL 类型名（如 `BIGINT`、`VARCHAR(64)`、`decimal(10,2)`；自省缺失为 null）
     * @return true=加引号；false=数值原样；null=类型未知（面板启发式兜底）
     */
    fun decide(dbTypeName: String?): Boolean? {
        val base = dbTypeName?.lowercase()?.substringBefore('(')?.trim()
        if (base.isNullOrEmpty()) return null
        // 数值族 → false（原样），其余（文本/时间等）→ true（加引号）
        return !NUMERIC_MARKERS.any { base.contains(it) }
    }
}
