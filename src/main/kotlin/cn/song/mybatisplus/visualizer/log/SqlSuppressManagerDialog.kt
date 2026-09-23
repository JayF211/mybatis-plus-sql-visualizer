package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.EditorWhitespaceSettings
import cn.song.mybatisplus.visualizer.ui.highlight.SqlListSyntaxHighlighter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel

/**
 * SQL 屏蔽规则管理对话框（T-SqlSuppress O-69，F5）：左规则列表 + 右只读高亮编辑器。
 *
 * 入口（D1 定案双入口开同一对话框）：日志面板工具栏「管理屏蔽规则…」（主）+ 列表右键 /
 * 屏蔽 balloon「管理屏蔽规则…」动作（辅）。非模态（与 SqlPreviewDialog 同形态：
 * `DialogWrapper(project, false, IdeModalityType.MODELESS)`）。
 *
 * 操作（关闭即生效——规则变更实时经 [SqlSuppressRuleManager] 通知链落地，**不设**「确定/应用」
 * 缓存层，避免与持久化服务双源）：新增（右侧编辑器进入可编辑态，粘贴 SQL 后确认，按规范化文本
 * 成规则）、删除单条、清空（确认后全清）。
 *
 * 右侧编辑器照抄 `SqlClickPreview.buildContent` 的写法：`EditorFactory.createDocument/createEditor`
 * + `(editor as? EditorEx)` 挂 `LexerEditorHighlighter(SqlListSyntaxHighlighter(), globalScheme)`
 * （与日志列表共用同一 token→配色映射，不依赖 Database 插件，缺席环境仍高亮）；
 * 行号按需求截图开（`isLineNumbersShown = true`，对齐 ParamImportDialog 外观）。
 * 切换选中用 `runWriteAction { document.setText(...) }` 换文，**不重建 editor**。
 *
 * editor 释放路径（§四 前提 1 已按代码证实，非凭转述）：[cn.song.mybatisplus.visualizer.ui.ParamImportDialog]
 * 的成熟路径 = 覆写 `dispose()`，在 `super.dispose()` 前 `EditorFactory.releaseEditor` 并置 null——
 * 平台 close() 处置 DialogWrapper 自身 `myDisposable` 时回调本方法，editor 随对话框生命周期确定性
 * 释放（SqlClickPreview 的 onClosed 弹窗路径不适用于 DialogWrapper）。注意 N3 教训：本类内部如需
 * 挂平台监听，父 disposable 必须用 `disposable` 而非 `this`（本类目前无平台监听，仅防回归记录）。
 */
