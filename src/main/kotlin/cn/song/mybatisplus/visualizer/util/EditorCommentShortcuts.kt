package cn.song.mybatisplus.visualizer.util

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange

/**
 * 预览编辑器「行注释快捷键」统一为 `--` 双横线（T-PreviewLineCommentDash O-134/D136）。
 *
 * 为什么必须在这个层位（开发计划 §一 全链实证）：预览文件被 [com.intellij.execute.DialectPreMapping]
 * 强制映射 MySQL 方言 → 平台注释动作取 commenter 链拿到 `MysqlCommenter` → 行前缀 `#`
 * （javap 双版本实锤 `SHARP_LINE_PREFIX = "# "`）→ 对 PostgreSQL 执行报 `[42601] syntax error`。
 * 接管 Ctrl+/（`IdeActions.ACTION_COMMENT_LINE`）对打标编辑器改走自有逐行切换，前缀一律 `--`；
 * 映射与 PSI 方言一律不动（否决 A/B1/B2 的理由见开发计划 §二）。
 *
 * 机制（EditorActionHandler 链式覆写）：首个打标编辑器出现时 `EditorActionManager.setActionHandler`
 * 换入自有 handler（返回值即原 handler，持有它做直通）；**未打标编辑器直通原 handler（零行为差异）**；
 * 最后一个打标编辑器卸载时恢复原 handler（引用计数）。作用域靠编辑器 userData 打标判定，
 * 不触语言级 `lang.commenter` EP（B1 会波及真实 MySQL 控制台/文件）。
 *
 * 语义契约：受影响行集 = 选区触及的行（行粒度；无选区 = 光标行，多光标各算各的、自底向上套用）；
 * **整段同态切换**（对齐平台手感）：全部非空行已是注释 → 剥除；否则补前缀，其中已是注释的行幂等跳过。
 * 前缀双认 `--` 与遗留 `#`（本轮修复前插入的 `#` 残留行也要能剥掉；双认先例 MysqlClientGSuffix.isCommentLine），
 * 剥除时连带吞掉前缀后**恰好一个**空格。文档改动包 WriteCommandAction（一步撤销）。
 *
 * D137：Ctrl+Shift+/ 块注释**不接管**——javap 双版本核实 MysqlCommenter 基类
 * `getBlockCommentPrefix()="/*"` / `getBlockCommentSuffix()="*/"`，与 PG 语义一致，缩小作用面。
 *
 * 测试缝照 [EditorCopyShortcuts]：纯逻辑（行级切换）与本对象可见，单测不依赖平台 action 分发。
 */
internal object EditorCommentShortcuts {

    /** 打标 Key：userData 在场 = 该编辑器的行注释走本接管。 */
    private val MARKER = Key.create<Boolean>("mpsv.editor.lineCommentDash")

    /** 行注释插入前缀（PG/MySQL 通用双横线）。 */
    private const val DASH_PREFIX = "--"

    /** 遗留 `#` 前缀（本轮修复前 MySQL commenter 产出，残留行兼容剥除）。 */
    private const val LEGACY_PREFIX = "#"

    private var installedHandler: DashLineCommentHandler? = null
    private var refCount = 0

    /** 给 [editor] 打标并确保全局 handler 已换入（幂等：重复打标不重复计数）。 */
    @Synchronized
    fun install(editor: Editor) {
        if (editor.getUserData(MARKER) == true) return
        editor.putUserData(MARKER, true)
        val manager = EditorActionManager.getInstance()
        if (installedHandler == null) {
            val original = manager.getActionHandler(IdeActions.ACTION_COMMENT_LINE)
            val handler = DashLineCommentHandler(original)
            manager.setActionHandler(IdeActions.ACTION_COMMENT_LINE, handler)
            installedHandler = handler
            refCount = 0
        }
        refCount++
    }

    /** 摘除 [editor] 打标；最后一个打标编辑器卸载时恢复原 handler（幂等：未打标零动作）。 */
    @Synchronized
    fun uninstall(editor: Editor) {
        if (editor.getUserData(MARKER) != true) return
        editor.putUserData(MARKER, null)
        refCount--
        if (refCount <= 0) {
            installedHandler?.let {
                val manager = EditorActionManager.getInstance()
                // 恢复 = 把当前在位的自有 handler 换回注册时捕获的原 handler（setActionHandler 返回前者）
                val current = manager.getActionHandler(IdeActions.ACTION_COMMENT_LINE)
                if (current === it) {
                    manager.setActionHandler(IdeActions.ACTION_COMMENT_LINE, it.originalHandler)
                }
            }
            installedHandler = null
            refCount = 0
        }
    }

    /**
     * 行级切换纯函数：**全已注释 → 全剥；否则补 `-- `，已注释行幂等跳过**。
     * 空白行参与范围但双态都原样保留（剥无可剥、补了徒增噪音）。
     */
    internal fun toggleLines(lines: List<String>): List<String> {
        val nonBlank = lines.filter { it.isNotBlank() }
        val uncomment = nonBlank.isNotEmpty() && nonBlank.all { isCommentedLine(it) }
        return lines.map { line ->
            when {
                line.isBlank() -> line
                uncomment -> stripCommentPrefix(line)
                isCommentedLine(line) -> line
                else -> addCommentPrefix(line)
            }
        }
    }

