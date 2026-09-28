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
 * 候选 = mapper 全文 SELECT 投影列名（[ResultMapColumns.extractColumns]），
 * 按已输入前缀过滤/排序（[ResultMapColumns.suggest]：前缀精确 > 下划线驼峰近邻 > Levenshtein）。
 * 非结果映射上下文零候选（反向验证）。
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
                    val file = tag.containingFile as? XmlFile ?: return
                    val columns = ResultMapColumns.extractColumns(file.text)
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
