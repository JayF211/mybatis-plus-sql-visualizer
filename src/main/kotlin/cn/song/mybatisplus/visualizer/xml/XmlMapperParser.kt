package cn.song.mybatisplus.visualizer.xml

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** 语句类型（mapper 文件中的 select/insert/update/delete 元素）。 */
enum class XmlStatementKind { SELECT, INSERT, UPDATE, DELETE }

/** 解析出的语句（id + SqlNode 树 + parameterType 元信息）。 */
data class XmlStatement(
    val id: String,
    val kind: XmlStatementKind,
    val node: SqlNode,
    val parameterType: String?,
)

/** mapper 文件解析结果（语句清单 + 解析期错误）。 */
data class XmlMapperModel(
    val namespace: String?,
    val statements: List<XmlStatement>,
    val errors: List<String>,
)

/** 解析失败（非良构 XML / 根元素不是 mapper）。 */
class XmlParseException(message: String) : RuntimeException(message)

/**
 * mapper 文件解析器（T-M24-01）：JDK DOM 解析（secure-processing，禁 DTD/外部实体），
 * 提取 `<mapper>` 下的 select/insert/update/delete 语句。
 *
 * - 先收集 `<sql id>` 片段供 include 展开（[XmlIncludeExpander]，同文件）；
 * - 语句解析前展开 include（就地修改 DOM）；
 * - 根元素非 `<mapper>` → [XmlParseException]；非良构 XML → 异常带解析器错误信息。
 */
class XmlMapperParser {

    fun parse(fileText: String): XmlMapperModel {
        val document = parseDocument(fileText)
        val root: Element = document.documentElement ?: throw XmlParseException("XML 文档为空。")
        if (root.tagName != "mapper") {
            throw XmlParseException("不是 MyBatis mapper 文件：根元素为 <${root.tagName}>（期望 <mapper>）。")
        }
        val errors = mutableListOf<String>()
        val namespaceRaw: String = root.getAttribute("namespace")
        val namespace = namespaceRaw.ifEmpty { null }

        val fragments: Map<String, Element> = childElements(root)
            .filter { it.tagName == "sql" && it.getAttribute("id").isNotEmpty() }
            .associateBy { it.getAttribute("id") }

        val statements = childElements(root)
            .filter { it.tagName in STATEMENT_TAGS }
            .map { element ->
                XmlIncludeExpander.expand(element, fragments, errors)
                XmlStatement(
                    id = element.getAttribute("id"),
                    kind = kindOf(element.tagName),
                    node = XmlScriptBuilder().parse(element),
                    parameterType = element.getAttribute("parameterType").ifEmpty { null },
                )
            }
        return XmlMapperModel(namespace, statements, errors)
    }

    /** 解析 XML 文本为 DOM（安全配置：禁 DTD/外部实体）。注解 `<script>` 等片段解析复用（T-M25-02）。 */
    internal fun parseDocument(fileText: String): org.w3c.dom.Document =
        runCatching { newDocumentBuilder().parse(InputSource(StringReader(fileText))) }
            .getOrElse { throw XmlParseException("XML 解析失败：${it.message}") }

    private fun childElements(element: Element): List<Element> {
        val result = mutableListOf<Element>()
        val children = element.childNodes
        for (i in 0 until children.length) {
            (children.item(i) as? Element)?.let { result += it }
        }
        return result
    }

    private fun kindOf(tagName: String): XmlStatementKind = when (tagName) {
        "select" -> XmlStatementKind.SELECT
        "insert" -> XmlStatementKind.INSERT
        "update" -> XmlStatementKind.UPDATE
        else -> XmlStatementKind.DELETE
    }

    private fun newDocumentBuilder() = DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder()

    companion object {
        val STATEMENT_TAGS = setOf("select", "insert", "update", "delete")
    }
}
