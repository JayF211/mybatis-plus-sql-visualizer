package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.ui.highlight.SqlPreviewLanguage
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.Alarm

/**
 * 工具窗口编辑器跟随（M9 工具窗口交互重设计，方案 1）。
 *
 * 监听活动编辑器光标移动，防抖后转换光标处 Wrapper 并推送到工具窗口，
 * 实现「强制定位到代码某处」——真实工程文件里实体/注解可解析，`@TableName` 等准确。
 *
 * 行为：
 *  - 仅当 [SqlToolWindow.isFollowEditor] 开启（工具栏开关）且编辑器为当前活动编辑器时转换
 *  - 跳过本插件自己的预览编辑器（SqlPreviewLanguage PSI 支撑，避免循环）
 *  - 防抖 [FOLLOW_DEBOUNCE_MS]；文件级缓存（F-20）保证同文件重复转换复用
 *  - 编辑器释放时清理监听与 Alarm（防泄漏）
 *
 * 注册见 plugin.xml `<editorFactoryListener>`。
 */
class SqlPreviewEditorTracker : EditorFactoryListener {

    private val alarms = mutableMapOf<Editor, Alarm>()

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        // 跳过我们自己的预览编辑器（SqlPreviewLanguage PSI 支撑）
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        if (psiFile.language == SqlPreviewLanguage) return

        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD)
        alarms[editor] = alarm
        val listener = object : CaretListener {
            override fun caretPositionChanged(e: CaretEvent) {
                if (!SqlToolWindow.isFollowEditor) return
                alarm.cancelAllRequests()
                alarm.addRequest({
                    // 仅当前活动编辑器
                    if (FileEditorManager.getInstance(project).selectedTextEditor != editor) return@addRequest
                    convertAtCaret(project, editor)
                }, FOLLOW_DEBOUNCE_MS)
            }
        }
        editor.caretModel.addCaretListener(listener)
        editor.putUserData(CARET_LISTENER_KEY, listener)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        editor.getUserData(CARET_LISTENER_KEY)?.let { editor.caretModel.removeCaretListener(it) }
        editor.putUserData(CARET_LISTENER_KEY, null)
        alarms.remove(editor)?.dispose()
    }

    /** 转换光标处 Wrapper 并推送到工具窗口（无 Wrapper 时保持现状）。后台线程 + ReadAction。 */
    private fun convertAtCaret(project: Project, editor: Editor) {
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        val element = psiFile.findElementAt(editor.caretModel.offset) ?: return
        SqlPreviewService.previewAsync(project, {
            project.getService(SqlPreviewService::class.java).preview(element)
        }) { result ->
            if (result != null) SqlToolWindow.findPanel(project)?.showResult(result)
        }
    }

    companion object {
        private const val FOLLOW_DEBOUNCE_MS = 300L
        private val CARET_LISTENER_KEY = Key.create<CaretListener>("MP_SQL_CARET_LISTENER")
    }
}
