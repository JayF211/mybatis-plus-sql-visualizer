package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

/**
 * T-XmlGap W5-4：OGNL 变量来源收集（除既有的方法参数 + 主实体字段之外的全部来源）。
 *
 * 收集项（按需求逐条）：
 *  1. `<bind name="x">` —— 在**其之后**的位置可见（同语句/`<sql>` 片段内、文档顺序在光标之前）；
 *  2. `<foreach item="i" index="k" collection="c">` —— `i`/`k` 在该 foreach **内**可见；
 *     `c`（collection 属性值）若是标识符形态也收录（可指向 bind 变量或参数）；
 *  3. `include` 的 `<property name="p">` —— 在**被 include 的 `<sql>` 片段内**可见
 *     （按 `refid` 反查本文件里引用该 `<sql>` 的 include 节点；同文件解析，跨文件见边界说明）；
 *  4. mybatis-config.xml `<properties>` 的 key（全项目聚合，[XmlCompletionSymbols.configPropertyKeys] 带缓存）；
 *  5. MyBatis 内置 `_databaseId`、`_parameter`。
 *
 * 边界（如实登记）：
 *  - include property 只反查**本文件**的 `<include refid>`（跨文件反查需索引往返，W7 引用导航另做）；
 *  - bind 可见性按「文档顺序在光标之前」判定，不做 if/choose 分支内的作用域收窄（MyBatis bind 实际
 *    是语句级绑定，顺序判定已符合其语义）；
 *  - 全部外部调用 `runCatching` 包裹，失败降级空列表。
 */
internal object OgnlVariableSources {

    /**
     * 收集变量（去重保序）。
     *
     * @param includeConfigProperties 是否并入 mybatis-config.xml `<properties>` 的 key
     *        （OGNL/`${}` 位置为 true；`#{}` 位置为 false——config properties 是解析期替换的，
     *        不是语句参数映射的来源）。
     */
    fun collect(
        caretElement: PsiElement,
        enclosingTag: XmlTag,
        project: Project,
        includeConfigProperties: Boolean,
    ): List<String> {
        val out = LinkedHashSet<String>()
        runCatching {
            val caretOffset = caretElement.textRange.startOffset
            collectBinds(enclosingTag, caretOffset, out)
            collectForeachAncestors(caretElement, enclosingTag, out)
            collectIncludeProperties(caretElement, out)
            if (includeConfigProperties) out += XmlCompletionSymbols.configPropertyKeys(project)
        }
        // MyBatis 内置变量（恒可用，无需解析）
        out += "_databaseId"
        out += "_parameter"
        return out.toList()
    }

    /** `<bind name>`：同作用域内、文档顺序在光标之前的 bind 变量。 */
    private fun collectBinds(enclosingTag: XmlTag, caretOffset: Int, out: MutableSet<String>) {
        PsiTreeUtil.findChildrenOfType(enclosingTag, XmlTag::class.java)
            .asSequence()
            .filter { it.name == "bind" }
            .filter { it.textRange.startOffset < caretOffset }
            .mapNotNull { it.getAttributeValue("name")?.takeIf { n -> n.isNotBlank() } }
            .forEach { out += it }
    }

    /** foreach 祖先的 item/index（及 collection 属性值的标识符形态）。 */
    private fun collectForeachAncestors(caretElement: PsiElement, enclosingTag: XmlTag, out: MutableSet<String>) {
        var current: XmlTag? = PsiTreeUtil.getParentOfType(caretElement, XmlTag::class.java, false)
        while (current != null) {
            val tag: XmlTag = current
            if (tag.name == "foreach") {
                listOf("item", "index").forEach { attr ->
                    tag.getAttributeValue(attr)?.takeIf { it.isNotBlank() }?.let { out += it }
                }
                tag.getAttributeValue("collection")
                    ?.trim()
                    ?.takeIf { IDENTIFIER_PATH.matches(it) }
                    ?.let { out += it }
            }
            if (tag === enclosingTag) break
            current = tag.parentTag
        }
    }

    /**
     * include property：若光标位于某 `<sql id="X">` 片段内，反查本文件里 `refid` 指向 X 的
     * `<include>`，收集其 `<property name>` 值（include 传入的参数名）。
     */
    private fun collectIncludeProperties(caretElement: PsiElement, out: MutableSet<String>) {
        val sqlTag = ancestorSqlTag(caretElement) ?: return
        val sqlId = sqlTag.getAttributeValue("id")?.trim()?.takeIf { it.isNotBlank() } ?: return
        var top: XmlTag = sqlTag
        while (top.parentTag != null) top = top.parentTag!!
        PsiTreeUtil.findChildrenOfType(top, XmlTag::class.java)
            .asSequence()
            .filter { it.name == "include" }
            .filter { include -> refidMatches(include.getAttributeValue("refid"), sqlId) }
            .flatMap { include ->
                PsiTreeUtil.findChildrenOfType(include, XmlTag::class.java)
                    .asSequence()
                    .filter { it.name == "property" }
            }
            .mapNotNull { it.getAttributeValue("name")?.takeIf { n -> n.isNotBlank() } }
            .forEach { out += it }
    }

    /** 光标祖先链上最近的 `<sql>` 标签（用于 include property 可见域判定）。 */
    private fun ancestorSqlTag(caretElement: PsiElement): XmlTag? {
        var current: XmlTag? = PsiTreeUtil.getParentOfType(caretElement, XmlTag::class.java, false)
        while (current != null) {
            if (current.name == "sql") return current
            current = current.parentTag
        }
        return null
    }

    /** `refid` 是否指向 `sqlId`（精确或 `namespace.sqlId` 尾段匹配）。 */
    private fun refidMatches(refid: String?, sqlId: String): Boolean {
        val ref = refid?.trim()?.takeIf { it.isNotBlank() } ?: return false
        return ref == sqlId || ref.endsWith(".$sqlId")
    }

    /** 标识符路径（可含点，如 `user.deptId`）。 */
    private val IDENTIFIER_PATH = Regex("""^[\w$]+(\.[\w$]+)*$""")
}
