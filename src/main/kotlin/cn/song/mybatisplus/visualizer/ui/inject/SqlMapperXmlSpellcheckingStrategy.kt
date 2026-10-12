package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.Tokenizer
import com.intellij.spellchecker.xml.XmlSpellcheckingStrategy

/**
 * T-XmlGap W6 修订：**宿主 XML 文本**侧的拼写豁免（沙盒走查抓出的缺陷的修法）。
 *
 * ## 为什么需要这个类（走查实证，2026-10-12）
 *
 * [SqlMapperSpellcheckingStrategy]（挂在注入语言 `MpSqlMapper` 上）只豁免**注入片段**；
 * 而 mapper 语句文本的拼写检查发生在**宿主 XmlText** 上——平台 `XmlSpellcheckingStrategy`
 * 经 `SuppressibleSpellcheckingStrategy` 的 `hasForeignLanguageChildren` / `hasOnlySupportedDialects`
 * 判定是否回落检查原文（javap 实证），我方自研语言不在其「支持方言」集合内 ⇒ **回落**，SQL 文本上
 * 照样出现绿色 typo 波浪线（沙盒 IU-2024.3.7.1 截图实证）。
 *
 * ## 为什么这样实现（安全边界）
 *
 * 继承平台 [XmlSpellcheckingStrategy] 并**只对「mapper 语句标签下的 XmlText」返回空 tokenizer**，
 * 其余元素一律 `super.getTokenizer(element)` 原样委托——即：非目标 XML 的拼写行为与平台**逐字一致**，
 * 不存在「换了策略导致别的 XML 不检查」的风险。
 */
class SqlMapperXmlSpellcheckingStrategy : XmlSpellcheckingStrategy() {

    override fun getTokenizer(element: PsiElement): Tokenizer<*> {
        if (isMapperStatementText(element)) return SpellcheckingStrategy.EMPTY_TOKENIZER
        return super.getTokenizer(element)
    }

    /** 仅「mapper 文件 + 语句标签（含 `<sql>`）下的 XmlText」豁免；属性值不碰（注释/属性按平台原语义）。 */
    private fun isMapperStatementText(element: PsiElement): Boolean {
        val text = element as? XmlText ?: return false
        val file = text.containingFile as? XmlFile ?: return false
        if (file.rootTag?.name != "mapper") return false
        var tag: XmlTag? = PsiTreeUtil.getParentOfType(text, XmlTag::class.java, false)
        var depth = 0
        while (tag != null && depth++ < MAX_ANCESTOR_DEPTH) {
            if (tag.name in STATEMENT_TAGS) return true
            tag = tag.parentTag
        }
        return false
    }

    private companion object {
        val STATEMENT_TAGS = setOf("select", "insert", "update", "delete", "sql")
        const val MAX_ANCESTOR_DEPTH = 24
    }
}
