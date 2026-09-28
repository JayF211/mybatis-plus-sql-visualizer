package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

/**
 * T-XmlSynHi D2/D3：mapper XML 注入器——
 *  - D2 语句文本：语句标签（select/insert/update/delete，祖先链 mapper）的**直属文本子节点**
 *    区间注入 [SqlMapperLanguage]（区间级而非整标签——标签名/属性不被注入；子标签内的文本由
 *    其各自所属语句标签或动态标签形态处理，遍历覆盖全部后代语句标签）；
 *  - D3 属性 OGNL：`test`/`value`/`collection`/`item` 属性值注入同语言（属性位无 SQL 文本，
 *    独立注入位；决策 1-A 注）；
 *  - 守卫：[XmlSynHiGate] 双让位（设置/平台/三方插件）+ 根 mapper + 文本长度熔断；
 *    让位命中 → 零注入（反向验证）。
 *
 * 形态：单遍 XmlElementVisitor 收集注入位（元素 + 元素内区间），非空才 startInjecting——
 * 零 addPlace 的 doneInjecting 不被平台容忍。
 */
class SqlMapperInjector : MultiHostInjector {

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val xmlFile = context as? XmlFile ?: return
        if (XmlSynHiGate.shouldYield(context.project)) return

        val places = mutableListOf<Pair<com.intellij.psi.PsiLanguageInjectionHost, TextRange>>()
        context.acceptChildren(object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                // D2 语句文本：直属文本子节点整体注入（长度熔断防万行级标签）
                for (child in tag.children) {
                    if (child is XmlText && child is com.intellij.psi.PsiLanguageInjectionHost &&
                        child.textLength in 1..XmlSynHiGate.MAX_TEXT_LENGTH
                    ) {
                        val r = trimRange(child.text)
                        if (!r.isEmpty) places += child to r.shiftRight(child.textRange.startOffset)
                    }
                }
                // D3 属性 OGNL：白名单属性值注入
                for (attr in tag.attributes) {
                    val name = attr.name
                    if (name == "test" || name == "value" || name == "collection" || name == "item") {
                        val valueElement = attr.valueElement as? com.intellij.psi.PsiLanguageInjectionHost
                        if (valueElement != null && valueElement.textLength in 1..4096) {
                            places += valueElement to TextRange(0, valueElement.textLength)
                        }
                    }
                }
                super.visitXmlTag(tag)
            }
        })

        if (places.isEmpty()) return
        registrar.startInjecting(SqlMapperLanguage)
        places.forEach { (element, range) -> registrar.addPlace(null, null, element, range) }
        registrar.doneInjecting()
    }

    /** 文本段内去首尾空白的注入区间（相对文本段）。 */
    private fun trimRange(text: String): TextRange {
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return TextRange(0, 0)
        val end = text.indexOfLast { !it.isWhitespace() }
        return TextRange(start, end + 1)
    }

    override fun elementsToInjectIn(): List<Class<out PsiElement>> =
        listOf(XmlText::class.java, XmlAttributeValue::class.java)
}
