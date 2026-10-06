package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
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
 * 参数命名方案管理对话框（R22 T-04）：左方案列表 + 按钮行 + 状态行。
 *
 * **UI 形态照抄 `log/SqlSuppressManagerDialog.kt` 全套**（真机退回教训——禁自创）：
 * 非模态（`DialogWrapper(project, false, IdeModalityType.MODELESS)`）、
 * `isResizable = true` + [getDimensionServiceKey] 尺寸/位置记忆（O-80 惯例）、
 * 左列表 + `FlowLayout(FlowLayout.LEFT, 4, 2)` 按钮行、inactive 色 [statusLabel]、
 * `createActions = arrayOf(cancelAction)`（关闭即生效，无确定/应用缓存层——操作直达
 * [cache]，与持久化单源）。
 *
 * 操作 = 另存为 / 重命名 / 删除；列表首项恒为默认方案（`ParamHistoryCache.DEFAULT_SCHEME`，
 * 显示名经 bundle 本地化）且禁改名删除（`DEFAULT_PROTECTED`）。删除为不可恢复操作，
 * `showYesNoDialog` 确认一次（弹窗克制红线，同 SqlSuppressManagerDialog 清空确认依据）。
 *
 * 数据交互面：方案名清单/条目数读 [ParamHistoryCache]；**当前参数快照由
 * [snapshotProvider] 注入**（对话框不知道预览面板——面板闭包供给
 * `historyCacheKey() to buildHistorySnapshot()`，无预览时 null）；任何方案变更后回调
 * [onSchemesChanged] 由面板同步下拉（激活方案被另存为切走时整面重召回）。
 *
 * 本类不持有 editor/平台监听等需释放资源（SqlSuppressManagerDialog 的 dispose 释放配对
 * 仅针对其 Editor，此处无对可配）；实现 [Disposable] 是 ParamImportDialog 同款形态——
 * 经公开接口方法放大 `dispose()` 可见性，供测试 teardown 显式释放未 show 的实例。
 */
