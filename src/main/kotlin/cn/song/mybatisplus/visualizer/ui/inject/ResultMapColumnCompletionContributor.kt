package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext

/**
 * T-XmlSynHi D5 后半：resultMap `column` 属性值补全（ResultMapColumns 接线）。
 *
 * 触发位：`<result|id … column="…">` 属性值（resultMap 上下文）。
 * 候选 = mapper 全文 SELECT 投影列名（[ResultMapColumnCompletionMemo.columnsFor]），
 * 按已输入前缀过滤/排序（[ResultMapColumns.suggest]：前缀精确 > 下划线驼峰近邻 > Levenshtein）。
 * 非结果映射上下文零候选（反向验证）。
 *
 * T-PerfSweep16 F40：补全链接让位门（[XmlSynHiGate.shouldYield]，与 typedHandler 口径对齐）
 * + 全文重扫单槽 memo（键=文件 url + modificationStamp，stamp 变化即失效）。
 */
class ResultMapColumnCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(PsiElement::class.java).withParent(XmlAttributeValue::class.java),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val position = parameters.position
                    val attrValue = position.parent as? XmlAttributeValue ?: return
                    val attr = attrValue.parent as? XmlAttribute ?: return
                    if (attr.name != "column") return
                    val tag = attr.parent as? XmlTag ?: return
                    if (tag.name != "result" && tag.name != "id") return
                    if (!inResultMap(tag)) return
                    // T-PerfSweep16 F40：让位门（设置关/平台注入器/三方插件在场 → 零候选）
                    if (XmlSynHiGate.shouldYield(position.project)) return
                    val file = tag.containingFile as? XmlFile ?: return
                    val columns = ResultMapColumnCompletionMemo.columnsFor(file)
                    val typed = currentTyped(attrValue, parameters)
                    ResultMapColumns.suggest(columns, typed).forEach { candidate ->
                        result.addElement(
                            com.intellij.codeInsight.lookup.LookupElementBuilder.create(candidate),
                        )
                    }
                }
            },
        )
    }

    /** 已输入前缀（属性值内光标前文本；引号由 XmlAttributeValue 边界排除在外）。 */
    private fun currentTyped(attrValue: XmlAttributeValue, parameters: CompletionParameters): String {
        val offset = parameters.offset
        val start = attrValue.textRange.startOffset + 1 // 跳过开引号
        return if (offset in start..attrValue.textRange.endOffset) {
            attrValue.text.substring(0, (offset - start).coerceAtMost(attrValue.text.length))
        } else attrValue.text
    }

    /** 祖先链存在 resultMap 标签（result/id 行属于结果映射上下文）。 */
    private fun inResultMap(tag: XmlTag): Boolean {
        var cur: XmlTag? = tag.parentTag
        while (cur != null) {
            if (cur.name == "resultMap") return true
            cur = cur.parentTag
        }
        return false
    }
}

/**
 * T-PerfSweep16 F40：`extractColumns` 全文重扫的单槽 memo（进程内一份，补全触发高频）。
 *
 * 键 = 文件 url + modificationStamp + 文本长度（指纹组合）：stamp 变化即失效；**长度是
 * 实测补上的第二键**——测试夹具与部分内存态文件的 LightVirtualFile 在文档变更后
 * modificationStamp 不动（ResultMapColumnCompletionMemoTest 真红实证），单靠 stamp 会
 * 返回陈旧候选。编辑后自然回源重扫。
 * [extractor] 为 internal 缝——测试注入计数 lambda 断言「二次补全零重扫」。
 */
internal object ResultMapColumnCompletionMemo {

    /** 提取函数缝（默认=纯函数本体；测试可注入计数 lambda）。 */
    internal var extractor: (String) -> List<String> = { text -> ResultMapColumns.extractColumns(text) }

    @Volatile private var memoUrl: String? = null
    @Volatile private var memoStamp: Long = Long.MIN_VALUE
    @Volatile private var memoLen: Int = -1
    @Volatile private var memoValue: List<String> = emptyList()

    /** 命中 memo 直接返回缓存（零重扫）；键不匹配才全文提取并回填。 */
    internal fun columnsFor(file: XmlFile): List<String> {
        val vf = file.virtualFile
        val url = vf?.url
        val stamp = vf?.modificationStamp ?: file.modificationStamp
        val len = file.textLength
        if (url == memoUrl && stamp == memoStamp && len == memoLen) return memoValue
        val value = extractor(file.text)
        memoUrl = url
        memoStamp = stamp
        memoLen = len
        memoValue = value
        return value
    }

    /** 测试缝：清空 memo，保证断言从冷态起算。 */
    internal fun resetForTest() {
        memoUrl = null
        memoStamp = Long.MIN_VALUE
        memoLen = -1
        memoValue = emptyList()
    }
}
