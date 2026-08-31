package cn.song.mybatisplus.visualizer.share.ui

import cn.song.mybatisplus.visualizer.share.SqlShareManager
import cn.song.mybatisplus.visualizer.share.format.HtmlReportFormatter
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileChooser.FileSaverDialog
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridLayout
import java.io.File
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JRadioButton

/**
 * 分享选项对话框（F-8）。
 *
 * 提供四种分享方式：
 *  - 复制为 Markdown 代码块
 *  - 复制为 Markdown 表格
 *  - 复制为可执行 SQL
 *  - 导出 HTML 报告文件
 *
 * 用户可填写来源位置（文件名:行号），附到分享内容中。
 */
class ShareDialog(
    private val project: Project?,
    private val panel: SqlPreviewPanel,
) : DialogWrapper(project, true) {

    private val sourceField = JBTextField(30)
    private val markdownRadio = JRadioButton(MpSqlVisualizerBundle.message("share.dialog.markdown"))
    private val tableRadio = JRadioButton(MpSqlVisualizerBundle.message("share.dialog.markdownTable"))
    private val executableRadio = JRadioButton(MpSqlVisualizerBundle.message("share.dialog.executableSql"))
    private val htmlRadio = JRadioButton(MpSqlVisualizerBundle.message("share.dialog.html"))

    private val shareManager = SqlShareManager(project)

    init {
        title = MpSqlVisualizerBundle.message("share.dialog.title")
        // F-46：预填充当前代码位置（文件名:行号），用户可编辑覆盖
        val loc = panel.currentSourceLocation() ?: computeCurrentLocation()
        if (!loc.isNullOrEmpty()) {
            sourceField.text = loc
        }
        init()
    }

    /** 从当前编辑器实时计算代码位置（文件名:行号），兜底 panel 未设置 sourceLocation 的场景。 */
    private fun computeCurrentLocation(): String? {
        val proj = project ?: return null
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(proj).selectedTextEditor ?: return null
        val virtualFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        val line = editor.document.getLineNumber(editor.caretModel.offset) + 1
        return "${virtualFile.name}:$line"
    }

    override fun createCenterPanel(): JComponent {
        val root = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10)
        }

        // 来源输入行
        val sourceRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(JBLabel(MpSqlVisualizerBundle.message("share.dialog.source") + ":"), BorderLayout.WEST)
            add(sourceField, BorderLayout.CENTER)
            border = JBUI.Borders.emptyBottom(10)
        }
        root.add(sourceRow)

        // 选项单选
        val group = ButtonGroup().apply {
            add(markdownRadio)
            add(tableRadio)
            add(executableRadio)
            add(htmlRadio)
        }
        markdownRadio.isSelected = true

        val optionsPanel = JBPanel<JBPanel<*>>(GridLayout(4, 1, 0, 4)).apply {
            add(markdownRadio)
            add(tableRadio)
            add(executableRadio)
            add(htmlRadio)
            border = JBUI.Borders.emptyBottom(10)
        }
        root.add(optionsPanel)

        return root
    }

    override fun getPreferredFocusedComponent(): JComponent = sourceField

    /** 测试缝：返回中心面板（与 [createCenterPanel] 同构），供测试遍历断言。DialogWrapper 的
     *  rootPane/contentPane 仅在 show() 时构建，headless 下构造即可用本缝取面板。 */
    internal fun centerPanelForTest(): JComponent = createCenterPanel()

    override fun createActions(): Array<Action> = arrayOf(
        okAction.apply { putValue(Action.NAME, MpSqlVisualizerBundle.message("dialog.ok")) },
        cancelAction.apply { putValue(Action.NAME, MpSqlVisualizerBundle.message("share.dialog.cancel")) },
    )

    override fun doOKAction() {
        val source = sourceField.text.trim().takeIf { it.isNotEmpty() }
        when {
            markdownRadio.isSelected -> shareManager.copyAsMarkdown(panel, source)
            tableRadio.isSelected -> shareManager.copyAsMarkdownTable(panel, source)
            executableRadio.isSelected -> shareManager.copyExecutableSql(panel, source)
            htmlRadio.isSelected -> exportHtmlReport()
        }
        super.doOKAction()
    }

    /** 导出 HTML 报告（复用 [HtmlReportFormatter]）。 */
    private fun exportHtmlReport() {
        val sql = panel.editorDocumentText()?.takeIf { it.isNotBlank() } ?: return
        val html = HtmlReportFormatter.format(
            sql = sql,
            sources = panel.unresolvedParams(),
            paramValues = panel.paramValuesSnapshot(),
            tableName = panel.currentTableName(),
            dialect = panel.currentDialect(),
        )
        val descriptor = FileSaverDescriptor(
            MpSqlVisualizerBundle.message("share.dialog.html"),
            "Save HTML report",
            "html",
        )
        val dialog: FileSaverDialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val saved = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, "sql-report.html")
        if (saved != null) {
            val file = saved.file
            shareManager.saveHtmlReport(html, file.absolutePath)
            BrowserUtil.open(file.toURI().toString())
        }
    }
}