    /** 行内容（跳过前导空白）是否以行注释开头——`--` 与遗留 `#` 双认（先例 MysqlClientGSuffix.kt:99-104）。 */
    internal fun isCommentedLine(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith(DASH_PREFIX) || t.startsWith(LEGACY_PREFIX)
    }

    /** 在首个非空白字符前插 `-- `（空白行原样保留，由调用方过滤）。 */
    private fun addCommentPrefix(line: String): String {
        val i = line.indexOfFirst { !it.isWhitespace() }
        return if (i < 0) line else line.substring(0, i) + DASH_PREFIX + " " + line.substring(i)
    }

    /** 剥 `--` / `#` 前缀并连带吞掉前缀后恰好一个空格；无前缀原样返回。 */
    private fun stripCommentPrefix(line: String): String {
        val t = line.trimStart()
        val prefix = when {
            t.startsWith(DASH_PREFIX) -> DASH_PREFIX
            t.startsWith(LEGACY_PREFIX) -> LEGACY_PREFIX
            else -> return line
        }
        val start = line.length - t.length
        var end = start + prefix.length
        if (end < line.length && line[end] == ' ') end++
        return line.substring(0, start) + line.substring(end)
    }

    /**
     * 对打标编辑器执行逐行切换（WriteCommandAction 包裹，一步撤销）。
     * 多光标各算各的受影响行集、自底向上套用（后套用的不挪先套用的偏移）。
     */
    private fun toggleLineComments(editor: Editor) {
        val document = editor.document
        if (!document.isWritable || editor.isViewer) return
        // 每个光标的受影响行集：有选区 → 选区触及的行（行粒度）；无选区 → 光标所在行
        data class Affected(
            val caret: Caret,
            val startLine: Int,
            val endLine: Int,
            val hasSelection: Boolean,
            val caretColumn: Int,
        )
        val affected = editor.caretModel.allCarets.mapNotNull { caret ->
            val caretOffset = caret.offset
            if (caret.hasSelection()) {
                val startLine = document.getLineNumber(caret.selectionStart)
                val endLine = document.getLineNumber(caret.selectionEnd)
                Affected(caret, startLine, maxOf(startLine, endLine), true, caretOffset - document.getLineStartOffset(startLine))
            } else {
                val line = document.getLineNumber(caretOffset)
                Affected(caret, line, line, false, caretOffset - document.getLineStartOffset(line))
            }
        }.distinctBy { it.startLine to it.endLine }.sortedByDescending { it.startLine }
        if (affected.isEmpty()) return
        val project = editor.project
        val commandName = MpSqlVisualizerBundle.message("preview.lineComment.commandName")
        val applyEdits = Runnable {
            for (a in affected) {
                val startOffset = document.getLineStartOffset(a.startLine)
                val endOffset = document.getLineEndOffset(a.endLine)
                val oldText = document.getText(TextRange(startOffset, endOffset))
                val oldLines = oldText.split('\n')
                val newLines = toggleLines(oldLines)
                if (newLines == oldLines) continue
                val newText = newLines.joinToString("\n")
                document.replaceString(startOffset, endOffset, newText)
                // 行索引不因切换而变（不增删行），光标/选区按新文本复位
                val newEnd = startOffset + newText.length
                if (a.hasSelection) {
                    a.caret.setSelection(startOffset, newEnd)
                }
                val newLineStart = document.getLineStartOffset(a.startLine)
                a.caret.moveToOffset(minOf(newLineStart + a.caretColumn, document.getLineEndOffset(a.startLine)))
            }
        }
        if (project != null) {
            WriteCommandAction.runWriteCommandAction(project, commandName, null, applyEdits)
        } else {
            // 无项目编辑器（三级降级兜底分支）：无命令宿主，仅保证写动作合法
            ApplicationManager.getApplication().runWriteAction(applyEdits)
        }
    }

    /**
     * 链式 handler：未打标编辑器直通原 handler（零行为差异）；打标编辑器走自有逐行切换。
     * 平台钩子可空性按 243/262 javap 双版本一致：`doExecute(Editor, Caret?, DataContext?)`、
     * `isEnabledForCaret(Editor, Caret, DataContext)`（后者三参 @NotNull）。
     */
    internal class DashLineCommentHandler(internal val originalHandler: EditorActionHandler) : EditorActionHandler() {

        override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
            if (editor.getUserData(MARKER) != true) {
                originalHandler.execute(editor, caret, dataContext)
                return
            }
            toggleLineComments(editor)
        }

        override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext): Boolean {
            if (editor.getUserData(MARKER) == true) {
                return editor.document.isWritable && !editor.isViewer
            }
            return originalHandler.isEnabled(editor, dataContext)
        }
    }
}
