package cn.song.mybatisplus.visualizer.xml

import org.w3c.dom.Element
import org.w3c.dom.Node

/** XML 脚本构建错误（未知标签 / choose 多 otherwise / 缺失属性）。 */
class XmlScriptException(message: String) : RuntimeException(message)

/**
 * 脚本构建器（T-M24-01）：复刻 MyBatis 3.5.19 `XMLScriptBuilder` 的 parseDynamicTags——
 * 把语句元素递归解析为 SqlNode 树。
 *
 * - 文本 / CDATA 节点 → TextSqlNode（含 `${}`）或 StaticTextSqlNode；
 * - 元素节点 → 按标签名分派 handler（trim/where/set/foreach/if/choose/bind；
 *   when/otherwise 在 choose 内由 choose 专门处理，choose 外按 if/纯内容容错）；
 * - 未知标签 → [XmlScriptException]（对齐 MyBatis "Unknown element" 行为）；
 * - 任何 XML 元素都令语句为动态（对齐 MyBatis 判定；本引擎渲染路径统一，无静态/动态之分）。
 */
class XmlScriptBuilder {

    fun parse(statement: Element): SqlNode = MixedSqlNode(parseDynamicTags(statement).contents)

    private class DynamicResult(val contents: List<SqlNode>)

    private fun parseDynamicTags(node: Node): DynamicResult {
        val contents = mutableListOf<SqlNode>()
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            when {
                child is Element -> {
                    when (child.tagName) {
                        "trim" -> parseTrim(child, contents)
                        "where" -> contents += WhereSqlNode(MixedSqlNode(parseDynamicTags(child).contents))
                        "set" -> contents += SetSqlNode(MixedSqlNode(parseDynamicTags(child).contents))
                        "foreach" -> parseForeach(child, contents)
                        "if" -> contents += IfSqlNode(MixedSqlNode(parseDynamicTags(child).contents), child.getAttribute("test"))
                        "choose" -> parseChoose(child, contents)
                        "when" -> contents += IfSqlNode(MixedSqlNode(parseDynamicTags(child).contents), child.getAttribute("test"))
                        "otherwise" -> contents += MixedSqlNode(parseDynamicTags(child).contents)
                        "bind" -> contents += VarDeclSqlNode(child.getAttribute("name"), child.getAttribute("value"))
                        else -> throw XmlScriptException("Unknown element <${child.tagName}> in SQL statement.")
                    }
                }
                child.nodeType == Node.TEXT_NODE || child.nodeType == Node.CDATA_SECTION_NODE -> {
                    val data = child.nodeValue ?: ""
                    if (TextSqlNode.isDynamic(data)) contents += TextSqlNode(data)
                    else contents += StaticTextSqlNode(data)
                }
            }
        }
        return DynamicResult(contents)
    }

    private fun parseTrim(element: Element, contents: MutableList<SqlNode>) {
        contents += TrimSqlNode(
            contents = MixedSqlNode(parseDynamicTags(element).contents),
            prefix = element.getAttribute("prefix").ifEmpty { null },
            prefixesToOverride = splitOverrides(element.getAttribute("prefixOverrides")),
            suffix = element.getAttribute("suffix").ifEmpty { null },
            suffixesToOverride = splitOverrides(element.getAttribute("suffixOverrides")),
        )
    }

    private fun splitOverrides(raw: String): List<String>? = raw.takeIf { it.isNotBlank() }?.split("|")

    private fun parseForeach(element: Element, contents: MutableList<SqlNode>) {
        contents += ForeachSqlNode(
            contents = MixedSqlNode(parseDynamicTags(element).contents),
            collectionExpression = element.getAttribute("collection"),
            separator = element.getAttribute("separator").ifEmpty { null },
            open = element.getAttribute("open").ifEmpty { null },
            close = element.getAttribute("close").ifEmpty { null },
            item = element.getAttribute("item").ifEmpty { null },
            index = element.getAttribute("index").ifEmpty { null },
            nullable = element.getAttribute("nullable") == "true",
        )
    }

    private fun parseChoose(element: Element, contents: MutableList<SqlNode>) {
        val whenNodes = mutableListOf<SqlNode>()
        val defaultNodes = mutableListOf<SqlNode>()
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element) {
                when (child.tagName) {
                    "when" -> whenNodes += IfSqlNode(MixedSqlNode(parseDynamicTags(child).contents), child.getAttribute("test"))
                    "otherwise" -> defaultNodes += MixedSqlNode(parseDynamicTags(child).contents)
                }
            }
        }
        if (defaultNodes.size > 1) {
            throw XmlScriptException("Too many default (otherwise) elements in choose statement.")
        }
        contents += ChooseSqlNode(whenNodes, defaultNodes.firstOrNull())
    }
}
