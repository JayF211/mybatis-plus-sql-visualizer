package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.EditorWhitespaceSettings
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.border.EmptyBorder

/**
 * 「最近执行记录」浏览对话框（T-RecentExecDialog O-118）：gutter 右键菜单二级 ListPopup 的换代形态。
 *
 * 交互对齐 [SqlSuppressManagerDialog]（同包成熟范式）：非模态 + 可拖拽 + DimensionService 尺寸记忆 +
 * `com.intellij.openapi.ui.Splitter` 左右分栏 + 右侧只读高亮编辑器（dispose 释放走 ParamImportDialog
 * 成熟路径）；差异点：只读浏览、无增删清，主按钮「打开预览」开启后**不关闭**本对话框（连续对比多条）。
 *
 * 左侧列表直接复用日志面板条目渲染器 [LogEntryListCellRenderer]（同包 internal 直用）——
 * 两行形态、类型色条、耗时/来源角标与「SQL 日志」页完全一致（D86/D87 用户拍板：恒弹对话框
 * 含空态、编辑器内容=restoredSql）。
 *
 * 右侧编辑器三件（D88=A 用户拍板）：**默认格式化显示**（[SqlFormatter.format]，与预览
 * 「格式化还原 SQL」同源口径）+「查看原文 / 格式化」切换（`showFormatted`，不持久化）+
 * 「复制」（[UiTextClipboard]，复制当前显示文本）。
 *
 * 线程约定：构造与 show() 必须在 EDT（gutter 侧既有 invokeLater 保证）；条目匹配留在调用方
 * 池化线程（[EntrySourceMatcher.match] 纯函数），本对话框零 PSI、零 IO。
 *
 * 数据链不变（快照校验→后台匹配→本对话框），展示上限沿用 [EntrySourceMatcher.MAX_RESULTS]。
 *
 * @param entries     反查结果（可空列表——D86=A 空态也弹对话框，状态行显示空态文案）
 * @param anchorLabel 锚点展示片段（「文件名:行号」）；null = 标题不带锚点
 * @param onOpenPreview 打开预览动作（默认 [LogEntryPreviewOpener.open] 统一识别通道；
 *   注入点仅供 headless 测试录制，生产调用方不传）
 */
