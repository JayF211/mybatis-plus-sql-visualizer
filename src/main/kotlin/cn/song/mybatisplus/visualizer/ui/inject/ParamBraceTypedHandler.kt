package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * T-XmlSynHi D5：mapper XML 语句文本内敲 `#` / `$` 自动闭合 `{}`（光标落 #/{ 之间）。
 *
 * 「先查后插」双开防（决策 4-A，共存插件同型行为）：插入前查光标后方字符——
 *  - 已是 `{` → 不补插（对方插件/用户已手敲 `{`，双插会产出 `#{{}}`）；
 *  - 已是 `}` → 也不补插（包住已有 `}` 会让用户多删一次）——直接放行原字符。
 * 守卫：XmlSynHiGate 双让位（平台/CodeHelperPro 在位即不接管）+ 根 mapper + 语句标签内。
 */
class ParamBraceTypedHandler : TypedHandlerDelegate() {

    override fun beforeCharTyped(
        c: Char,
        project: Project,
        editor: Editor,
        file: PsiFile,
        fileType: FileType,
    ): TypedHandlerDelegate.Result {
        if (c != '#' && c != '$') return TypedHandlerDelegate.Result.CONTINUE
        if (file !is XmlFile) return TypedHandlerDelegate.Result.CONTINUE
        if (XmlSynHiGate.shouldYield(project)) return TypedHandlerDelegate.Result.CONTINUE
        val offset = editor.caretModel.offset
        val text = editor.document.text
        // 光标后方已有 `{`（对方插件已补/用户已敲）→ 放行，不双插
        if (offset < text.length && text[offset] == '{') return TypedHandlerDelegate.Result.CONTINUE
        // 光标须在 mapper 语句标签文本内（根 mapper 守卫 + 标签定位）
        val at = (offset - 1).coerceAtLeast(0)
        val el = file.findElementAt(at) ?: return TypedHandlerDelegate.Result.CONTINUE
        val tag = com.intellij.psi.util.PsiTreeUtil.getParentOfType(el, XmlTag::class.java) ?: return TypedHandlerDelegate.Result.CONTINUE
        if (!inStatementText(tag)) return TypedHandlerDelegate.Result.CONTINUE

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(offset, "$c{}")
            editor.caretModel.moveToOffset(offset + 1)
        }
        return TypedHandlerDelegate.Result.STOP
    }

    /** 光标前字符所属标签是否为语句标签（mapper 根守卫 + 语句标签集，与 injector 同口径）。 */
    private fun inStatementText(tag: XmlTag): Boolean {
        if (!XmlSynHiGate.inMapperFile(tag)) return false
        return tag.name in STATEMENT_TAGS || tag.parentTag != null && tag.parentTag!!.name in STATEMENT_TAGS
    }

    private companion object {
        private val STATEMENT_TAGS = setOf("select", "insert", "update", "delete")
    }

}
