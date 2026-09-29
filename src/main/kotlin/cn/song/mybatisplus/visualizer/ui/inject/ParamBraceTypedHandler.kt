package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.injected.editor.DocumentWindow
import com.intellij.injected.editor.EditorWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * T-XmlSynHi D5：mapper XML 语句文本内敲 `#` / `$` 自动闭合 `{}`（光标落 #/{ 之间）。
 *
 * 「先查后插」防双插（决策 4-A，共存插件同型行为）：插入前查光标后方字符——
 *  - 已是 `{` → 不补插（对方插件/用户已手敲 `{`，双插会产出 `#{{}}`）——直接放行原字符。
 *  （T-PerfSweep16 F46 口径对齐：历史 KDoc 声称 `}` 后也放行，实现自始只查 `{`——修文档不改行为。）
 * 守卫：XmlSynHiGate 双让位（平台/CodeHelperPro 在位即不接管）+ 根 mapper + 语句标签内。
 *
 * 243+ 注入片段击键路由（TypedHandler.callDelegates 字节码实证）：光标在注入片段内时
 * 传给本回调的 [file] 是**注入 PsiFile**（SQL 语言）而非宿主 XmlFile、[editor] 是
 * EditorWindow——须还原宿主三件套（hostFile/hostEditor/hostOffset）再套守卫；宿主路径
 * （标签标记区等非注入位）三件套原样即位。
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
        val inInjected = file !is XmlFile
        val hostFile = if (inInjected) {
            InjectedLanguageManager.getInstance(project).getInjectionHost(file)
                ?.containingFile as? XmlFile
                ?: return TypedHandlerDelegate.Result.CONTINUE
        } else {
            file
        }
        if (XmlSynHiGate.shouldYield(project)) return TypedHandlerDelegate.Result.CONTINUE
        val hostEditor = (editor as? EditorWindow)?.delegate ?: editor
        val offset = if (inInjected) {
            (editor.document as? DocumentWindow)
                ?.injectedToHost(editor.caretModel.offset)
                ?: editor.caretModel.offset
        } else {
            editor.caretModel.offset
        }
        // T-PerfSweep16 F39：getCharsSequence 直读底层文本（原 document.text 每次击键整文档复制一份 String）
        val text = hostEditor.document.charsSequence
        // 光标后方已有 `{`（对方插件已补/用户已敲）→ 放行，不双插
        if (offset < text.length && text[offset] == '{') return TypedHandlerDelegate.Result.CONTINUE
        // 光标须在 mapper 语句标签文本内（根 mapper 守卫 + 标签定位）；宿主偏移左邻字符定位
        val at = (offset - 1).coerceAtLeast(0)
        val el = hostFile.findElementAt(at) ?: return TypedHandlerDelegate.Result.CONTINUE
        val tag = PsiTreeUtil.getParentOfType(el, XmlTag::class.java)
            ?: return TypedHandlerDelegate.Result.CONTINUE
        if (!inStatementText(tag, offset)) return TypedHandlerDelegate.Result.CONTINUE

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            hostEditor.document.insertString(offset, "$c{}")
            hostEditor.caretModel.moveToOffset(offset + 1)
        }
        return TypedHandlerDelegate.Result.STOP
    }

    /**
     * 光标前字符所属标签是否为语句文本位（mapper 根守卫 + 语句标签集，与 injector 同口径）。
     * 子标签两态分界（20260928 真机实证）：光标落子标签**边界之后**（如 `</if>` 右侧，
     * 属父语句内容区）→ 接管；落子标签**自身标记区内**（如 `<trim prefix=|>` 属性间隙）→
     * 不接管——第二态曾因 parentTag∈语句标签 的宽判全放，全靠 EP 未注册的死代码遮掩。
     */
    private fun inStatementText(tag: XmlTag, offset: Int): Boolean {
        if (!XmlSynHiGate.inMapperFile(tag)) return false
        if (tag.name in STATEMENT_TAGS) return true
        val parent = tag.parentTag ?: return false
        if (parent.name !in STATEMENT_TAGS) return false
        return offset >= tag.textRange.endOffset
    }

    private companion object {
        private val STATEMENT_TAGS = setOf("select", "insert", "update", "delete")
    }

}