class SqlSuppressManagerDialog(
    private val project: Project,
) : DialogWrapper(project, false, DialogWrapper.IdeModalityType.MODELESS) {

    private val ruleManager = SqlSuppressRuleManager.getInstance()

    private val listModel = CollectionListModel<SqlSuppressRule>()
    private val ruleList = JBList<SqlSuppressRule>(listModel)

    /** 只读高亮编辑器（F5）；null 仅在 dispose 之后。 */
    private var editor: Editor? = null

    /** 新增模式标记：新增期间右侧编辑器可编辑，选中切换不改写文档。 */
    private var adding = false

    private val statusLabel = JBLabel().apply { foreground = UIUtil.getInactiveTextColor() }

    private val addButton = JButton(MpSqlVisualizerBundle.message("log.suppress.dialog.add")).apply {
        isFocusable = false
        addActionListener { onAddClicked() }
    }

    private val cancelAddButton = JButton(MpSqlVisualizerBundle.message("log.suppress.dialog.cancelAdd")).apply {
        isFocusable = false
        isVisible = false
        addActionListener {
            exitAddMode()
            refreshRuleList(select = null) // 放弃新增：恢复选中项内容
        }
    }

    init {
        title = MpSqlVisualizerBundle.message("log.suppress.dialog.title")
        setCancelButtonText(MpSqlVisualizerBundle.message("log.suppress.dialog.close"))
        init()
        setSize(DIALOG_WIDTH, DIALOG_HEIGHT)
        refreshRuleList(select = null)
    }

    override fun createCenterPanel(): JComponent {
        ruleList.cellRenderer = object : ColoredListCellRenderer<SqlSuppressRule>() {
            override fun customizeCellRenderer(
                list: javax.swing.JList<out SqlSuppressRule>,
                value: SqlSuppressRule,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                append(value.patternSummary(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                val hits = MpSqlVisualizerBundle.message(
                    "log.suppress.dialog.hitCount",
                    ruleManager.hitCount(value.id).toString(),
                )
                append("  " + createdAtText(value) + " · " + hits, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        ruleList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        ruleList.addListSelectionListener { event ->
            if (event.valueIsAdjusting || adding) return@addListSelectionListener
            updateEditorFromSelection()
        }

        // 左：规则列表 + 操作按钮行；右：只读高亮编辑器 + 状态行（新增提示/重名/上限）+ 常驻说明
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            add(addButton)
            add(cancelAddButton)
            add(JButton(MpSqlVisualizerBundle.message("log.suppress.dialog.delete")).apply {
                isFocusable = false
                addActionListener { deleteSelectedRule() }
            })
            add(JButton(MpSqlVisualizerBundle.message("log.suppress.dialog.clear")).apply {
                isFocusable = false
                addActionListener { clearAllRules() }
            })
        }
        val leftPanel = JPanel(BorderLayout()).apply {
            add(
                JBScrollPane(ruleList).apply { preferredSize = Dimension(LIST_WIDTH, LIST_HEIGHT) },
                BorderLayout.CENTER,
            )
            add(buttonRow, BorderLayout.SOUTH)
        }

        // 常驻说明（恢复语义落差，计划 §六）：JTextArea 自动折行（JBLabel 不折行会撑宽对话框）
        val noteArea = JTextArea(MpSqlVisualizerBundle.message("log.suppress.dialog.restoreNote")).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = null
            foreground = UIUtil.getInactiveTextColor()
            background = null
        }

        val rightPanel = JPanel(BorderLayout()).apply {
            add(createEditorPane(), BorderLayout.CENTER)
            add(noteArea, BorderLayout.SOUTH)
        }

        // 左右分栏：JSplitPane（com.intellij.ui.Splitter 不在本仓 243 编译 classpath——
        // 2026-09-23 compileKotlin 实证 Unresolved reference；DataSourceSelector 既有 JSplitPane 先例）
        return javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT).apply {
            leftComponent = leftPanel
            rightComponent = JPanel(BorderLayout()).apply {
                add(rightPanel, BorderLayout.CENTER)
                add(statusLabel, BorderLayout.SOUTH)
            }
            resizeWeight = SPLIT_PROPORTION.toDouble()
            dividerLocation = (DIALOG_WIDTH * SPLIT_PROPORTION).toInt()
            isOneTouchExpandable = false
            border = null
        }
    }

    /**
     * 创建只读高亮编辑器（高亮写法照抄 `SqlClickPreview.buildContent`；行号开 = 需求截图外观）。
     * 初始为 viewer 态；新增模式下临时切可编辑。
     */
    private fun createEditorPane(): JComponent {
        val document = EditorFactory.getInstance().createDocument("")
        val created = EditorFactory.getInstance().createEditor(document, project)
        (created as? EditorEx)?.apply {
            // createEditor 静态类型为 Editor，setHighlighter 在 EditorEx；非 EditorEx 实现时跳过（降级纯文本）
            setHighlighter(
                LexerEditorHighlighter(
                    SqlListSyntaxHighlighter(),
                    EditorColorsManager.getInstance().globalScheme,
                ),
            )
            settings.apply {
                isLineNumbersShown = true
                isFoldingOutlineShown = false
                isIndentGuidesShown = false
                isAdditionalPageAtBottom = false
                setCaretRowShown(false)
                setUseSoftWraps(true) // 长 SQL 软换行免横向滚动
            }
            EditorWhitespaceSettings.applyTo(this)
        }
        // setViewer 在 EditorEx（243 javap 实证：Editor 只有 isViewer() 读、EditorEx 才有 setViewer(boolean) 写——
        // Kotlin `created.isViewer = true` 对 Editor 静态类型报 val 不可赋值）；非 EditorEx 实现则只读不可切（防御）
        (created as? EditorEx)?.setViewer(true)
        editor = created
        return created.component
    }

    /** 选中规则 → 只读编辑器换文（runWriteAction 直写 document，不重建 editor）。 */
    private fun updateEditorFromSelection() {
        val ed = editor ?: return
        val text = ruleList.selectedValue?.sampleSql ?: ""
        ApplicationManager.getApplication().runWriteAction {
            if (ed.document.text != text) ed.document.setText(text)
        }
    }

    // ---------- 规则操作 ----------

    /** 列表模型按 manager 当前规则重建；[select] 非 null 时按 id 选中（增删后保持视点）。 */
    private fun refreshRuleList(select: SqlSuppressRule?) {
        val rules = ruleManager.rules
        listModel.replaceAll(rules)
        val target = select?.let { next -> rules.firstOrNull { it.id == next.id } } ?: rules.firstOrNull()
        if (target != null) {
            ruleList.setSelectedValue(target, false)
        } else {
            ruleList.clearSelection()
        }
        updateEditorFromSelection()
        statusLabel.text = if (rules.isEmpty()) MpSqlVisualizerBundle.message("log.suppress.dialog.empty") else ""
    }

    /** 新增：未在新增态 → 进入（编辑器清空可编辑）；已在新增态 → 确认（读编辑器文本成规则）。 */
    private fun onAddClicked() {
        if (!adding) {
            adding = true
            ruleList.clearSelection()
            val ed = editor ?: return
            (ed as? EditorEx)?.setViewer(false)
            ApplicationManager.getApplication().runWriteAction {
                if (ed.document.text.isNotEmpty()) ed.document.setText("")
            }
            addButton.text = MpSqlVisualizerBundle.message("log.suppress.dialog.addConfirm")
            cancelAddButton.isVisible = true
            statusLabel.text = MpSqlVisualizerBundle.message("log.suppress.dialog.addHint")
            return
        }
        val ed = editor ?: return
        val text = ed.document.text
        if (text.isBlank()) {
            statusLabel.text = MpSqlVisualizerBundle.message("log.suppress.dialog.blank")
            return
        }
        when (ruleManager.addRule(text)) {
            SqlSuppressRuleManager.AddResult.ADDED -> {
                val normalized = normalizeSqlWhitespace(text)
                val created = ruleManager.rules.firstOrNull { it.pattern == normalized }
                exitAddMode()
                refreshRuleList(select = created)
            }
            SqlSuppressRuleManager.AddResult.DUPLICATE ->
                statusLabel.text = MpSqlVisualizerBundle.message("log.suppress.dialog.duplicate")
            SqlSuppressRuleManager.AddResult.LIMIT_REACHED ->
                statusLabel.text = MpSqlVisualizerBundle.message(
                    "log.suppress.limit",
                    SqlSuppressRuleManager.MAX_RULES.toString(),
                )
        }
    }

    /** 退出新增态：编辑器回 viewer，按钮复位（列表/编辑器内容由调用方刷新）。 */
    private fun exitAddMode() {
        adding = false
        (editor as? EditorEx)?.setViewer(true)
        addButton.text = MpSqlVisualizerBundle.message("log.suppress.dialog.add")
        cancelAddButton.isVisible = false
    }

    /** 删除单条选中规则；删除后仍在缓冲内的条目重新显示（闸门已丢弃的不可恢复——底部常驻说明）。 */
    private fun deleteSelectedRule() {
        val selected = ruleList.selectedValue ?: return
        ruleManager.removeRule(selected.id)
        refreshRuleList(select = null)
    }

    /** 清空全部规则（确认后执行）。 */
    private fun clearAllRules() {
        if (ruleManager.rules.isEmpty()) return
        // 弹窗克制红线准入依据（BannedApiSourceScanTest 白名单在册）：清空是不可恢复的破坏性操作，
        // 属「用户必须知情确认」场景——本对话框本体非模态，仅此处用一次模态确认。
        val confirm = Messages.showYesNoDialog(
            project,
            MpSqlVisualizerBundle.message("log.suppress.dialog.clearConfirm"),
            MpSqlVisualizerBundle.message("log.suppress.dialog.title"),
            Messages.getQuestionIcon(),
        )
        if (confirm != Messages.YES) return
        ruleManager.clearRules()
        refreshRuleList(select = null)
    }

    /** createdAt 展示片段（yyyy-MM-dd HH:mm；EDT 渲染专用，SimpleDateFormat 线程约束不适用）。 */
    private fun createdAtText(rule: SqlSuppressRule): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(rule.createdAt))

    /** 只挂「关闭」（F5：关闭即生效，无确定/应用缓存层）。 */
    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    /**
     * editor 释放（ParamImportDialog 成熟路径，§四 前提 1 结论）：平台 close() → dispose() →
     * releaseEditor → super.dispose()。置 null 防二次释放。
     */
    override fun dispose() {
        editor?.let { EditorFactory.getInstance().releaseEditor(it) }
        editor = null
        super.dispose()
    }

    /** 测试访问器：当前规则行数（headless 断言列表同步）。 */
    internal fun ruleCountForTest(): Int = listModel.size

    /** 测试访问器：触发新增按钮（headless 驱动新增态切换）。 */
    internal fun triggerAddForTest() = onAddClicked()

    /** 测试访问器：当前编辑器文本。 */
    internal fun editorTextForTest(): String = editor?.document?.text ?: ""

    /** 测试访问器：设置编辑器文本（模拟用户粘贴）。 */
    internal fun setEditorTextForTest(text: String) {
        editor?.document?.let { doc ->
            WriteCommandAction.runWriteCommandAction(project) { doc.setText(text) }
        }
    }

    companion object {
        const val DIALOG_WIDTH = 760
        const val DIALOG_HEIGHT = 480
        const val LIST_WIDTH = 300
        const val LIST_HEIGHT = 400
        const val SPLIT_PROPORTION = 0.38f
    }
}
