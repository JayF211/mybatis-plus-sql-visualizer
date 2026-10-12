package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.codeInsight.daemon.ChangeLocalityDetector
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

/**
 * T-XmlGap W6：mapper XML 语句文本编辑时**放大重算脏区**（竞品同型设计的收窄版）。
 *
 * 竞品把 SQL 文本编辑的脏区放大到**整文件**（内部调研报告 §2.3）；本实现收窄到**所属语句标签**：
 * 目的只是让该语句的注入与高亮及时重算（动态标签/`#{}` 改动会改变注入区间），把整文件拖进重算
 * 对大 mapper 是纯浪费——故取「语句标签」为界，收益/代价比更合理（该取舍记入计划 §实施记录）。
 *
 * 只在 mapper XML 内、且元素是语句标签下的 XmlText/XmlAttributeValue 时返回脏区；其余返回 null
 * （交回平台默认语义，零行为变化）。
 */
class SqlMapperChangeLocalityDetector : ChangeLocalityDetector {

    override fun getChangeHighlightingDirtyScopeFor(changedElement: PsiElement): PsiElement? {
        val file = changedElement.containingFile as? XmlFile ?: return null
        if (file.rootTag?.name != "mapper") return null
        val text = changedElement as? XmlText ?: changedElement.parent as? XmlText ?: return null
        val tag = text.parent as? XmlTag ?: return null
        var cur: XmlTag? = tag
        var depth = 0
        while (cur != null && depth++ < MAX_ANCESTOR_DEPTH) {
            if (cur.name in STATEMENT_TAGS) return cur
            cur = cur.parentTag
        }
        return null
    }

    private companion object {
        val STATEMENT_TAGS = setOf("select", "insert", "update", "delete", "sql")
        const val MAX_ANCESTOR_DEPTH = 24
    }
}
