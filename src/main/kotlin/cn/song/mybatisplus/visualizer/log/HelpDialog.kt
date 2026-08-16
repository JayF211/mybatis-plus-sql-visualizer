package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JEditorPane

/**
 * 帮助弹窗（T-M39）：侧边栏工具栏「?」按钮 → 只读 HTML 帮助内容。
 *
 * 内容来自 bundle（i18n 双语，核心功能 / 使用方法 / 默认快捷键 / 反馈入口）；
 * JEditorPane 渲染（朴素 HTML 子集，无外部资源依赖，离线可用），字体跟随 IDE
 * （HONOR_DISPLAY_PROPERTIES 使 HTML 相对字号从组件字体派生）。
 */
class HelpDialog(project: Project?) : DialogWrapper(project, true) {

    private val editorPane = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false
        // HTML 渲染按组件字体缩放（默认用 Swing 字体而非 IDE UI 字体，割裂观感）
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = UIUtil.getLabelFont()
        text = buildHtml()
        caretPosition = 0
    }

    init {
        title = MpSqlVisualizerBundle.message("help.dialog.title")
        init()
        // 关闭按钮文案走插件 bundle（平台 cancelAction 随 IDE 语言包，插件语言设置独立于 IDE）
        setCancelButtonText(MpSqlVisualizerBundle.message("help.dialog.close"))
    }

    override fun createCenterPanel(): JComponent = JBScrollPane(editorPane).apply {
        preferredSize = Dimension(DIALOG_WIDTH, DIALOG_HEIGHT)
        minimumSize = Dimension(DIALOG_MIN_WIDTH, DIALOG_MIN_HEIGHT)
    }

    /** 单「关闭」按钮（帮助属临时查看，无其他操作）。 */
    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    /** internal 测试访问器（渲染文本断言，仿 JavaCodePreviewDialog.textAreaForTest）。 */
    internal fun contentForTest(): JEditorPane = editorPane

    companion object {
        const val DIALOG_WIDTH = 560
        const val DIALOG_HEIGHT = 460
        const val DIALOG_MIN_WIDTH = 420
        const val DIALOG_MIN_HEIGHT = 300

        /** 帮助 HTML 全文（各节 bundle 拼装；internal 供测试断言关键片段）。 */
        internal fun buildHtml(): String = buildString {
            append("<html><body>")
            append(MpSqlVisualizerBundle.message("help.content.features"))
            append(MpSqlVisualizerBundle.message("help.content.usage"))
            append(MpSqlVisualizerBundle.message("help.content.shortcuts"))
            append(MpSqlVisualizerBundle.message("help.content.other"))
            append("</body></html>")
        }
    }
}
