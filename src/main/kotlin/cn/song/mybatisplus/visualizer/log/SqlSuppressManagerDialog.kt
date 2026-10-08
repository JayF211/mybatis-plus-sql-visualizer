package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.EditorWhitespaceSettings
import cn.song.mybatisplus.visualizer.ui.highlight.SqlListSyntaxHighlighter
import cn.song.mybatisplus.visualizer.util.EditorCopyShortcuts
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
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
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridLayout
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.border.EmptyBorder

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

    /**
     * 时间格式化（ThreadLocal 复用；照 LogTimestampParser 范式）。
     *
     * **声明位置是契约**（T-DevAcceptFix2 ③，20261006 验收 NPE 修复）：本字段必须声明在 [init] 块
     * **之前**——`init { … refreshRuleList(select = null) }` 在构造期就触发列表渲染，渲染器回调
     * [createdAtText] 读本字段。Kotlin 属性按声明序初始化，声明在后 = 构造期读取时为 null
     * （引用类型默认值，编译期不报错）→ 规则数 ≥1 时每次打开对话框必 NPE。
     */
    private val createdAtFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm")
    }

    private val listModel = CollectionListModel<SqlSuppressRule>()
    private val ruleList = JBList<SqlSuppressRule>(listModel)

    /** 只读高亮编辑器（F5）；null 仅在 dispose 之后。 */
    private var editor: Editor? = null

    /** 新增模式标记：新增期间右侧编辑器可编辑，选中切换不改写文档。 */
    private var adding = false

    private val statusLabel = JBLabel().apply { foreground = UIUtil.getInactiveTextColor() }

    /**
     * T-SqlSuppressUx 二段：结构匹配开关（用户定案手动开启）。作用于**选中规则**，标签随选中态
     * 切换（开启/关闭结构匹配）；批量入口 = [structureAllCheckBox]（全部开/全部关）。
     */
    private val structureButton = JButton(MpSqlVisualizerBundle.message("log.suppress.dialog.structure.toggleOn")).apply {
        isFocusable = false
        isEnabled = false
        addActionListener { toggleStructureForSelected() }
    }

    /** 批量开关：勾选 = 全部规则结构匹配开；取消 = 全关。派生状态由 [syncStructureControls] 回写。 */
    private val structureAllCheckBox = JBCheckBox(MpSqlVisualizerBundle.message("log.suppress.dialog.structure.all")).apply {
        isFocusable = false
        addActionListener { toggleStructureAll(isSelected) }
    }

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
        // T-SqlSuppressDialogResize（O-80，20260924 用户走查）：DialogWrapper 默认 fixed 不可拖拽缩放，
        // 显式开启（照 ErrorReportDialog「拖拽：isResizable=true + getDimensionServiceKey 记忆尺寸」惯例）；
        // 有记忆时 DimensionService 直接给尺寸，setSize 仅作首次打开（无记忆）的缺省。
        isResizable = true
        init()
        setSize(DIALOG_WIDTH, DIALOG_HEIGHT)
        refreshRuleList(select = null)
    }

    /** O-80：尺寸/位置记忆（RollbackSqlDialog/SqlPreviewDialog/ErrorReportDialog 同惯例，零自持久化）。 */
    override fun getDimensionServiceKey(): String = DIMENSION_SERVICE_KEY

    override fun createCenterPanel(): JComponent {
        ruleList.cellRenderer = RuleRowRenderer()
        ruleList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        ruleList.addListSelectionListener { event ->
            if (event.valueIsAdjusting || adding) return@addListSelectionListener
            updateEditorFromSelection()
            syncStructureControls()
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
            add(structureButton)
            add(structureAllCheckBox)
        }
        val leftPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(ruleList), BorderLayout.CENTER)
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

        // 左右分栏：com.intellij.openapi.ui.Splitter（LogToSqlPanel/SqlPreviewPanel/SqlToJavaPanel 同款
        // 主题化分栏）。此前误 import com.intellij.ui.Splitter 编译失败，误判「不在 classpath」退回原生
        // JSplitPane——其分隔条在新 UI 深色主题下呈白条（20260924 用户走查截图），换仓内既有 Splitter 根除。
        val centerPanel = Splitter(false, SPLIT_PROPORTION).apply {
            firstComponent = leftPanel
            secondComponent = JPanel(BorderLayout()).apply {
                add(rightPanel, BorderLayout.CENTER)
                add(statusLabel, BorderLayout.SOUTH)
            }
        }
        // O-80：最小尺寸兜底（SqlPreviewDialog 同款——DialogWrapper 无 setMinimumSize，设在中心面板；
        // 下限=按钮行不折行 + 编辑器/列表可读，D3 默认 A 单行按钮）
        centerPanel.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
        return centerPanel
    }

    /**
     * 规则行渲染器：对齐日志列表（[LogEntryListCellRenderer]）的两行形态——SQL 主体行在上、
     * 元信息行（创建时间 · 命中计数）在下灰字；行底分隔线与内边距同款
     * （matte bottom `JBColor.border()` + EmptyBorder）；选中态背景/前景取平台列表选中色。
     */
    private inner class RuleRowRenderer :
        JPanel(BorderLayout()),
        ListCellRenderer<SqlSuppressRule> {

        private val patternLine = SimpleColoredComponent()
        private val metaLine = SimpleColoredComponent()
        private val linesPanel = JPanel(GridLayout(0, 1)).apply { isOpaque = false }

        init {
            isOpaque = true
            patternLine.isOpaque = false
            metaLine.isOpaque = false
            linesPanel.add(patternLine)
            linesPanel.add(metaLine)
            add(linesPanel, BorderLayout.CENTER)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                EmptyBorder(4, 8, 4, 8),
            )
        }

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out SqlSuppressRule>,
            value: SqlSuppressRule,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ): Component {
            background = if (selected) list.selectionBackground else list.background
            val fg = if (selected) list.selectionForeground else list.foreground
            patternLine.foreground = fg
            patternLine.clear()
            patternLine.append(value.patternSummary(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            metaLine.foreground = if (selected) fg else UIUtil.getInactiveTextColor()
            metaLine.clear()
            metaLine.append(createdAtText(value), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            metaLine.append("  ·  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            metaLine.append(
                MpSqlVisualizerBundle.message(
                    "log.suppress.dialog.hitCount",
                    ruleManager.hitCount(value.id).toString(),
                ),
                SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
            )
            if (value.structureMatch) {
                metaLine.append(
                    "  ·  " + MpSqlVisualizerBundle.message("log.suppress.dialog.structure.badge"),
                    SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES,
                )
            }
            return this
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
        // T-SqlCopyShortcuts O-122：无选中 Ctrl+C 复制全文、有选中只复制片段
        //（层位与理由见 EditorCopyShortcuts 的 KDoc；本处此前无任何键盘复制绑定）
        EditorCopyShortcuts.install(created, UiTextClipboard::copy)
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
        syncStructureControls()
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

    /**
     * 切换选中规则的结构匹配（T-SqlSuppressUx 二段：手动开启/关闭），刷新列表徽标、按钮标签与提示。
     * 语义提示走 [statusLabel]（结构匹配会把「参数值不同的同形状 SQL」一并拦下，须让用户可见）。
     */
    private fun toggleStructureForSelected() {
        val rule = ruleList.selectedValue ?: return
        val newState = ruleManager.setStructureMatch(rule.id, !rule.structureMatch)
        refreshRuleList(select = rule)
        statusLabel.text = MpSqlVisualizerBundle.message(
            if (newState == true) "log.suppress.dialog.structure.onHint" else "log.suppress.dialog.structure.offHint",
        )
    }

    /** 批量：全部规则结构匹配开/关（「全部按结构匹配」勾选框）。 */
    private fun toggleStructureAll(enabled: Boolean) {
        ruleManager.setStructureMatchAll(enabled)
        refreshRuleList(select = ruleList.selectedValue)
        statusLabel.text = MpSqlVisualizerBundle.message(
            if (enabled) "log.suppress.dialog.structure.onHint" else "log.suppress.dialog.structure.offHint",
        )
    }

    /** 同步结构匹配控件态：按钮标签/可用性随选中规则；勾选框派生自全体规则（全开才勾选）。 */
    private fun syncStructureControls() {
        val rule = ruleList.selectedValue
        structureButton.isEnabled = rule != null
        structureButton.text = MpSqlVisualizerBundle.message(
            if (rule?.structureMatch == true) "log.suppress.dialog.structure.toggleOff"
            else "log.suppress.dialog.structure.toggleOn",
        )
        structureAllCheckBox.isSelected = ruleManager.rules.isNotEmpty() && ruleManager.rules.all { it.structureMatch }
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

    /** createdAt 展示片段（yyyy-MM-dd HH:mm）。F22（T-PerfSweep9）：SDF ThreadLocal 缓存化
     *  （照 LogTimestampParser 范式；此前每行每次重绘新建一个实例，分配面与 PS7-04 缓存化方向相逆）。
     *  复用实例输出与逐次新建逐字节一致（format 不读旧状态）。
     *  T-DevAcceptFix2 ③：字段本体已上移至类首（构造期渲染回调要读，声明序即契约，见该处注释）。 */
    private fun createdAtText(rule: SqlSuppressRule): String =
        createdAtFormat.get().format(Date(rule.createdAt))

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

        /** O-80：DimensionService 键（尺寸/位置记忆；getDimensionServiceKey 返回）。 */
        const val DIMENSION_SERVICE_KEY = "mpsv.sqlsuppress.manager.dialog"

        /** O-80：拖拽下限（中心面板 minimumSize；量级取「按钮行不折行+两侧可读」，真机观感定）。 */
        const val MIN_WIDTH = 560
        const val MIN_HEIGHT = 360
        const val SPLIT_PROPORTION = 0.38f
    }
}
