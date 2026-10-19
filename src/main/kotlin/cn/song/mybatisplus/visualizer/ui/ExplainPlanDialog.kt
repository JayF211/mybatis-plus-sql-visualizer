package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

/**
 * T-PreviewSideQuery P2：非模态承载 EXPLAIN 结果，不替换预览编辑器。
 *
 * 结构化视图（[ExplainPlanResultView]）与直送结果表格都通过本对话框展示；关闭后
 * 预览 SQL/参数仍在原处可继续编辑。
 */
internal class ExplainPlanDialog(
    project: Project,
    private val content: JComponent,
) : DialogWrapper(project, false) {

    init {
        title = MpSqlVisualizerBundle.message("panel.explain")
        isModal = false
        init()
    }

    override fun createCenterPanel(): JComponent = JBScrollPane(content).apply {
        preferredSize = Dimension(900, 560)
        border = JBUI.Borders.empty()
    }

    override fun createActions(): Array<javax.swing.Action> = arrayOf(cancelAction)

    internal fun contentForTest(): JComponent = content
}

/** 测试缝：headless 不显示真实窗口；生产为 null 走 dialog.show()。 */
@Volatile
internal var explainDialogSinkForTest: ((ExplainPlanDialog) -> Unit)? = null

/** 关闭上一个结果对话框后展示新的；注册到面板 Disposer 树，面板关闭时一并回收。 */
internal fun SqlPreviewPanel.showExplainDialog(dialog: ExplainPlanDialog) {
    explainResultDialog?.close(0)
    explainResultDialog = dialog
    val sink = explainDialogSinkForTest
    if (sink != null) {
        sink(dialog)
        return
    }
    // DialogWrapper 不实现 Disposable（243）；用 Disposable 包装关闭动作挂到面板树。
    Disposer.register(this, com.intellij.openapi.Disposable { dialog.close(0) })
    dialog.show()
}

internal fun SqlPreviewPanel.showExplainDialog(view: JComponent) {
    val proj = project ?: return
    showExplainDialog(ExplainPlanDialog(proj, view))
}

/** 直送结果（TEXT/XML/回退形态）：只读表格展示，不进入共享控制台。 */
internal fun SqlPreviewPanel.showExplainRawResultDialog(title: String, raw: McpExplainFormatter.RawResult) {
    val model = DefaultTableModel(arrayOf<Any>(*raw.headers.toTypedArray()), 0)
    raw.rows.forEach { model.addRow(arrayOf<Any>(*it.toTypedArray())) }
    val table = JBTable(model).apply {
        isEnabled = false
        autoResizeMode = JTable.AUTO_RESIZE_OFF
    }
    val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(
            JBLabel(
                "<html><b>" + escapeHtml(title) + "</b>" +
                    if (raw.truncated) " · " + MpSqlVisualizerBundle.message("panel.explain.structured.truncated") else "" +
                    "</html>",
            ).apply { border = JBUI.Borders.empty(4, 6) },
            BorderLayout.NORTH,
        )
        add(JBScrollPane(table), BorderLayout.CENTER)
    }
    showExplainDialog(panel)
}

/** 旁路查询失败：给出原因，并保留显式「在控制台打开」入口（用户明确选择才换编辑器）。 */
internal fun SqlPreviewPanel.showExplainErrorDialog(message: String, onOpenConsole: () -> Unit) {
    val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(
            JBLabel("<html>" + escapeHtml(message) + "</html>").apply {
                border = JBUI.Borders.empty(8)
                foreground = com.intellij.ui.JBColor.RED
            },
            BorderLayout.CENTER,
        )
        add(
            JButton(MpSqlVisualizerBundle.message("panel.explain.openConsole")).apply {
                addActionListener { onOpenConsole() }
            },
            BorderLayout.SOUTH,
        )
    }
    showExplainDialog(panel)
}

private fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