class RecentExecutionsDialog(
    private val project: Project,
    entries: List<LogSqlEntry>,
    anchorLabel: String? = null,
    private val onOpenPreview: (LogSqlEntry) -> Unit = { LogEntryPreviewOpener.open(project, it) },
) : DialogWrapper(project, false, DialogWrapper.IdeModalityType.MODELESS) {

    private val listModel = CollectionListModel<LogSqlEntry>()
    private val entryList = JBList<LogSqlEntry>(listModel)

    /** 日志面板条目渲染器（复用点；字段持有供测试钉断言——JBList getter 平台侧会包 ExpandedItemListCellRendererWrapper 壳）。 */
    private val entryRenderer = LogEntryListCellRenderer()

    /** 只读高亮编辑器；null 仅在 dispose 之后（SqlSuppressManagerDialog 同生命周期约定）。 */
    private var editor: Editor? = null

    private val statusLabel = JBLabel().apply { foreground = UIUtil.getInactiveTextColor() }

    /** D88=A：格式化显示开关（默认开；**不持久化**——每次打开对话框都是格式化态）。 */
    private var showFormatted = true

    /**
     * 「查看原文 / 格式化」切换按钮：标签语义=点击后到达的形态。
     * 形制照抄 SqlSuppressManagerDialog.structureButton——纯 JButton + 标签随状态改写
     * （不走 DialogWrapperAction：动作名刷新时机不可控，JButton.setText 才是可控路径）。
     */
    private val formatToggle = JButton().apply {
        isFocusable = false
        addActionListener { toggleFormatDisplay() }
    }

    /** 「复制」按钮：复制当前编辑器显示文本（格式化态复制格式化版）；走 [UiTextClipboard] 统一收口通道。 */
    private val copyButton = JButton(MpSqlVisualizerBundle.message("log.recent.dialog.copy")).apply {
        isFocusable = false
        addActionListener { copyCurrentSql() }
    }

    init {
        title = if (anchorLabel.isNullOrBlank()) {
            MpSqlVisualizerBundle.message("log.recent.dialog.title")
        } else {
            MpSqlVisualizerBundle.message("log.recent.dialog.title.anchor", anchorLabel)
        }
        setOKButtonText(MpSqlVisualizerBundle.message("log.recent.dialog.openPreview"))
        setCancelButtonText(MpSqlVisualizerBundle.message("log.recent.dialog.close"))
        // O-80 惯例（SqlSuppressManagerDialog/RollbackSqlDialog 同款）：可拖拽 + 尺寸/位置记忆
        isResizable = true
        init()
        setSize(DIALOG_WIDTH, DIALOG_HEIGHT)
        refreshEntries(entries)
    }

    /** 尺寸/位置记忆键（O-80 惯例，零自持久化）。 */
    override fun getDimensionServiceKey(): String = DIMENSION_SERVICE_KEY

    override fun createCenterPanel(): JComponent {
        entryList.cellRenderer = entryRenderer
        entryList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        entryList.addListSelectionListener { event ->
            if (event.valueIsAdjusting) return@addListSelectionListener
            updateEditorFromSelection()
            syncSelectionScopedControls()
        }
        // 双击 = 打开预览（与主按钮同路径）
        entryList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) doOKAction()
            }
        })

        val leftPanel = JBScrollPane(entryList)

        val rightPanel = JPanel(BorderLayout()).apply {
            // 编辑器按钮行（D88=A）：格式化切换 + 复制——作用于右侧显示文本，放编辑器正上方
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                isOpaque = false
                add(formatToggle)
                add(copyButton)
            }, BorderLayout.NORTH)
            add(createEditorPane(), BorderLayout.CENTER)
        }

        val centerPanel = Splitter(false, SPLIT_PROPORTION).apply {
            firstComponent = leftPanel
            secondComponent = rightPanel
        }
        // DialogWrapper 无 setMinimumSize，设在中心面板（SqlSuppressManagerDialog 同款兜底）
        centerPanel.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)

        val root = JPanel(BorderLayout())
        root.add(centerPanel, BorderLayout.CENTER)
        statusLabel.border = EmptyBorder(4, 8, 4, 8)
        root.add(statusLabel, BorderLayout.SOUTH)
        return root
    }

    /**
     * 创建只读高亮编辑器（写法照抄 SqlSuppressManagerDialog.createEditorPane：
     * LexerEditorHighlighter + 日志列表同款 token→配色映射，不依赖 Database 插件；行号开、软换行）。
     */
    private fun createEditorPane(): JComponent {
        val document = EditorFactory.getInstance().createDocument("")
        val created = EditorFactory.getInstance().createEditor(document, project)
        (created as? EditorEx)?.apply {
            setHighlighter(
                LexerEditorHighlighter(
                    cn.song.mybatisplus.visualizer.ui.highlight.SqlListSyntaxHighlighter(),
                    EditorColorsManager.getInstance().globalScheme,
                ),
            )
            settings.apply {
                isLineNumbersShown = true
                isFoldingOutlineShown = false
                isIndentGuidesShown = false
                isAdditionalPageAtBottom = false
                setCaretRowShown(false)
                setUseSoftWraps(true)
            }
            EditorWhitespaceSettings.applyTo(this)
            setViewer(true)
        }
        editor = created
        return created.component
    }

    /**
     * 选中条目 → 只读编辑器换文（D88=A 默认格式化显示；「查看原文」态 = restoredSql 原文；
     * runWriteAction 直写不重建 editor）。
     */
    private fun updateEditorFromSelection() {
        val ed = editor ?: return
        val text = entryList.selectedValue?.let { displayTextFor(it) } ?: ""
        ApplicationManager.getApplication().runWriteAction {
            if (ed.document.text != text) ed.document.setText(text)
        }
    }

    /**
     * 条目 → 编辑器显示文本：默认格式化（与预览「格式化还原 SQL」同源 [SqlFormatter.format]，
     * 含语句结束符——口径对齐预览侧 `formatIfNeeded(withTerminator(restoredSql))`）；
     * 格式化异常按容错优先降级原文，不抛、不空屏。
     */
    private fun displayTextFor(entry: LogSqlEntry): String {
        if (!showFormatted) return entry.restoredSql
        return runCatching { SqlFormatter.format(withTerminator(entry.restoredSql)) }
            .getOrDefault(entry.restoredSql)
    }

    /** 切换「格式化 / 原文」显示：标签语义=点击后到达的形态（当前格式化 → 「查看原文」，反之 → 「格式化」）。 */
    private fun toggleFormatDisplay() {
        showFormatted = !showFormatted
        updateEditorFromSelection()
        syncSelectionScopedControls()
    }

    /**
     * 复制当前编辑器显示文本（格式化态即复制格式化版）。
     * 通道=[UiTextClipboard]（T-UiNative 收口：进 IDE 复制历史）；反馈复用通用键
     * `dialog.copy.done`（SqlSuppressManagerDialog 状态行承载操作反馈同款）。
     */
    private fun copyCurrentSql() {
        val text = editor?.document?.text ?: return
        if (text.isEmpty()) return
        UiTextClipboard.copy(text)
        statusLabel.text = MpSqlVisualizerBundle.message("dialog.copy.done")
    }

    /** 列表装载 + 首条默认选中 + 状态行（空态文案/共 N 条）+ 选中态联动控件同步。 */
    private fun refreshEntries(entries: List<LogSqlEntry>) {
        listModel.replaceAll(entries)
        val first = entries.firstOrNull()
        if (first != null) entryList.setSelectedValue(first, false)
        statusLabel.text = if (entries.isEmpty()) {
            // D86=A 空态复用 gutter 菜单既有「未找到」文案，不造重复键
            MpSqlVisualizerBundle.message("gutter.popup.recent.empty")
        } else {
            MpSqlVisualizerBundle.message("log.recent.dialog.count", entries.size.toString())
        }
        syncSelectionScopedControls()
    }

    /**
     * 选中态联动：主按钮「打开预览」/切换「查看原文·格式化」/「复制」的可用态与标签。
     * 空态（无选中）三者全禁用——双击/回车同样无动作。
     */
    private fun syncSelectionScopedControls() {
        val hasSelection = entryList.selectedValue != null
        okAction.isEnabled = hasSelection
        formatToggle.text = MpSqlVisualizerBundle.message(
            if (showFormatted) "log.recent.dialog.showOriginal" else "log.recent.dialog.format",
        )
        formatToggle.isEnabled = hasSelection
        copyButton.isEnabled = hasSelection
    }

    /**
     * 主按钮/回车/双击：打开选中条目预览后**不关闭**本对话框（浏览对比场景）。
     * 无选中直接返回（okAction 已禁用，防御直达调用）。
     */
    override fun doOKAction() {
        val selected = entryList.selectedValue ?: return
        onOpenPreview(selected)
    }

    /** 程序化关闭（SqlPreviewDialog/RollbackSqlDialog 同款惯例；测试 tearDown 用）——DialogWrapper.dispose 平台侧 protected，同类内提升为 public 包装，语义与用户点关闭一致。 */
    fun doDispose() {
        dispose()
    }

    /** editor 释放（ParamImportDialog 成熟路径）：置 null 防二次释放。 */
    override fun dispose() {
        editor?.let { EditorFactory.getInstance().releaseEditor(it) }
        editor = null
        super.dispose()
    }

    // ---------- 测试访问器（headless 断言，RollbackSqlDialogTest 同款构造不 show） ----------

    /** 测试访问器：当前列表条目数。 */
    internal fun entryCountForTest(): Int = listModel.size

    /** 测试访问器：列表渲染器（复用钉断言用；返回对话框自持字段，非 JBList 包装壳）。 */
    internal fun cellRendererForTest(): Any? = entryRenderer

    /** 测试访问器：当前编辑器文本。 */
    internal fun editorTextForTest(): String = editor?.document?.text ?: ""

    /** 测试访问器：状态行文本。 */
    internal fun statusTextForTest(): String = statusLabel.text

    /** 测试访问器：按索引选中（驱动选中切换链）。 */
    internal fun selectIndexForTest(index: Int) {
        entryList.selectedIndex = index
    }

    /** 测试访问器：当前选中条目。 */
    internal fun selectedEntryForTest(): LogSqlEntry? = entryList.selectedValue

    /** 测试访问器：主按钮可用态。 */
    internal fun openPreviewEnabledForTest(): Boolean = okAction.isEnabled

    /** 测试访问器：触发主按钮动作（含「不关闭」契约断言的前半段）。 */
    internal fun triggerOpenPreviewForTest() = doOKAction()

    /** 测试访问器：切换「格式化 / 原文」。 */
    internal fun triggerToggleFormatForTest() = toggleFormatDisplay()

    /** 测试访问器：切换按钮当前标签（形态语义断言）。 */
    internal fun formatToggleLabelForTest(): String = formatToggle.text

    /** 测试访问器：切换按钮可用态。 */
    internal fun formatToggleEnabledForTest(): Boolean = formatToggle.isEnabled

    /** 测试访问器：触发复制。 */
    internal fun triggerCopyForTest() = copyCurrentSql()

    /** 测试访问器：复制按钮可用态。 */
    internal fun copyEnabledForTest(): Boolean = copyButton.isEnabled

    companion object {
        const val DIALOG_WIDTH = 760
        const val DIALOG_HEIGHT = 480

        /** DimensionService 键（尺寸/位置记忆）。 */
        const val DIMENSION_SERVICE_KEY = "mpsv.recent.executions.dialog"

        /** 拖拽下限（中心面板 minimumSize；量级对齐 SqlSuppressManagerDialog）。 */
        const val MIN_WIDTH = 560
        const val MIN_HEIGHT = 360

        /** 左右分栏比（列表侧信息密度高于规则名列表，较屏蔽规则对话框 0.38 放宽）。 */
        const val SPLIT_PROPORTION = 0.45f
    }
}