class ParamSchemeManagerDialog(
    private val project: Project,
    private val cache: ParamHistoryCache,
    private val snapshotProvider: () -> Pair<String, Map<String, String>>?,
    private val onSchemesChanged: () -> Unit,
) : DialogWrapper(project, false, DialogWrapper.IdeModalityType.MODELESS), Disposable {

    /** 列表模型存**原始方案名**（默认方案 = 空串），显示名经渲染器本地化——改名/删除直取，免查表。 */
    private val listModel = CollectionListModel<String>()
    private val schemeList = JBList<String>(listModel)

    private val statusLabel = JBLabel().apply { foreground = UIUtil.getInactiveTextColor() }

    private val saveAsButton = JButton(MpSqlVisualizerBundle.message("panel.scheme.dialog.saveAs")).apply {
        isFocusable = false
        addActionListener { saveAsClicked() }
    }

    private val renameButton = JButton(MpSqlVisualizerBundle.message("panel.scheme.dialog.rename")).apply {
        isFocusable = false
        isEnabled = false
        addActionListener { renameClicked() }
    }

    private val deleteButton = JButton(MpSqlVisualizerBundle.message("panel.scheme.dialog.delete")).apply {
        isFocusable = false
        isEnabled = false
        addActionListener { deleteClicked() }
    }

    init {
        title = MpSqlVisualizerBundle.message("panel.scheme.dialog.title")
        setCancelButtonText(MpSqlVisualizerBundle.message("panel.scheme.dialog.close"))
        isResizable = true
        init()
        setSize(DIALOG_WIDTH, DIALOG_HEIGHT)
        refreshSchemeList(select = null)
    }

    /** O-80：尺寸/位置记忆（SqlSuppressManagerDialog/ErrorReportDialog 同惯例，零自持久化）。 */
    override fun getDimensionServiceKey(): String = DIMENSION_SERVICE_KEY

    override fun createCenterPanel(): JComponent {
        schemeList.cellRenderer = SchemeRowRenderer()
        schemeList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        schemeList.addListSelectionListener { event ->
            if (event.valueIsAdjusting) return@addListSelectionListener
            syncActionButtons()
        }

        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            add(saveAsButton)
            add(renameButton)
            add(deleteButton)
        }
        val listPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(schemeList), BorderLayout.CENTER)
            add(buttonRow, BorderLayout.SOUTH)
        }

        // 常驻说明（JTextArea 自动折行——JBLabel 不折行会撑宽对话框，SqlSuppressManagerDialog 同教训）
        val noteArea = JTextArea(MpSqlVisualizerBundle.message("panel.scheme.dialog.note")).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = null
            foreground = UIUtil.getInactiveTextColor()
            background = null
        }
        val southPanel = JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.NORTH)
            add(noteArea, BorderLayout.SOUTH)
        }

        val centerPanel = JPanel(BorderLayout()).apply {
            add(listPanel, BorderLayout.CENTER)
            add(southPanel, BorderLayout.SOUTH)
            // O-80：最小尺寸兜底（SqlSuppressManagerDialog 同款——DialogWrapper 无 setMinimumSize）
            minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
        }
        return centerPanel
    }

    /** 方案行渲染器：SqlSuppressManagerDialog.RuleRowRenderer 同形态（行底分隔线 + 内边距 + 选中色）。 */
    private inner class SchemeRowRenderer :
        JPanel(BorderLayout()),
        ListCellRenderer<String> {

        private val nameLine = SimpleColoredComponent()

        init {
            isOpaque = true
            nameLine.isOpaque = false
            add(nameLine, BorderLayout.CENTER)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                EmptyBorder(4, 8, 4, 8),
            )
        }

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out String>,
            value: String,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ): Component {
            background = if (selected) list.selectionBackground else list.background
            val fg = if (selected) list.selectionForeground else list.foreground
            nameLine.foreground = fg
            nameLine.clear()
            nameLine.append(displaySchemeName(value), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            if (value == ParamHistoryCache.DEFAULT_SCHEME) {
                nameLine.append(
                    "  ·  " + MpSqlVisualizerBundle.message("panel.scheme.default"),
                    SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
                )
            } else {
                // T-DevAcceptFix2 ①：条目摘要改为**当前方法**口径（预设已按方法/SQL 隔离，
                // 全局缓存键数对当前方法无意义；本键下 1 = 已存、0 = 未存）
                val here = if (currentKey()?.let { cache.recallFromScheme(value, it) } != null) "1" else "0"
                nameLine.append(
                    "  ·  " + MpSqlVisualizerBundle.message("panel.scheme.dialog.entry", here),
                    SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
                )
            }
            return this
        }
    }

    // ---------- 方案操作 ----------

    /** T-DevAcceptFix2 ①：当前预览的缓存键（对话框不自持状态——经 [snapshotProvider] 取）；
     *  null = 无预览上下文（名单只列默认方案）。 */
    private fun currentKey(): String? = runCatching { snapshotProvider()?.first }.getOrNull()

    /** 列表模型按 cache 当前方案重建（默认方案恒首位）；[select] 非 null 时选中。
     *  T-DevAcceptFix2 ①：名单与选中项均按**当前方法键**取（每个方法只列自己的预设）。 */
    private fun refreshSchemeList(select: String?) {
        val names = schemeNamesWithDefault()
        listModel.replaceAll(names)
        val target = select ?: cache.activeSchemeFor(currentKey())
        if (names.contains(target)) schemeList.setSelectedValue(target, false) else schemeList.clearSelection()
        statusLabel.text = if (names.size == 1) MpSqlVisualizerBundle.message("panel.scheme.dialog.empty") else ""
        syncActionButtons()
    }

    private fun schemeNamesWithDefault(): List<String> =
        listOf(ParamHistoryCache.DEFAULT_SCHEME) + cache.schemeNamesFor(currentKey())

    /** 重命名/删除按钮可用性：默认方案（或未选中）禁用——与 DEFAULT_PROTECTED 数据面语义双保险。 */
    private fun syncActionButtons() {
        val selected = schemeList.selectedValue
        val protectedOrNone = selected == null || selected == ParamHistoryCache.DEFAULT_SCHEME
        renameButton.isEnabled = !protectedOrNone
        deleteButton.isEnabled = !protectedOrNone
    }

    /** 另存为：输入方案名 → 快照由 [snapshotProvider] 注入 → [ParamHistoryCache.saveAsScheme]。
     *  弹窗克制红线白名单场景：用户必须输入方案名才能继续（BannedApiSourceScanTest 白名单登记）。 */
    private fun saveAsClicked() {
        val name = Messages.showInputDialog(
            project,
            MpSqlVisualizerBundle.message("panel.scheme.dialog.saveAsPrompt"),
            MpSqlVisualizerBundle.message("panel.scheme.dialog.title"),
            Messages.getQuestionIcon(),
        ) ?: return
        performSaveAs(name)
    }

    /** 重命名：预填当前名输入新名 → [ParamHistoryCache.renameScheme]。 */
    private fun renameClicked() {
        val selected = schemeList.selectedValue ?: return
        val newName = Messages.showInputDialog(
            project,
            MpSqlVisualizerBundle.message("panel.scheme.dialog.renamePrompt", selected),
            MpSqlVisualizerBundle.message("panel.scheme.dialog.title"),
            Messages.getQuestionIcon(),
            selected,
            null,
        ) ?: return
        performRename(newName)
    }

    /** 删除：确认一次（不可恢复）→ [ParamHistoryCache.deleteScheme]。
     *  弹窗克制红线白名单场景：破坏性知情确认，与 SqlSuppressManagerDialog 清空前确认同类。 */
    private fun deleteClicked() {
        val confirm = Messages.showYesNoDialog(
            project,
            MpSqlVisualizerBundle.message("panel.scheme.dialog.deleteConfirm", schemeList.selectedValue ?: return),
            MpSqlVisualizerBundle.message("panel.scheme.dialog.title"),
            Messages.getQuestionIcon(),
        )
        if (confirm != Messages.YES) return
        performDelete()
    }

    /** 另存为核心（输入对话框旁路共用于测试缝）：返回是否成功落库。 */
    private fun performSaveAs(rawName: String): Boolean {
        val name = rawName.trim()
        if (name.isEmpty()) {
            statusLabel.text = MpSqlVisualizerBundle.message("panel.scheme.dialog.blank")
            return false
        }
        if (name == ParamHistoryCache.DEFAULT_SCHEME || cache.hasScheme(name)) {
            statusLabel.text = MpSqlVisualizerBundle.message("panel.scheme.dialog.duplicate")
            return false
        }
        val snapshot = snapshotProvider() ?: run {
            statusLabel.text = MpSqlVisualizerBundle.message("panel.scheme.dialog.noSnapshot")
            return false
        }
        val result = cache.saveAsScheme(name, snapshot.first, snapshot.second)
        if (result == ParamHistoryCache.SchemeOpResult.LIMIT_REACHED) {
            // F49：方案个数上限（ParamHistoryCache.MAX_SCHEME_COUNT）——状态行提示，不落库
            statusLabel.text = MpSqlVisualizerBundle.message(
                "panel.scheme.dialog.limitReached", ParamHistoryCache.MAX_SCHEME_COUNT,
            )
            return false
        }
        refreshSchemeList(select = name)
        statusLabel.text = MpSqlVisualizerBundle.message("panel.scheme.dialog.saved", name)
        onSchemesChanged()
        return true
    }

    /** 重命名核心（输入对话框旁路共用于测试缝）：返回是否成功落库。 */
    private fun performRename(rawNewName: String): Boolean {
        val oldName = schemeList.selectedValue ?: return false
        if (oldName == ParamHistoryCache.DEFAULT_SCHEME) {
            statusLabel.text = MpSqlVisualizerBundle.message("panel.scheme.dialog.defaultProtected")
            return false
        }
        val newName = rawNewName.trim()
        if (newName.isEmpty()) {
            statusLabel.text = MpSqlVisualizerBundle.message("panel.scheme.dialog.blank")
            return false
        }
        val result = cache.renameScheme(oldName, newName)
        when (result) {
            ParamHistoryCache.SchemeOpResult.OK -> {
                refreshSchemeList(select = newName)
                statusLabel.text = MpSqlVisualizerBundle.message("panel.scheme.dialog.renamed", newName)
                onSchemesChanged()
                return true
            }
            ParamHistoryCache.SchemeOpResult.DUPLICATE ->
                statusLabel.text = MpSqlVisualizerBundle.message("panel.scheme.dialog.duplicate")
            ParamHistoryCache.SchemeOpResult.NOT_FOUND ->
                statusLabel.text = MpSqlVisualizerBundle.message("panel.scheme.dialog.notFound")
            ParamHistoryCache.SchemeOpResult.DEFAULT_PROTECTED ->
                statusLabel.text = MpSqlVisualizerBundle.message("panel.scheme.dialog.defaultProtected")
            // F49 不可达守卫：renameScheme 不产 LIMIT_REACHED/INVALID（仅 saveAsScheme 产），穷尽性收口
            ParamHistoryCache.SchemeOpResult.LIMIT_REACHED, ParamHistoryCache.SchemeOpResult.INVALID ->
                statusLabel.text = MpSqlVisualizerBundle.message("panel.scheme.dialog.notFound")
        }
        return false
    }

    /** 删除核心（确认对话框旁路共用于测试缝）：返回是否成功落库。 */
    private fun performDelete(): Boolean {
        val name = schemeList.selectedValue ?: return false
        if (name == ParamHistoryCache.DEFAULT_SCHEME) {
            statusLabel.text = MpSqlVisualizerBundle.message("panel.scheme.dialog.defaultProtected")
            return false
        }
        when (val result = cache.deleteScheme(name)) {
            ParamHistoryCache.SchemeOpResult.OK -> {
                refreshSchemeList(select = null)
                statusLabel.text = MpSqlVisualizerBundle.message("panel.scheme.dialog.deleted", name)
                onSchemesChanged()
                return true
            }
            ParamHistoryCache.SchemeOpResult.NOT_FOUND ->
                statusLabel.text = MpSqlVisualizerBundle.message("panel.scheme.dialog.notFound")
            ParamHistoryCache.SchemeOpResult.DEFAULT_PROTECTED ->
                statusLabel.text = MpSqlVisualizerBundle.message("panel.scheme.dialog.defaultProtected")
            ParamHistoryCache.SchemeOpResult.DUPLICATE ->
                statusLabel.text = MpSqlVisualizerBundle.message("panel.scheme.dialog.duplicate")
            // F49 不可达守卫：deleteScheme 不产 LIMIT_REACHED/INVALID（仅 saveAsScheme 产），穷尽性收口
            ParamHistoryCache.SchemeOpResult.LIMIT_REACHED, ParamHistoryCache.SchemeOpResult.INVALID ->
                statusLabel.text = MpSqlVisualizerBundle.message("panel.scheme.dialog.notFound")
        }
        return false
    }

    /** 只挂「关闭」（照 SqlSuppressManagerDialog：关闭即生效，无确定/应用缓存层）。 */
    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    /**
     * 可见性经 [Disposable] 公开接口方法放大（DialogWrapper.dispose 为 protected，测试
     * teardown 需显式释放未 show 的实例——ParamImportDialog 同款）；本类不持有
     * editor/平台监听等资源，无释放配对逻辑（见类 KDoc）。
     */
    override fun dispose() {
        super.dispose()
    }

    // ---------- 测试缝（headless 旁路输入/确认对话框） ----------

    /** 测试访问器：当前方案行数（含默认方案首位）。 */
    internal fun schemeCountForTest(): Int = listModel.size

    /** 测试访问器：按位置取方案原始名（默认方案断言「恒首位」用）。 */
    internal fun schemeNameAtForTest(index: Int): String = listModel.getElementAt(index)

    /** 测试访问器：当前选中方案原始名。 */
    internal fun selectedSchemeForTest(): String? = schemeList.selectedValue

    /** 测试访问器：状态行文本（操作结果断言）。 */
    internal fun statusTextForTest(): String = statusLabel.text

    /** 测试访问器：重命名按钮可用性（默认方案保护断言）。 */
    internal fun renameEnabledForTest(): Boolean = renameButton.isEnabled

    /** 测试访问器：删除按钮可用性（默认方案保护断言）。 */
    internal fun deleteEnabledForTest(): Boolean = deleteButton.isEnabled

    /** 测试访问器：DimensionService 键（UI 形态契约断言）。 */
    internal fun dimensionServiceKeyForTest(): String = getDimensionServiceKey()

    /** 测试访问器：程序化选中方案（同步按钮可用性）。 */
    internal fun selectSchemeForTest(name: String) {
        schemeList.setSelectedValue(name, false)
        syncActionButtons()
    }

    /** 测试访问器：另存为（旁路输入对话框）。 */
    internal fun saveAsForTest(name: String): Boolean = performSaveAs(name)

    /** 测试访问器：重命名选中方案（旁路输入对话框）。 */
    internal fun renameForTest(newName: String): Boolean = performRename(newName)

    /** 测试访问器：删除选中方案（旁路确认对话框）。 */
    internal fun deleteForTest(): Boolean = performDelete()

    companion object {
        const val DIALOG_WIDTH = 440
        const val DIALOG_HEIGHT = 320

        /** O-80：DimensionService 键（尺寸/位置记忆；getDimensionServiceKey 返回）。 */
        const val DIMENSION_SERVICE_KEY = "mpsv.paramscheme.manager.dialog"

        /** O-80：最小尺寸兜底（中心面板 minimumSize；量级=按钮行不折行+列表可读）。 */
        const val MIN_WIDTH = 360
        const val MIN_HEIGHT = 220
    }
}

/** 方案显示名：默认方案（空串内部名）经 bundle 本地化，命名方案原名直显。 */
internal fun displaySchemeName(name: String): String =
    if (name == ParamHistoryCache.DEFAULT_SCHEME) MpSqlVisualizerBundle.message("panel.scheme.default") else name
