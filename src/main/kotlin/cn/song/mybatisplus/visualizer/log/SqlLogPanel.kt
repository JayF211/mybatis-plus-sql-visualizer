package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.PluginSettingsConfigurable
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.ui.highlight.SqlHighlightColors
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JToggleButton
import javax.swing.ListModel
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

/** SQL 末尾分号结束符（显示层统一追加；已以 `;` 结尾或空串不重复；T-M42 提为顶层，openPreview/右键复制/列表复制共用）。 */
internal fun withTerminator(sql: String): String =
    if (sql.isEmpty() || sql.endsWith(";")) sql else sql + ";"

/**
 * 日志条目列表（T-M42）：覆写 JBList.itemToText——内置 Ctrl+C 复制默认返回 cellRenderer
 * 渲染文本（HTML 源码，用户实测缺陷），覆写后复制输出纯 SQL（多选逐条换行拼接、
 * CopyPasteManager 写入、Ctrl+C 快捷键注册均由 JBList 既有链路复用）。
 * open：internal 类默认 final，测试子类经公开包装访问 protected itemToText。
 */
internal open class SqlEntryList(model: ListModel<LogSqlEntry>) : JBList<LogSqlEntry>(model) {
    override fun itemToText(index: Int, element: LogSqlEntry): String = withTerminator(element.restoredSql)
}

/**
 * 排序模式（P-06）：NONE = 正序（默认）；DURATION_DESC = 按耗时降序。
 */
enum class SortMode { NONE, DURATION_DESC }

/**
 * 工具窗口面板（标签页 + 工具栏；headless 测试不布局，交互断言走条目模型层）。
 *
 * P-06 新增：
 * - 过滤条（工具栏下方）：类型按钮组（SELECT/INSERT/UPDATE/DELETE）+ 计数 + 「全部」按钮
 * - 高频折叠：折叠态条目右侧显示 ⨉ N 徽章，点击展开/折叠
 * - 耗时排序：检测到耗时信息时显示排序按钮
 */
