package cn.song.mybatisplus.visualizer.xml

import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * include 展开器（T-M24-03）：复刻 MyBatis 3.5.19 `XMLIncludeTransformer.applyIncludes`（同文件形态）。
 *
 * - `<include refid="...">` 在**加载期**内联展开：深拷贝 `<sql id>` 片段的子节点到 include 位置；
 * - `<property name value>` 为**静态字符串替换**：只作用于片段中的 `${name}`（`#{}` 不替换），
 *   嵌套 include 继承外层 property（子 property 覆盖同名）；
 * - refid 支持 `${name}`（property 解析后查表）；跨命名空间 refid（含 `.` 且本文件无此片段）
 *   → 错误记录并移除 include（预览不中断）；
 * - 自引用/过深嵌套 → 错误记录（深度上限 [MAX_DEPTH]）。
 */
object XmlIncludeExpander {

    const val MAX_DEPTH = 20

    /** 展开语句元素中的全部 include（就地修改 DOM）。 */
    fun expand(statement: Element, fragments: Map<String, Element>, errors: MutableList<String>) {
        expandRecursive(statement, fragments, errors, 0, emptySet())
    }

    /**
     * 递归展开：入口先检查自身是否 include（嵌套 include 的拷贝体会以 include 元素身份进入）；
     * 同一 refid 在当前展开链上重复出现 → 循环截断（移除节点 + 错误记录，不残留）。
     */
    private fun expandRecursive(
        element: Element,
        fragments: Map<String, Element>,
        errors: MutableList<String>,
        depth: Int,
        chain: Set<String>,
    ) {
        if (element.tagName == "include") {
            if (depth > MAX_DEPTH) {
                errors += "Include nesting too deep (possible include cycle)."
                element.parentNode?.removeChild(element)
                return
            }
            val props = propertyMap(element)
            val refid = resolveRefid(element.getAttribute("refid"), props)
            if (refid in chain) {
                errors += "Include cycle detected: '$refid'."
                element.parentNode?.removeChild(element)
                return
            }
            val target = fragments[refid]
            if (target == null) {
                errors += "Include refid '$refid' not found in this mapper (cross-namespace include is not supported)."
                element.parentNode?.removeChild(element)
                return
            }
            val copies = copyChildren(target, props)
            replaceNode(element, copies)
            copies.filterIsInstance<Element>()
                .forEach { expandRecursive(it, fragments, errors, depth + 1, chain + refid) }
            return
        }
        // 快照遍历（展开会修改子节点集合）
        val children = element.childNodes
        val snapshot = (0 until children.length).map { children.item(it) }
        for (child in snapshot) {
            if (child is Element) expandRecursive(child, fragments, errors, depth + 1, chain)
        }
    }

    private fun propertyMap(include: Element): Map<String, String> {
        val props = mutableMapOf<String, String>()
        val children = include.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && child.tagName == "property") {
                val name = child.getAttribute("name")
                if (name.isNotEmpty()) props[name] = child.getAttribute("value")
            }
        }
        return props
    }

    /** refid 中的 `${name}` 按 property 静态替换（不在 property 中的保留原样，后续查表必然失败）。 */
    private fun resolveRefid(refid: String, props: Map<String, String>): String =
        TokenParser.parse(refid, "\${", "}") { props[it] ?: "\${$it}" }

    /** 深拷贝片段子节点：文本节点与属性值中的 `${name}` 按 property 替换（不污染原始片段）。 */
    private fun copyChildren(target: Element, props: Map<String, String>): List<Node> {
        val result = mutableListOf<Node>()
        val children = target.childNodes
        for (i in 0 until children.length) {
            val copy = children.item(i).cloneNode(true)
            applyProperties(copy, props)
            result += copy
        }
        return result
    }

    private fun applyProperties(node: Node, props: Map<String, String>) {
        when (node.nodeType) {
            Node.TEXT_NODE, Node.CDATA_SECTION_NODE ->
                node.nodeValue = replaceProps(node.nodeValue, props)
            Node.ELEMENT_NODE -> {
                val element = node as Element
                val attributes = element.attributes
                for (j in 0 until attributes.length) {
                    val attr = attributes.item(j)
                    attr.nodeValue = replaceProps(attr.nodeValue, props)
                }
                val children = element.childNodes
                for (k in 0 until children.length) {
                    applyProperties(children.item(k), props)
                }
            }
        }
    }

    private fun replaceProps(text: String?, props: Map<String, String>): String =
        if (text == null) ""
        else TokenParser.parse(text, "\${", "}") { props[it] ?: "\${$it}" }

    /** 用拷贝节点替换 include 节点（保持顺序；空片段 → 直接移除 include）。 */
    private fun replaceNode(include: Element, copies: List<Node>) {
        val parent = include.parentNode ?: return
        var anchor: Node = include
        copies.forEachIndexed { i, copy ->
            if (i == 0) parent.replaceChild(copy, anchor)
            else parent.insertBefore(copy, anchor.nextSibling)
            anchor = copy
        }
        if (copies.isEmpty()) parent.removeChild(include)
    }
}
