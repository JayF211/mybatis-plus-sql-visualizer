package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.ui.highlight.JsonSyntaxHighlighter
import cn.song.mybatisplus.visualizer.util.EditorCopyShortcuts
import cn.song.mybatisplus.visualizer.util.IconScaler
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.JPanel

/**
 * P-05.1：参数导入手动输入弹窗。
 *
 * 布局：顶部工具栏（格式化 / 删除）+ 中部 JSON 语法高亮编辑器 + 空态占位提示。
 * 默认回显当前 SQL 参数（JSON 格式），用户可在此基础上修改后导入。
 */
class ParamImportDialog(
    private val project: Project,
    private val preFill: String? = null,
    private val onConfirm: (String) -> Unit,
) : DialogWrapper(project, true), Disposable {

    /** JSON 语法高亮编辑器。 */
    private var editor: Editor? = null

    /** 空态占位提示标签（编辑器为空时显示）。 */
    private val placeholderLabel = JBLabel(
        MpSqlVisualizerBundle.message("panel.param.import.dialog.placeholder")
    ).apply {
        foreground = UIUtil.getInactiveTextColor()
        border = JBUI.Borders.empty(6, 8)
    }

    init {
        title = MpSqlVisualizerBundle.message("panel.param.import.result.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val content = JPanel(BorderLayout())
        content.add(createToolbar(), BorderLayout.NORTH)

        // 编辑器 + 空态提示叠加
        val editorComponent = createEditor()
        val scrollPane = JBScrollPane(editorComponent).apply {
            preferredSize = Dimension(DIALOG_WIDTH, DIALOG_HEIGHT)
        }
        val overlayPanel = object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                add(placeholderLabel, BorderLayout.NORTH)
                add(scrollPane, BorderLayout.CENTER)
            }
        }
        content.add(overlayPanel, BorderLayout.CENTER)

        // 监听文档变化，控制占位提示显隐
        // N3 修复（2026-09-21）：父 disposable 必须是 `disposable`（= DialogWrapper.getDisposable()），
        // **不能传 `this`**。平台 close() 处置的是 `myDisposable`（其 dispose() 再回调对话框的
        // dispose()），而 `this` 是 DialogWrapper 作为 Disposable 的**另一个**节点——它从没被登记进
        // Disposer 树，于是以 ROOT_DISPOSABLE 子节点身份留到应用退出，被 ObjectTree 报泄漏：
        // `'ParamImportDialog@…' was registered in Disposer as a child of 'ROOT_DISPOSABLE' but wasn't
        // disposed`（真机复现：开 3 次该对话框 → 退出时报 3 个实例泄漏，实例数与打开次数严格相等）。
        // 抽成 [attachPlaceholderListener] 是为了让「处置后监听不再响应」这条可被单测直接断言。
        editor?.let { attachPlaceholderListener(it.document) }
        updatePlaceholderVisibility()

        // 格式化快捷键 Ctrl+Enter（焦点在编辑器内时触发）
        val formatKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK)
        content.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(formatKeyStroke, "format")
        content.actionMap.put("format", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = formatInput()
        })
        return content
    }

    /** 创建带 JSON 语法高亮的编辑器（不依赖 JSON 插件，Community 可用）。 */
    private fun createEditor(): JComponent {
        val proj = project
        val initialText = preFill ?: ""
        val doc = EditorFactory.getInstance().createDocument(initialText)
        val created = EditorFactory.getInstance().createEditor(doc, proj)
        (created as EditorEx).apply {
            highlighter = LexerEditorHighlighter(
                JsonSyntaxHighlighter(),
                EditorColorsManager.getInstance().globalScheme,
            )
            settings.isLineNumbersShown = true
            settings.isFoldingOutlineShown = false
            settings.isIndentGuidesShown = false
            settings.isAdditionalPageAtBottom = false
            // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）
            EditorWhitespaceSettings.applyTo(this)
        }
        // T-SqlCopyShortcuts O-122：无选中 Ctrl+C 复制全文、有选中只复制片段
        //（层位与理由见 EditorCopyShortcuts 的 KDoc；本处此前无任何键盘复制绑定）
        EditorCopyShortcuts.install(created, UiTextClipboard::copy)
        editor = created
        return created.component
    }

    /** 更新占位提示显隐（编辑器为空时显示，有内容时隐藏）。 */
    private fun updatePlaceholderVisibility() {
        placeholderLabel.isVisible = editor?.document?.text.isNullOrBlank()
    }

    /**
     * N3：把「文档变化 → 刷新占位提示」的监听挂到**对话框自己的 disposable** 上。
     *
     * **父节点必须是 [disposable]**（= `DialogWrapper.getDisposable()`）：平台在 `close()` 时处置的
     * 就是它（`myDisposable.dispose()` 再回调对话框的 `dispose()`）。若传 `this`（对话框作为
     * `Disposable` 的另一个节点），该节点从没进过 Disposer 树 → 以 ROOT_DISPOSABLE 子节点身份留到
     * 应用退出 → ObjectTree 报 `Memory leak detected: 'ParamImportDialog@…' … wasn't disposed`。
     *
     * internal 是为了让单测能用**外部文档**断言「disposable 被处置后，监听不再响应」——直接观察
     * 对话框自身的占位标签做不到：`dispose()` 会把 `editor` 置 null，断言会恒真而失去判别力。
     *
     * @param onChanged 文档变化时的动作；默认即生产行为（刷新占位提示），单测注入计数器以判别
     *   「处置后是否还在响应」（同理，注入点而非断言占位标签，是为了让断言有判别力）。
     */
    internal fun attachPlaceholderListener(
        document: com.intellij.openapi.editor.Document,
        onChanged: () -> Unit = ::updatePlaceholderVisibility,
    ) {
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                onChanged()
            }
        }, disposable)
    }

    /** 顶部工具栏：格式化 + 删除（图标按钮，参考 SqlToJavaPanel 风格）。 */
    private fun createToolbar(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 2)).apply {
        isOpaque = false
        add(iconButton(IconScaler.scale(AllIcons.Actions.ReformatCode, ICON_SCALE),
            MpSqlVisualizerBundle.message("panel.param.import.dialog.format"), ::formatInput))
        add(iconButton(IconScaler.scale(AllIcons.Actions.GC, ICON_SCALE),
            MpSqlVisualizerBundle.message("panel.param.import.dialog.clear"), ::clearInput))
    }

    /** 创建无边框图标按钮（与 SqlToJavaPanel 工具栏风格一致）。 */
    private fun iconButton(icon: Icon, tooltip: String, action: () -> Unit): JButton = JButton(icon).apply {
        toolTipText = tooltip
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        margin = JBUI.insets(2)
        preferredSize = Dimension(24, 24)
        addActionListener { action() }
    }

    /**
     * 格式化输入：JSON 美化（加缩进换行）/ 非 JSON 文本归一（去空行、行级 trim）。
     */
    private fun formatInput() {
        val doc = editor?.document ?: return
        val text = doc.text
        if (text.isBlank()) return
        val trimmed = text.trim()
        val formatted = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            formatJson(trimmed)
        } else {
            normalizeText(trimmed)
        }
        if (formatted != text) {
            WriteCommandAction.runWriteCommandAction(project) { doc.setText(formatted) }
        }
    }

    /**
     * 简单 JSON 美化（自实现，不依赖 Jackson）：单层扁平 object 加缩进换行。
     * 注意：所有值统一加引号（参数值本质都是字符串，不做数值/布尔类型推断）。
     */
    private fun formatJson(text: String): String {
        val entries = ParamImportParser.parseJson(text)
        if (entries.isEmpty()) return text  // 解析失败回退原文
        val sb = StringBuilder("{\n")
        entries.entries.forEachIndexed { index, (key, value) ->
            val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
            sb.append("  \"").append(key).append("\": \"").append(escaped).append("\"")
            if (index < entries.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("}")
        return sb.toString()
    }

    /** 非 JSON 文本归一：去空行、行级 trim。 */
    private fun normalizeText(text: String): String =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    /** 清空输入框。 */
    private fun clearInput() {
        editor?.document?.let { doc ->
            WriteCommandAction.runWriteCommandAction(project) { doc.setText("") }
        }
    }

    /**
     * 确定：**空内容也回调**。
     *
     * P13（2026-09-20 真机走查）：此前末句是 `if (text.isNotBlank()) onConfirm(text)`，其自带注释
     * 「避免空串走解析浪费」是纯性能微优化，代价却是「留空点确定」在界面上**零反馈**——回调被吞掉，
     * 下游 `importParams(emptyMap())` 与 `showEmptyClipboardHint()` 永不执行，与 T-Perf-20
     * 「空剪贴板给行内警示」的产品口径相互矛盾。故去掉该守卫：空串照常回调，由下游把「没识别到参数」
     * 这件事说出来（行内红字 + 「复制错误信息」按钮），而不是让确定按钮点了像没点。
     *
     * 空串代价可忽略：[ParamImportParser.parse] 首行 `if (text.isBlank()) return emptyMap()` 即返回，
     * 不存在「空串走完整解析」的开销，原注释担心的浪费并不成立。
     */
    public override fun doOKAction() {
        val text = editor?.document?.text ?: ""
        super.doOKAction()
        onConfirm(text)
    }

    /** 释放编辑器（避免泄漏）。 */
    override fun dispose() {
        editor?.let { EditorFactory.getInstance().releaseEditor(it) }
        editor = null
        super.dispose()
    }

    /** 测试访问器：当前输入文本。 */
    internal fun textForTest(): String = editor?.document?.text ?: ""

    /** 测试访问器：设置输入文本（模拟用户粘贴）。 */
    internal fun setTextForTest(text: String) {
        editor?.document?.let { doc ->
            if (doc.text != text) {
                WriteCommandAction.runWriteCommandAction(project) { doc.setText(text) }
            }
        }
    }

    /** 测试访问器：触发格式化按钮动作。 */
    internal fun triggerFormatForTest() = formatInput()

    /** 测试访问器：触发清除按钮动作。 */
    internal fun triggerClearForTest() = clearInput()

    companion object {
        const val DIALOG_WIDTH = 480
        const val DIALOG_HEIGHT = 200
        private const val ICON_SCALE = 14f / 16f
    }
}
