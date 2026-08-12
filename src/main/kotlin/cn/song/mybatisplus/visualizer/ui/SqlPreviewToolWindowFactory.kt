package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JToggleButton

/**
 * 工具窗口工厂（M3-03，F-07 工具窗口展示方式）。
 *
 * 注册 ID：`MP SQL Visualizer`（见 plugin.xml toolWindow 扩展，与 [SqlToolWindow.ID] 一致）。
 * 内容：顶部操作区（跟随编辑器开关 / 从编辑器获取 / 清空 + 粘贴输入 + 转换）+ SQL 预览面板 + 状态栏。
 *
 * 交互（M9 重设计）：
 *  - 跟随编辑器：勾选后光标移动实时转换推送（[SqlPreviewEditorTracker]）
 *  - 从编辑器获取：手动捕获当前编辑器光标处
 *  - 粘贴 + 转换：把粘贴的 Wrapper 代码建成 Java PSI 片段后走转换链路
 *  - 右键菜单/快捷键转换时按显示方式（F-16）推送结果到本面板（[SqlToolWindow.showResult]）
 *
 * 说明：操作区用普通 Swing 组件（JBCheckBox/JButton）而非 ActionToolbar——
 * 工具栏对无图标的 AnAction 会渲染成空白圆形占位。
 */
class SqlPreviewToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SqlPreviewToolWindowPanel(project)
        val content = com.intellij.ui.content.ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

/**
 * 工具窗口内容面板：操作区（跟随/获取/清空 + 粘贴转换）+ SQL 预览 + 状态栏。
 */
class SqlPreviewToolWindowPanel(private val project: Project) : JBPanel<SqlPreviewToolWindowPanel>(BorderLayout()) {

    private val previewPanel = SqlPreviewPanel(project)
    private val statusLabel = JBLabel(MpSqlVisualizerBundle.message("toolwindow.status.hint"))

    private val inputArea = JBTextArea(4, 40).apply {
        isEditable = true
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        emptyText.text = MpSqlVisualizerBundle.message("toolwindow.input.hint")
    }

    init {
        // 顶部操作行：跟随编辑器 / 从编辑器获取 / 清空（统一方形按钮 + 图标）
        val followToggle = toolToggleButton(
            text = MpSqlVisualizerBundle.message("toolwindow.follow"),
            icon = AllIcons.General.AutoscrollToSource,
            selected = SqlToolWindow.isFollowEditor,
        ) { SqlToolWindow.isFollowEditor = it }
        val captureButton = toolButton(
            MpSqlVisualizerBundle.message("toolwindow.capture"),
            AllIcons.Actions.Execute,
        ) { captureFromEditor() }
        val clearButton = toolButton(
            MpSqlVisualizerBundle.message("toolwindow.clear"),
            AllIcons.Actions.GC,
        ) { clear() }
        val topBar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(followToggle)
            add(captureButton)
            add(clearButton)
        }

        // 粘贴输入 + 转换
        val convertButton = toolButton(MpSqlVisualizerBundle.message("toolwindow.convert")) { convertPastedText() }
        val inputRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(JBScrollPane(inputArea).apply { preferredSize = Dimension(300, 90) }, BorderLayout.CENTER)
            add(convertButton, BorderLayout.EAST)
        }
        val top = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(topBar, BorderLayout.NORTH)
            add(inputRow, BorderLayout.CENTER)
        }
        add(top, BorderLayout.NORTH)

        // 中部 SQL 预览
        add(previewPanel, BorderLayout.CENTER)

        // 底部状态栏
        add(statusLabel, BorderLayout.SOUTH)
    }

    /**
     * 统一风格的按钮（默认 IntelliJ LaF：文字 + 图标同显，同高）。
     * 注意：勿用 "JButton.buttonType"="square"（纯图标工具栏样式会隐藏文字、按图标收缩）。
     */
    private fun toolButton(text: String, icon: javax.swing.Icon? = null, action: () -> Unit): JButton =
        JButton(text, icon).apply {
            addActionListener { action() }
        }

    /** 统一风格的切换按钮（按下态 = 开启）。 */
    private fun toolToggleButton(
        text: String,
        icon: javax.swing.Icon? = null,
        selected: Boolean,
        onToggle: (Boolean) -> Unit,
    ): JToggleButton = JToggleButton(text, icon, selected).apply {
        addActionListener { onToggle(isSelected) }
    }

    /** 展示预览结果（由 Action / 转换 / 编辑器跟随调用）。 */
    fun showResult(result: PreviewResult) {
        previewPanel.showResult(result)
        statusLabel.text = MpSqlVisualizerBundle.message(
            "toolwindow.status.generated",
            java.time.LocalTime.now().withNano(0),
        )
    }

    /** 清空展示区与粘贴输入区。 */
    fun clear() {
        previewPanel.clear()
        inputArea.text = ""
        statusLabel.text = MpSqlVisualizerBundle.message("toolwindow.status.cleared")
    }

    /**
     * 从当前编辑器光标处捕获并转换（后台线程执行，EDT 渲染）。
     * 无活动编辑器/无 Wrapper 时状态栏提示。
     */
    private fun captureFromEditor() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: run {
            statusLabel.text = MpSqlVisualizerBundle.message("toolwindow.status.notFound")
            return
        }
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
        val element = psiFile?.findElementAt(editor.caretModel.offset) ?: run {
            statusLabel.text = MpSqlVisualizerBundle.message("toolwindow.status.notFound")
            return
        }
        SqlPreviewService.previewAsync(project, {
            project.getService(SqlPreviewService::class.java).preview(element)
        }) { result ->
            if (result != null) showResult(result)
            else statusLabel.text = MpSqlVisualizerBundle.message("toolwindow.status.notFound")
        }
    }

    /**
     * 转换粘贴的代码：后台线程把粘贴文本建成 Java PSI 片段并走统一转换链路。
     * 片段无需完整类定义——实体类不可解析时按类名命名策略兜底表名。
     */
    private fun convertPastedText() {
        val text = inputArea.text.trim()
        if (text.isEmpty()) {
            statusLabel.text = MpSqlVisualizerBundle.message("toolwindow.status.emptyInput")
            return
        }
        SqlPreviewService.previewAsync(project, {
            val file = PsiFileFactory.getInstance(project)
                .createFileFromText("Paste.java", JavaLanguage.INSTANCE, text)
            project.getService(SqlPreviewService::class.java).preview(file)
        }) { result ->
            if (result != null) showResult(result)
            else statusLabel.text = MpSqlVisualizerBundle.message("toolwindow.status.notFound")
        }
    }
}
