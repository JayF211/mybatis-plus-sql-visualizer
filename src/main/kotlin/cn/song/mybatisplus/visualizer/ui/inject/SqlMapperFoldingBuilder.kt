package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * T-XmlGap W6：mapper XML 语句标签的**带 id 折叠**（竞品 `v.a` 的同型能力，内部调研报告 §2.3）。
 *
 * ## 与平台 XML 折叠的关系（这是本类的设计前提，勿删）
 *
 * 平台自带的 `com.intellij.lang.xml.XmlFoldingBuilder`（基类 `XmlCodeFoldingBuilder`）**已经**为 XML
 * 标签建 fold 区，占位文本是标签名本身（`XmlCodeFoldingBuilder.getRangeToFold` 取
 * `XmlTagUtil.getStartTagNameElement`）；`XmlCodeFoldingSettings.isCollapseXmlTags()` 只控制
 * **默认是否折叠**，不影响 fold 区是否存在（javap 实证，2026.2 平台）。
 *
 * 因此本类的价值**不是**「让标签可折叠」（平台已有），而是**把占位文本换成带 id 的形态**
 * （`select#findById`）——长 mapper 里折叠后一眼能认出是哪个语句，这是平台给不了的。
 *
 * 作用面刻意收窄到最小，避免与平台抢 fold 区：
 *  - 只在**根标签为 `mapper`** 的文件里生效；
 *  - 只对**语句标签**（select/insert/update/delete）与带 `id` 的 `<sql>` 建区；
 *  - 只对**跨多行**的标签建区（单行标签折叠无视觉意义，且更易与平台区重叠）；
 *  - 不自带默认折叠（`isCollapsedByDefault = false`，与平台「不自动折叠 XML 标签」一致）。
 *
 * 与平台区**范围相同**时的占位优先级属渲染期行为，单测只能钉本类产出；真机观感列入 W8 走查项
 * （计划 §十 遗留）。
 */
class SqlMapperFoldingBuilder : FoldingBuilderEx() {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val file = root.containingFile as? XmlFile ?: return FoldingDescriptor.EMPTY
        if (file.rootTag?.name != "mapper") return FoldingDescriptor.EMPTY
        val out = mutableListOf<FoldingDescriptor>()
        for (tag in PsiTreeUtil.findChildrenOfType(root, XmlTag::class.java)) {
            if (tag.name !in FOLDABLE_TAGS) continue
            val id = tag.getAttributeValue("id")
            if (id.isNullOrBlank()) continue
            val range = tag.textRange
            if (!spansMultipleLines(document, range.startOffset, range.endOffset)) continue
            out += FoldingDescriptor(tag, range.startOffset, range.endOffset, null, "${tag.name}#$id")
        }
        return out.toTypedArray()
    }

    /** 与平台一致：不自动折叠（平台侧由 `isCollapseXmlTags` 用户设置决定）。 */
    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    /**
     * 占位文本（`FoldingBuilder` 接口的抽象成员；`FoldingBuilderEx` 只提供二参重载）。
     *
     * 本类的区都在 [buildFoldRegions] 里带了显式占位，故本方法实际不被走到；仍按同一形态实现，
     * 避免被平台在其它调用点问到时空手（返回 null 会落到「用原文」的默认行为）。
     */
    override fun getPlaceholderText(node: ASTNode): String? {
        val tag = node.psi as? XmlTag ?: return null
        if (tag.name !in FOLDABLE_TAGS) return null
        val id = tag.getAttributeValue("id") ?: return null
        return "${tag.name}#$id"
    }

    private fun spansMultipleLines(document: Document, start: Int, end: Int): Boolean {
        if (document.textLength == 0) return false
        val safeStart = start.coerceIn(0, document.textLength - 1)
        val safeEnd = end.coerceIn(0, document.textLength - 1)
        return document.getLineNumber(safeStart) != document.getLineNumber(safeEnd)
    }

    private companion object {
        /** 可折叠标签：语句标签 + 带 id 的 `<sql>` 片段（resultMap 族不折——它们通常很短且 id 语义弱）。 */
        val FOLDABLE_TAGS = setOf("select", "insert", "update", "delete", "sql")
    }
}
