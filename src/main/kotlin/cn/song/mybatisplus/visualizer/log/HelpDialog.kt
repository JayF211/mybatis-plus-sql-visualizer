package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBHtmlEditorKit
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JEditorPane

/**
 * 帮助弹窗（T-M39）：侧边栏工具栏「?」按钮 → 只读 HTML 帮助内容。
 *
 * 内容来自 bundle（i18n 双语，核心功能 / 使用方法 / 默认快捷键 / 反馈入口）。
 * 渲染跟随 IDE 主题（用户反馈「样式割裂」后改造，对齐 IDEA 帮助信息观感）：
 *  - [JBHtmlEditorKit]——HTML 渲染使用 IDE 字体解析，非 Swing 默认；
 *  - `isOpaque = false`——背景透出面板色（默认白底与弹窗灰底割裂）；
 *  - 前景取 Label 前景色（LAF 主题文字色），未指定颜色的文本/标题继承；
 *  - `HONOR_DISPLAY_PROPERTIES`——HTML 相对字号从组件字体（Label 字体）派生。
 */
class HelpDialog(project: Project?) : DialogWrapper(project, true) {

    private val editorPane = JEditorPane().apply {
        contentType = "text/html"
        // 主题化 HTML 渲染 kit（字体/颜色解析走 IDE 主题）
        editorKit = JBHtmlEditorKit()
        isEditable = false
        // 背景透出面板色（JDK 默认 EditorPane 白底，与弹窗背景割裂）
        isOpaque = false
        foreground = UIUtil.getLabelForeground()
        // HTML 相对字号按组件字体缩放（默认 Swing 字体而非 IDE UI 字体，观感割裂）
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

        /** 内容排版样式（对齐 IDEA 帮助信息观感：紧凑行高、标题间距、列表缩进）。 */
        private const val HELP_CSS = "" +
            "body { line-height: 1.4; }" +
            "h3 { margin: 14px 0 6px; }" +
            "ul { margin: 4px 0 10px; padding-left: 22px; }" +
            "li { margin-bottom: 5px; }" +
            "p { margin: 6px 0; }"

        /** 帮助 HTML 全文（各节 bundle 拼装；internal 供测试断言关键片段）。 */
        internal fun buildHtml(): String = buildString {
            append("<html><head><style>$HELP_CSS</style></head><body>")
            append(MpSqlVisualizerBundle.message("help.content.features"))
            append(MpSqlVisualizerBundle.message("help.content.usage"))
            append(MpSqlVisualizerBundle.message("help.content.shortcuts"))
            append(MpSqlVisualizerBundle.message("help.content.other"))
            append("</body></html>")
        }
    }
}
