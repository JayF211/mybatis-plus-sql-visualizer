package cn.song.mybatisplus.visualizer.xml

/**
 * 注解 SQL 解析器（T-M25-02）：MyBatis 注解（@Select/@Insert/@Update/@Delete 及 Provider 折叠结果）
 * 的 SQL 文本 → 预览模型。纯逻辑（输入已按 MyBatis 拼接规则处理的文本），PSI 提取在
 * [cn.song.mybatisplus.visualizer.ui.AnnotationSqlPreviewService]。
 *
 * 构建规则对齐 mybatis-3.5.19 源码（MapperAnnotationBuilder.buildSqlSourceFromStrings /
 * XMLLanguageDriver.createSqlSource）：
 * - `startsWith("<script>")` → 包 `<script>` 根走 DOM 解析 + [XmlScriptBuilder]——与 XML mapper
 *   语句同一解析路径（动态标签全兼容；`<include>`/`<sql>` 报 Unknown element，与 3.5.19 一致）；
 * - 否则纯文本节点（[TextSqlNode]：`${}`/`#{}` 语义与 XML 文本一致，`<`/`>` 无需转义）。
 */
class AnnotationSqlParser {

    /**
     * @param sqlText   注解 SQL 文本（调用方已按 `String.join(" ", value).trim()` 拼接）
     * @param kind      语句类型（注解类型直接映射 SqlCommandType）
     * @param paramNames 方法参数 mock 名（@Param 值优先 + 实际名 + param1..N）
     * @param staticFieldValues 静态字段值（T-M25.2）：`@class@field` → 值
     */
    fun parse(
        sqlText: String,
        kind: XmlStatementKind,
        paramNames: List<String>,
        staticFieldValues: Map<String, Any?> = emptyMap(),
    ): XmlPreviewModel {
        val node = if (sqlText.startsWith("<script>")) {
            parseScript(sqlText)
        } else {
            MixedSqlNode(listOf(TextSqlNode(sqlText)))
        }
        val statement = XmlStatement(id = "annotation", kind = kind, node = node, parameterType = null)
        return XmlPreviewModel.from(statement, emptyList(), extraParamProps = paramNames.toSet(), staticFieldValues = staticFieldValues)
    }

    private fun parseScript(scriptText: String): SqlNode {
        val document = XmlMapperParser().parseDocument(scriptText)
        val root = document.documentElement ?: throw XmlScriptException("script 内容为空。")
        if (root.tagName != "script") {
            throw XmlScriptException("注解 SQL 以 <script> 开头但根元素为 <${root.tagName}>。")
        }
        return XmlScriptBuilder().parse(root)
    }
}
