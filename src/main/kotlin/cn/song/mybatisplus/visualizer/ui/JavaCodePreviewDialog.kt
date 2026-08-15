package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Java 代码预览弹窗（SQL→MP Java 代码 反向转换的结果展示）。
 *
 * 只读等宽字体预览生成代码，底部提供"复制代码 / 关闭"。由
 * [cn.song.mybatisplus.visualizer.actions.ConvertSqlToJavaAction] 触发。
 */
class JavaCodePreviewDialog(
    private val project: Project?,
    private val code: String,
) : DialogWrapper(project, true) {

    private val textArea = JTextArea(code).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        tabSize = 4
        caretPosition = 0
    }

    init {
        title = MpSqlVisualizerBundle.message("reverse.dialog.title")
        init()
        setCancelButtonText(MpSqlVisualizerBundle.message("dialog.preview.cancel"))
    }

    override fun createCenterPanel(): JComponent = JScrollPane(textArea).apply {
        preferredSize = Dimension(DIALOG_WIDTH, DIALOG_HEIGHT)
        minimumSize = Dimension(DIALOG_MIN_WIDTH, DIALOG_MIN_HEIGHT)
    }

    override fun createActions(): Array<Action> = arrayOf(
        object : DialogWrapperAction(MpSqlVisualizerBundle.message("reverse.dialog.copy")) {
            override fun doAction(actionEvent: java.awt.event.ActionEvent?) {
                copyCode()
            }
        },
        cancelAction,
    )

    /** 复制生成代码到系统剪贴板（对齐 SqlPreviewPanel.copySql 的复制实现）。 */
    private fun copyCode() {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(code), null)
        Messages.showInfoMessage(
            project,
            MpSqlVisualizerBundle.message("reverse.copy.done"),
            MpSqlVisualizerBundle.message("plugin.name"),
        )
    }

    /** internal 测试访问器（渲染文本断言，仿 SqlSelectionDialog.listForTest）。 */
    internal fun textAreaForTest(): JTextArea = textArea

    companion object {
        const val DIALOG_WIDTH = 680
        const val DIALOG_HEIGHT = 420
        const val DIALOG_MIN_WIDTH = 480
        const val DIALOG_MIN_HEIGHT = 240
    }
}
