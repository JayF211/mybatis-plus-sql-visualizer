package cn.song.mybatisplus.visualizer.xml

/**
 * 参数映射（T-M24-01）：`#{...}` token 解析产物，属性名顺序对应 SQL 中 `?` 顺序。
 *
 * @param property      参数属性名（foreach 内为重写后的 `__frch_item_0.name` 形态）
 * @param attributes    token 附加属性（jdbcType / typeHandler / javaType / mode / numericScale 等，首段无 `=` 者为 property）
 * @param originalToken token 原文（面板展示用）
 */
data class ParameterMapping(
    val property: String,
    val attributes: Map<String, String>,
    val originalToken: String,
) {
    companion object {
        /** 解析 token 内容（对齐 SqlSourceBuilder.buildParameterMapping 的简化形态）。 */
        fun parse(token: String): ParameterMapping {
            var property = ""
            val attributes = mutableMapOf<String, String>()
            token.split(',').forEach { segment ->
                val part = segment.trim()
                if (part.isEmpty()) return@forEach
                val eq = part.indexOf('=')
                if (eq > 0) {
                    val key = part.substring(0, eq).trim()
                    val value = part.substring(eq + 1).trim()
                    if (key.isNotEmpty()) attributes[key] = value
                } else if (property.isEmpty()) {
                    property = part
                }
            }
            return ParameterMapping(property, attributes, token)
        }
    }
}

/** 渲染产物：SQL（`#{}` 已替换为 `?`）+ 参数映射 + 错误提示。 */
data class RenderedXmlSql(
    val sql: String,
    val mappings: List<ParameterMapping>,
    val errors: List<String>,
)

/**
 * 渲染器（T-M24-01）：复刻 MyBatis 3.5.19 `DynamicSqlSource.getBoundSql` 的前两遍——
 *
 *  1. SqlNode 树 apply（分支判定 / `${}` 替换 / bind 累积，bindings 由调用方提供）；
 *  2. `#{}` → `?` + ParameterMapping 清单（[TokenParser] 等价 GenericTokenParser；
 *     `\#{` 转义；无 `}` 原样保留）。
 *
 * 第三遍（参数值填充 PreparedStatement）预览场景不需要——参数值由面板回填输入。
 */
class XmlSqlRenderer {

    fun render(
        node: SqlNode,
        bindings: Map<String, Any?>,
        conditionOverrides: Map<String, Boolean> = emptyMap(),
        protectedNames: Set<String> = emptySet(),
    ): RenderedXmlSql {
        val context = DynamicContext(bindings, conditionOverrides, protectedNames)
        node.apply(context)
        return parseParameterMappings(context.getSql(), context.errors)
    }

    private fun parseParameterMappings(sql: String, errors: List<String>): RenderedXmlSql {
        val mappings = mutableListOf<ParameterMapping>()
        val rendered = TokenParser.parse(sql, "#{", "}") { token ->
            mappings += ParameterMapping.parse(token)
            "?"
        }
        return RenderedXmlSql(rendered, mappings, errors)
    }
}