class SqlLogPanel(
    private val project: Project,
    private val manager: SqlLogManager,
) : JPanel(BorderLayout()), Disposable {

    private val listModel = DefaultListModel<LogSqlEntry>()
    private val list = SqlEntryList(listModel)
    private val tabs = JBTabbedPane()
    /** T-M38：日志转 SQL 转换 tab（粘贴日志 → 批量还原可执行 SQL；原「SQL 预览」被动展示改造）。 */
    private val logToSqlPanel = LogToSqlPanel(project)
    /** T-M36.4：SQL → Java 转换 tab（粘贴 SQL → 格式化/补全 → 转换 → 可编辑 Java 高亮结果区）。 */
    private val sqlToJavaPanel = SqlToJavaPanel(project)
    private val dedupeCheckBox = JCheckBox(MpSqlVisualizerBundle.message("log.panel.dedupe")).apply {
        isSelected = manager.entries.dedupeEnabled
        addActionListener { manager.entries.dedupeEnabled = isSelected }
    }

    // ---------- P-06 过滤/折叠/排序状态 ----------

    /** 当前激活的类型过滤集合（默认全选 SELECT/INSERT/UPDATE/DELETE；OTHER 默认不选）。 */
    private val activeFilter = mutableSetOf(
        StatementType.SELECT, StatementType.INSERT, StatementType.UPDATE, StatementType.DELETE,
    )

    /** 高频折叠开关（默认关）。 */
    private var frequencyFoldEnabled = false

    /** 排序模式（默认正序）。 */
    private var sortMode = SortMode.NONE

    /** 当前分组状态（refreshList 后更新；用于渲染折叠徽章）。 */
    private var groupedEntries: List<GroupedEntry> = emptyList()

    /** 各类型计数（refreshList 后更新；用于按钮文案）。 */
    private var typeCounts: Map<StatementType, Int> = emptyMap()

    /** 是否含耗时信息（决定是否显示排序按钮）。 */
    private var hasAnyDurationInfo = false

    // ---------- P-06 UI 组件引用（供刷新计数/可见性用） ----------

    /** 类型按钮映射（类型 → 按钮；供动态更新文案/选中态）。 */
    private val typeButtons = mutableMapOf<StatementType, JToggleButton>()

    /** 「全部」按钮（一键全选/全取消）。 */
    private var allButton: JToggleButton? = null

    /** 高频折叠切换按钮。 */
    private var foldButton: JToggleButton? = null

    /** 耗时排序按钮（动态显隐）。 */
    private var sortButton: JLabel? = null

    // ---------- P-06 测试访问器（headless 测试断言用） ----------

    /** 当前列表模型条目数（测试断言）。 */
    internal val visibleEntryCount: Int get() = listModel.size()

    /** 当前列表模型条目 restoredSql 列表（测试断言）。 */
    internal val visibleEntries: List<String>
        get() = (0 until listModel.size()).map { listModel.getElementAt(it).restoredSql }

    /** 当前激活的类型过滤集合（测试断言）。 */
    internal val currentFilter: Set<StatementType> get() = activeFilter.toSet()

    /** 当前高频折叠开关（测试断言）。 */
    internal val isFoldEnabled: Boolean get() = frequencyFoldEnabled

    /** 当前排序模式（测试断言）。 */
    internal val currentSortMode: SortMode get() = sortMode

    /** 当前分组状态（测试断言）。 */
    internal val currentGroupedEntries: List<GroupedEntry> get() = groupedEntries

    /** 类型按钮映射（测试点击用）。 */
    internal val typeFilterButtons: Map<StatementType, JToggleButton> get() = typeButtons

    /** 「全部」按钮（测试点击用）。 */
    internal val allFilterButton: JToggleButton? get() = allButton

    /** 高频折叠按钮（测试点击用）。 */
    internal val foldToggleButton: JToggleButton? get() = foldButton

    /** 耗时排序按钮（测试点击用）。 */
    internal val durationSortLabel: JLabel? get() = sortButton

    /**
     * 工具栏动作组（T-M39：internal 供测试断言帮助入口存在性；
     * update 由工具栏生命周期驱动，headless 测试手动触发；须在 init 前声明）。
     */
    internal val toolbarGroup = DefaultActionGroup(
        CaptureAction(),
        ClearAction(),
        SettingsAction(),
        HelpAction(),
    )

    init {
        // 标签 1：SQL 日志（工具栏 + 过滤条 + 条目列表）
        val logPanel = JPanel(BorderLayout())
        logPanel.add(buildToolbar(), BorderLayout.NORTH) // JPanel（工具栏 + 去重勾选开关）
        logPanel.add(buildFilterBar(), BorderLayout.CENTER) // P-06：过滤条（类型按钮 + 折叠/排序）
        logPanel.add(buildListPane(), BorderLayout.SOUTH) // 条目列表

        // 标签 2：日志转 SQL（粘贴日志 → 批量还原可执行 SQL）
        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.logs"), logPanel)
        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.logToSql"), logToSqlPanel)
        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.sqlToJava"), sqlToJavaPanel)
        add(tabs, BorderLayout.CENTER)

        // T-M36.6：tab 重选时刷新形态下拉（设置页修改后切回即同步；不触发重生成）
        tabs.addChangeListener {
            if (tabs.selectedComponent == sqlToJavaPanel) {
                sqlToJavaPanel.refreshFormatFromSettings()
            }
        }

        manager.addUiListener { refreshList() }
        refreshList()

        // capturing 变化 → 刷新工具栏按钮图标/文案（调用 ActionToolbar.updateActionsAsync()；
        // T-M51-06：updateActionsImmediately() 已弃用，改用 updateActionsAsync()（接口公开方法，
        // 三平台 243/253/262 皆存——javap 实证）
        manager.addCapturingListener {
            LOG.info("SqlLogPanel.capturingListener: 触发")
            ApplicationManager.getApplication().invokeLater {
                _toolbarRef?.let { toolbar ->
                    runCatching {
                        toolbar.updateActionsAsync()
                        LOG.info("SqlLogPanel.capturingListener: updateActionsAsync 调用成功")
                    }.onFailure {
                        LOG.warn("SqlLogPanel.capturingListener: updateActionsAsync 失败 - ${it.message}")
                    }
                }
            }
        }
    }

    // ---------- 工具栏（IDEA 原生 ActionToolbar：扁平图标按钮 + tooltip） ----------

    private fun buildToolbar(): JPanel {
        // T-M31.16：暂停按钮移除（捕捉开关已覆盖——用户方案 A）
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_ID, toolbarGroup, true)
            .apply { targetComponent = this@SqlLogPanel }
        _toolbarRef = toolbar // 保存引用供 capturing 监听刷新

        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))
        bar.add(toolbar.component)
        bar.add(dedupeCheckBox) // 去重勾选开关（T-M31.5）
        return bar
    }

    /** 工具栏引用（capturing 变化时刷新按钮状态）。 */
    private var _toolbarRef: ActionToolbar? = null

    private inner class CaptureAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = if (manager.capturing) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
            e.presentation.text = MpSqlVisualizerBundle.message(
                if (manager.capturing) "log.panel.stop" else "log.panel.capture",
            )
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (manager.capturing) {
                manager.stopCapture()
            } else {
                manager.startCapture()
            }
        }
    }

    private inner class ClearAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.GC
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.clear")
        }

        override fun actionPerformed(e: AnActionEvent) {
            manager.entries.clear()
        }
    }

    private inner class SettingsAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.General.Settings
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.settings")
        }

        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, PluginSettingsConfigurable::class.java)
        }
    }

    /** T-M39：帮助入口（? 按钮，位于设置按钮旁）→ 帮助弹窗（核心功能/使用方法/默认快捷键）。
     *  图标用 AllIcons.Actions.Help（纯灰 #6E6E6E 问号，与设置/清空按钮同色系；
     *  ContextHelp 为灰蓝圆圈 #9AA7B0，与旁边按钮色相不一致——用户反馈）。 */
    private inner class HelpAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.Help
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.help")
        }

        override fun actionPerformed(e: AnActionEvent) {
            HelpDialog(project).show()
        }
    }

    // ---------- P-06 过滤条（工具栏下方：类型按钮组 + 高频折叠 + 耗时排序） ----------

    /**
     * 构建过滤条（P-06）：类型按钮组（全部 + SELECT/INSERT/UPDATE/DELETE）+ 高频折叠切换 + 耗时排序按钮。
     */
    private fun buildFilterBar(): JPanel {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))

        // 「全部」按钮
        allButton = JToggleButton(MpSqlVisualizerBundle.message("log.filter.all")).apply {
            isSelected = activeFilter.size >= 4
            addActionListener {
                if (isSelected) {
                    activeFilter.clear()
                    activeFilter.addAll(listOf(StatementType.SELECT, StatementType.INSERT, StatementType.UPDATE, StatementType.DELETE))
                } else {
                    activeFilter.clear()
                }
                syncTypeButtonSelection()
                refreshList()
            }
        }
        bar.add(allButton)

        // 类型按钮（SELECT / INSERT / UPDATE / DELETE）
        listOf(StatementType.SELECT, StatementType.INSERT, StatementType.UPDATE, StatementType.DELETE).forEach { type ->
            val label = when (type) {
                StatementType.SELECT -> MpSqlVisualizerBundle.message("log.filter.select")
                StatementType.INSERT -> MpSqlVisualizerBundle.message("log.filter.insert")
                StatementType.UPDATE -> MpSqlVisualizerBundle.message("log.filter.update")
                StatementType.DELETE -> MpSqlVisualizerBundle.message("log.filter.delete")
                else -> "OTHER"
            }
            val button = JToggleButton(label).apply {
                isSelected = type in activeFilter
                addActionListener {
                    if (isSelected) activeFilter.add(type) else activeFilter.remove(type)
                    syncAllButtonSelection()
                    refreshList()
                }
            }
            typeButtons[type] = button
            bar.add(button)
        }

        // 分隔条
        bar.add(JLabel(" | ").apply { foreground = Color.GRAY })

        // 高频折叠切换按钮
        foldButton = JToggleButton(MpSqlVisualizerBundle.message("log.fold.frequency")).apply {
            isSelected = frequencyFoldEnabled
            addActionListener {
                frequencyFoldEnabled = isSelected
                refreshList()
            }
        }
        bar.add(foldButton)

        // 耗时排序按钮（用 JLabel 模拟按钮；动态显隐）
        sortButton = JLabel(MpSqlVisualizerBundle.message("log.sort.duration")).apply {
            toolTipText = MpSqlVisualizerBundle.message("log.sort.duration")
            border = EmptyBorder(2, 6, 2, 6)
            isOpaque = true
            background = Color(0xE0E0E0)
            font = font.deriveFont(font.size2D - 1f)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    toggleDurationSort()
                }
            })
        }
        bar.add(sortButton)

        return bar
    }

    /** 同步类型按钮选中态（外部操作后刷新按钮 UI）。 */
    private fun syncTypeButtonSelection() {
        typeButtons.forEach { (type, button) ->
            button.isSelected = type in activeFilter
        }
    }

    /** 同步「全部」按钮选中态（4 个类型全选时「全部」选中）。 */
    private fun syncAllButtonSelection() {
        allButton?.isSelected = activeFilter.size >= 4
    }

    /**
     * 更新过滤条计数（refreshList 后调用）：各类型按钮文案 + 耗时排序按钮可见性。
     */
    private fun updateFilterBar() {
        typeButtons.forEach { (type, button) ->
            val baseLabel = when (type) {
                StatementType.SELECT -> MpSqlVisualizerBundle.message("log.filter.select")
                StatementType.INSERT -> MpSqlVisualizerBundle.message("log.filter.insert")
                StatementType.UPDATE -> MpSqlVisualizerBundle.message("log.filter.update")
                StatementType.DELETE -> MpSqlVisualizerBundle.message("log.filter.delete")
                else -> "OTHER"
            }
            val count = typeCounts[type] ?: 0
            button.text = "$baseLabel ($count)"
        }
        // 排序按钮显隐
        sortButton?.isVisible = hasAnyDurationInfo
        sortButton?.let {
            val sortLabel = when (sortMode) {
                SortMode.NONE -> MpSqlVisualizerBundle.message("log.sort.duration")
                SortMode.DURATION_DESC -> MpSqlVisualizerBundle.message("log.sort.duration.desc")
            }
            it.text = sortLabel
            it.toolTipText = sortLabel
        }
    }

    // ---------- 条目列表 ----------

    private fun buildListPane(): JBScrollPane {
        list.emptyText.text = MpSqlVisualizerBundle.message("log.panel.empty")
        list.cellRenderer = object : javax.swing.DefaultListCellRenderer() {
            /** P-06 折叠徽章标签（复用于每个折叠态模板行）。 */
            private val badgeLabel = JLabel().apply {
                isOpaque = true
                font = font.deriveFont(font.size2D - 1f)
                border = EmptyBorder(0, 4, 0, 4)
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            }

            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val entry = value as? LogSqlEntry
                text = null
                if (entry != null) {
                    // T-M31.13/14：SQL 词法着色（插件自身 SqlHighlightColors 配色，随 IDE 主题）+ 可选时间戳前缀
                    val timestamp = timestampHtml(entry.timestamp)
                    val sql = SqlListHtmlRenderer.render(withTerminator(entry.summary))
                    // P-06：检测是否为折叠态模板（高频折叠态：count > 1 && !expanded）
                    val foldedGroup = findFoldedGroup(entry)
                    if (foldedGroup != null) {
                        val badge = MpSqlVisualizerBundle.message("log.fold.badge", foldedGroup.count)
                        val tooltip = MpSqlVisualizerBundle.message("log.fold.tooltip.folded", foldedGroup.count)
                        this.text = "<html>" + timestamp + "<br>" + sql +
                            "&nbsp;<span style='color:#888888;background:#E8E8E8;'>$badge</span><br></html>"
                        toolTipText = tooltip + "\n" + entry.restoredSql
                    } else {
                        this.text = "<html>" + timestamp + "<br>" + sql + "<br></html>"
                        toolTipText = entry.restoredSql
                    }
                }
                return this
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // P-06：折叠态模板行单击切换展开/折叠（优先于双击预览）
                if (e.clickCount == 1) {
                    val idx = list.locationToIndex(e.point)
                    if (idx >= 0) {
                        val entry = list.model.getElementAt(idx)
                        val group = findFoldedGroup(entry)
                        if (group != null) {
                            group.expanded = !group.expanded
                            rebuildListModel()
                            return
                        }
                    }
                }
                if (e.clickCount >= 2) {
                    list.selectedValue?.let { openPreview(it) }
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
        })
        return JBScrollPane(list)
    }

    /**
     * 查找条目是否为折叠态模板（P-06）：返回对应的 [GroupedEntry]（当该条目是 count>1 的折叠态模板时），
     * 否则返回 null。
     *
     * 用引用相等（===）匹配模板——折叠态下 listModel 中的元素就是 [GroupedEntry.template] 引用。
     */
    private fun findFoldedGroup(entry: LogSqlEntry): GroupedEntry? =
        groupedEntries.firstOrNull { it.template === entry && it.count > 1 && !it.expanded }

    // ---------- P-06 刷新管线（过滤 → 排序 → 折叠） ----------

    /**
     * 刷新列表（P-06 全管线：过滤 → 排序 → 折叠 → 渲染）。
     *
     * 执行顺序：
     * 1. [applyFilter]：按 [activeFilter] 筛选条目
     * 2. [applySort]：按 [sortMode] 排序（降序耗时）
     * 3. [applyFrequencyFold]：按指纹分组（高频合并）
     * 4. 渲染到 [listModel]
     */
    private fun refreshList() {
        val snapshot = manager.entries.snapshot()

        // 计算类型计数（用于按钮文案；基于全量快照）
        typeCounts = snapshot.groupingBy { LogStatementClassifier.classify(it) }.eachCount()

        // 检测是否含耗时信息（决定是否显示排序按钮）
        hasAnyDurationInfo = snapshot.any { LogDurationParser.hasDurationInfo(it) }

        val filtered = applyFilter(snapshot)
        val sorted = applySort(filtered)
        groupedEntries = if (frequencyFoldEnabled) {
            val threshold = PluginSettings.getInstance().state.logFrequencyThreshold
                .coerceAtLeast(2)
            LogFrequencyGrouper.group(sorted, threshold)
        } else {
            sorted.map { GroupedEntry(it, 1, listOf(it)) }
        }

        rebuildListModel()
        updateFilterBar()
    }

    /** 按当前过滤条件筛选条目。 */
    private fun applyFilter(entries: List<LogSqlEntry>): List<LogSqlEntry> =
        entries.filter { LogStatementClassifier.classify(it) in activeFilter }

    /** 按当前排序模式排序条目。 */
    private fun applySort(entries: List<LogSqlEntry>): List<LogSqlEntry> = when (sortMode) {
        SortMode.NONE -> entries
        SortMode.DURATION_DESC -> entries.sortedByDescending { LogDurationParser.parse(it) ?: -1L }
    }

    /** 应用高频折叠（返回分组列表）。 */
    private fun applyFrequencyFold(entries: List<LogSqlEntry>): List<GroupedEntry> {
        val threshold = PluginSettings.getInstance().state.logFrequencyThreshold.coerceAtLeast(2)
        return LogFrequencyGrouper.group(entries, threshold)
    }

    /** 重建列表模型（从 [groupedEntries] 展开到 [listModel]）。 */
    private fun rebuildListModel() {
        listModel.clear()
        groupedEntries.forEach { ge ->
            if (ge.expanded) {
                ge.entries.forEach { listModel.addElement(it) }
            } else {
                listModel.addElement(ge.template)
            }
        }
    }

    /** 切换耗时排序（NONE → DURATION_DESC → NONE）。 */
    private fun toggleDurationSort() {
        sortMode = when (sortMode) {
            SortMode.NONE -> SortMode.DURATION_DESC
            SortMode.DURATION_DESC -> SortMode.NONE
        }
        refreshList()
    }

    // ---------- 刷新与动作 ----------

    /** 时间戳 HTML 前缀（设置开关 + 格式；灰字跟随主题）。 */
    private fun timestampHtml(timestamp: Long): String {
        val state = PluginSettings.getInstance().state
        if (!state.logTimestampEnabled) return ""
        val formatted = try {
            SimpleDateFormat(state.logTimestampFormat).format(Date(timestamp))
        } catch (_: IllegalArgumentException) {
            SimpleDateFormat(DEFAULT_TIMESTAMP_FORMAT).format(Date(timestamp)) // 非法格式回退
        }
        val color = toHex(EditorColorsManager.getInstance().globalScheme.getAttributes(SqlHighlightColors.COMMENT).foregroundColor)
        return "<span style='color:$color'>[$formatted] </span>"
    }

    private fun toHex(color: java.awt.Color): String {
        val r = Integer.toHexString(color.red).padStart(2, '0')
        val g = Integer.toHexString(color.green).padStart(2, '0')
        val b = Integer.toHexString(color.blue).padStart(2, '0')
        return "#$r$g$b"
    }

    private fun openPreview(entry: LogSqlEntry) {
        // T-M31.14：设置开启时预览展示格式化版（多行缩进），否则原样
        val state = PluginSettings.getInstance().state
        // T-M42：双击弹窗自动识别参数——`?` 数 == 参数数 ⇔ 匹配识别（值预填日志参数值，初始显示与现状一致）
        val recognized = entry.toRecognizedPreview()
        if (!recognized.recognized) {
            // 回退现状：restoredSql + 未解析警告（参数缺失/数量不匹配/纯静态 SQL）
            val base = withTerminator(entry.restoredSql)
            val sql = if (state.logFormatSql) SqlFormatter.format(base) else base
            SqlPreviewService.showResult(
                project,
                PreviewResult(
                    sql = sql,
                    compactSql = entry.restoredSql,
                    unresolvedParams = emptyList(),
                    paramTable = null,
                    wrapperType = null,
                    tableName = null,
                    warning = if ('?' in entry.restoredSql) {
                        MpSqlVisualizerBundle.message("log.preview.unresolvedWarning")
                    } else {
                        null
                    },
                ),
            )
            return
        }
        // 识别成功：rawSql 回填参数行（param1..N + 日志值预填 + 类型引号提示），全识别无警告
        val base = withTerminator(recognized.sql)
        val sql = if (state.logFormatSql) SqlFormatter.format(base) else base
        SqlPreviewService.showResult(
            project,
            PreviewResult(
                sql = sql,
                compactSql = withTerminator(recognized.compactSql),
                unresolvedParams = recognized.unresolvedParams,
                paramTable = null,
                wrapperType = null,
                tableName = null,
                warning = null,
                paramHints = recognized.paramHints,
                initialParamValues = recognized.initialValues,
            ),
        )
    }

    private fun showContextMenu(e: MouseEvent) {
        val index = list.locationToIndex(e.point)
        if (index >= 0) list.selectedIndex = index
        val entry = list.selectedValue ?: return
        val menu = JPopupMenu()
        menu.add(
            JMenuItem(MpSqlVisualizerBundle.message("log.panel.copy")).apply {
                addActionListener {
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(withTerminator(entry.restoredSql)), null)
                }
            },
        )
        menu.add(
            JMenuItem(MpSqlVisualizerBundle.message("log.panel.delete")).apply {
                addActionListener { manager.entries.removeEntry(entry) }
            },
        )
        // T-M37 P1：发送到 SQL → Java 转换（第三 tab）——还原 SQL 写入输入区并切换选中该 tab
        menu.add(
            JMenuItem(MpSqlVisualizerBundle.message("log.panel.sendToReverse")).apply {
                addActionListener {
                    sqlToJavaPanel.setInputSql(entry.restoredSql)
                    tabs.selectedComponent = sqlToJavaPanel
                }
            },
        )
        // T-M38：发送到日志转 SQL（第二 tab）——条目渲染回日志文本并立即转换
        menu.add(
            JMenuItem(MpSqlVisualizerBundle.message("logToSql.sendTo")).apply {
                addActionListener {
                    logToSqlPanel.loadFromEntry(entry)
                    tabs.selectedComponent = logToSqlPanel
                }
            },
        )
        menu.show(list, e.x, e.y)
    }

    /** 面板销毁：释放日志转 SQL 与 SQL→Java 面板编辑器（manager 生命周期由项目 @Service 管理）。 */
    override fun dispose() {
        logToSqlPanel.dispose()
        sqlToJavaPanel.dispose()
    }

    companion object {
        private val LOG = Logger.getInstance(SqlLogPanel::class.java)
        private const val TOOLBAR_ID = "cn.song.mybatisplus.visualizer.log.toolbar"
        private const val DEFAULT_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss"
    }
}
