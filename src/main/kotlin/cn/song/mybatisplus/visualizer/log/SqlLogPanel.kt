package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.SqlLogConfigurable
import cn.song.mybatisplus.visualizer.config.SettingsChangeListener
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import com.intellij.ui.JBColor
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSeparator
import javax.swing.JToggleButton
import javax.swing.ListModel
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

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

/** 类型多选弹窗可勾选的类型（OTHER 不单列；全选时 OTHER 一并透传）。 */
internal val FILTERABLE_TYPES: List<StatementType> = listOf(
    StatementType.SELECT,
    StatementType.INSERT,
    StatementType.UPDATE,
    StatementType.DELETE,
)

/**
 * 类型过滤（纯逻辑，可独立单测）：所选类型覆盖全部可筛选类型时视为「全部」，
 * 透传所有条目（含 OTHER）；否则仅保留分类命中的条目。
 */
internal fun filterByType(entries: List<LogSqlEntry>, activeTypes: Set<StatementType>): List<LogSqlEntry> {
    if (activeTypes.containsAll(FILTERABLE_TYPES)) return entries
    return entries.filter { LogStatementClassifier.classify(it) in activeTypes }
}

/**
 * 搜索过滤（纯逻辑，可独立单测）：查询词忽略大小写 contains，匹配还原 SQL 与摘要
 * （摘要含参数值的还原 SQL 前缀；参数值是常见检索目标）；空白查询透传全部。
 */
internal fun filterBySearch(entries: List<LogSqlEntry>, query: String): List<LogSqlEntry> {
    val q = query.trim()
    if (q.isEmpty()) return entries
    return entries.filter {
        it.restoredSql.contains(q, ignoreCase = true) || it.summary.contains(q, ignoreCase = true)
    }
}

/**
 * 工具窗口面板（标签页 + 工具栏；headless 测试不布局，交互断言走条目模型层）。
 *
 * 筛选项精简改造（2026-08-27）：
 * - 类型筛选：工具栏行「SQL类型」多选按钮（弹窗勾选，替代原两行按钮组）
 * - 高频折叠：整体移除（识别重复 SQL 的价值由去重 ⨉N 徽章承接）
 * - 去重徽章：开启「移除重复的sql」后被丢弃的 SQL 在条目旁累加显示 ⨉N
 * - Ctrl+F/⌘F 搜索：默认隐藏的搜索条，快捷键唤起实时过滤（Esc 清空后再按收起）
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
    /** F-44：历史 SQL tab（读取 .idea 目录中的自动保存文件，支持源码定位）。仅 logAutoSaveEnabled 时创建。 */
    private val historyPanel: HistorySqlLogPanel by lazy { HistorySqlLogPanel(project) }
    /** F-44：历史 tab 在 JBTabbedPane 中的索引（-1 表示未添加）。 */
    private var historyTabIndex = -1
    private val dedupeCheckBox = JCheckBox(MpSqlVisualizerBundle.message("log.panel.dedupe")).apply {
        isSelected = manager.entries.dedupeEnabled
        addActionListener {
            val wasEnabled = manager.entries.dedupeEnabled
            manager.entries.dedupeEnabled = isSelected
            if (!wasEnabled && isSelected) {
                // 重新开启去重：丢弃计数从零起算（徽章只反映本次开启后的去重量）
                manager.entries.resetDedupeCounts()
                refreshList()
            }
        }
    }

    // ---------- 过滤/排序状态 ----------

    /** 当前激活的类型过滤集合（默认全选 = 「全部」，含 OTHER 透传）。 */
    private val activeFilter = FILTERABLE_TYPES.toMutableSet()

    /** 排序模式（默认正序）。 */
    private var sortMode = SortMode.NONE

    /** 是否含耗时信息（决定是否显示排序按钮）。 */
    private var hasAnyDurationInfo = false

    /** 当前搜索词（搜索条实时同步；空串 = 不过滤）。 */
    private var searchText = ""

    /** 去重丢弃计数快照（refreshList 后更新；cellRenderer 渲染 ⨉N 徽章用）。 */
    private var dedupeCounts: Map<String, Int> = emptyMap()

    // ---------- O-1 日志窗口差异化：慢查询计数器 + 耗时着色 ----------

    /** 慢查询计数器（O-1 Phase C）。 */
    private val slowQueryCounter = SlowQueryCounter(
        PluginSettings.getInstance().state.slowQueryThresholdMs,
    )

    /** 工具栏慢查询计数标签（动态更新）。 */
    private var slowQueryLabel: JLabel? = null

    // ---------- UI 组件（工具栏行：捕捉/清空/设置/帮助 + 去重勾选 + SQL类型 + 耗时排序） ----------

    /** SQL类型标签（独立于按钮——`SQL类型：[状态]`，状态文字只放按钮）。 */
    private val typeFilterLabel = JLabel(MpSqlVisualizerBundle.message("log.filter.type.label"))

    /** SQL类型多选按钮（点击弹可勾选弹窗；文案只显示选中状态：全部 / 无 / 「查询, 更新」组合）。 */
    private val typeFilterButton = JButton().apply {
        isFocusable = false
        icon = AllIcons.General.ArrowDown
        horizontalTextPosition = SwingConstants.LEFT // 图标在文字右侧（下拉按钮惯例）
        toolTipText = MpSqlVisualizerBundle.message("log.filter.type")
        addActionListener { showTypeFilterPopup() }
    }

    /** 耗时排序按钮（JToggleButton，原生 LaF 渲染选中态；检测到耗时信息才显示）。 */
    private val sortButton = JToggleButton(MpSqlVisualizerBundle.message("log.sort.duration")).apply {
        isFocusable = false
        addActionListener { toggleDurationSort() }
    }

    /** 搜索条（默认隐藏，Ctrl+F/⌘F 唤起；实时过滤 restoredSql 与摘要）。 */
    private val searchField = buildSearchField()

    // ---------- 测试访问器（headless 测试断言用） ----------

    /** 当前列表模型条目数（测试断言）。 */
    internal val visibleEntryCount: Int get() = listModel.size()

    /** 当前列表模型条目 restoredSql 列表（测试断言）。 */
    internal val visibleEntries: List<String>
        get() = (0 until listModel.size()).map { listModel.getElementAt(it).restoredSql }

    /** 当前激活的类型过滤集合（测试断言）。 */
    internal val currentFilter: Set<StatementType> get() = activeFilter.toSet()

    /** 当前排序模式（测试断言）。 */
    internal val currentSortMode: SortMode get() = sortMode

    /** 耗时排序按钮（测试点击用）。 */
    internal val durationSortLabel: JToggleButton get() = sortButton

    /** SQL类型多选按钮（测试断言文案）。 */
    internal val typeFilterButtonForTest: JButton get() = typeFilterButton

    /** SQL类型标签（测试断言存在性与文案）。 */
    internal val typeFilterLabelForTest: JLabel get() = typeFilterLabel

    /** 应用类型过滤组合（测试驱动：设置选中集 + 刷新按钮文案与列表）。 */
    internal fun applyTypeFilterForTest(types: Set<StatementType>) {
        activeFilter.clear()
        activeFilter.addAll(types)
        updateTypeFilterButton()
        refreshList()
    }

    /** 搜索条（测试断言显隐/文本）。 */
    internal val searchFieldForTest: SearchTextField get() = searchField

    /**
     * 工具栏动作组（T-M39：internal 供测试断言帮助入口存在性；
     * update 由工具栏生命周期驱动，headless 测试手动触发；须在 init 前声明）。
     */
    internal val toolbarGroup = DefaultActionGroup(
        CaptureAction(),
        ClearAction(),
        SettingsAction(),
        ShowSearchAction(), // 搜索在帮助左侧（用户指定排序）
        HelpAction(),
    )

    init {
        // 注册子面板到 Disposer 树：父面板 dispose 时级联释放子面板（及其 DocumentListener），
        // 避免 IDE 退出时 ObjectTree 报 Memory leak detected
        Disposer.register(this, logToSqlPanel)
        Disposer.register(this, sqlToJavaPanel)

        // 标签 1：SQL 日志（工具栏行 + 搜索条 + 条目列表）
        val logPanel = JPanel(BorderLayout())
        logPanel.add(buildTopPanel(), BorderLayout.NORTH)
        logPanel.add(buildListPane(), BorderLayout.CENTER) // 条目列表铺满剩余空间

        // 标签 2：日志转 SQL（粘贴日志 → 批量还原可执行 SQL）
        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.logs"), logPanel)
        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.logToSql"), logToSqlPanel)
        tabs.addTab(MpSqlVisualizerBundle.message("log.tab.sqlToJava"), sqlToJavaPanel)
        // F-44：开启自动保存、或 .idea 中已有历史文件时显示历史 tab
        if (PluginSettings.getInstance().state.logAutoSaveEnabled || hasHistoryFiles()) {
            Disposer.register(this, historyPanel)
            historyTabIndex = tabs.tabCount
            tabs.addTab(MpSqlVisualizerBundle.message("log.tab.history"), historyPanel)
        }
        add(tabs, BorderLayout.CENTER)

        // T-M36.6：tab 重选时刷新形态下拉（设置页修改后切回即同步；不触发重生成）
        tabs.addChangeListener {
            if (tabs.selectedComponent == sqlToJavaPanel) {
                sqlToJavaPanel.refreshFormatFromSettings()
            }
            // F-44：切换到历史 tab 时刷新数据
            if (historyTabIndex >= 0 && tabs.selectedIndex == historyTabIndex) {
                historyPanel.refresh()
            }
        }

        // F-44 历史 tab 显隐跟随设置（设置页修改后即时生效，无需切 tab）
        val settingsListener = SettingsChangeListener {
            ApplicationManager.getApplication().invokeLater {
                updateHistoryTabVisibility()
            }
        }
        project.messageBus.connect(this).subscribe(PluginSettings.SETTINGS_CHANGE_TOPIC, settingsListener)

        manager.addUiListener { refreshList() }
        refreshList()

        // capturing 变化 → 刷新工具栏按钮图标/文案
        // 注意：updateActionsAsync()/updateActionsImmediately() 在组件不可见时会跳过更新
        // （ToolbarUpdater$MyUpdateRunnable 检查 isShowing，沙盒中工具栏可能不可见），
        // 故直接遍历 ActionButton 设置图标（绕过平台更新机制）
        manager.addCapturingListener {
            LOG.info("SqlLogPanel.capturingListener: 触发")
            ApplicationManager.getApplication().invokeLater {
                updateCaptureButtonIcon()
            }
        }
    }

    // ---------- 顶部区域（工具栏行 + 搜索条） ----------

    /** 顶部竖向容器：工具栏行（NORTH）+ 搜索条（SOUTH，默认隐藏不占空间）。 */
    private fun buildTopPanel(): JPanel {
        val top = JPanel(BorderLayout())
        top.add(buildToolbar(), BorderLayout.NORTH)
        top.add(searchField, BorderLayout.SOUTH)
        return top
    }

    private fun buildToolbar(): JPanel {
        // T-M31.16：暂停按钮移除（捕捉开关已覆盖——用户方案 A）
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_ID, toolbarGroup, true)
            .apply { targetComponent = this@SqlLogPanel }
        _toolbarRef = toolbar // 保存引用供 capturing 监听刷新

        updateTypeFilterButton()
        // 左右分区：左侧 动作图标 + SQL类型单元 + 耗时排序 + 慢查询计数；
        // 右侧「移除重复的sql」勾选（用户要求移到右侧，与 SQL类型 垂直居中平行、样式统一）
        val row = JPanel(BorderLayout())

        // 左侧流式区：WrapLayout——窄侧边栏放不下时折行且高度随之增长（原生 FlowLayout
        // preferredLayoutSize 只按单行算，折行后第二行会被裁掉——「SQL类型筛选项消失」根因）
        val left = JPanel(WrapLayout())
        left.add(toolbar.component)
        // 标签 + 按钮打包成一体：折行时「SQL类型：」与状态按钮同进退，不拆散两行
        val typeFilterUnit = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(typeFilterLabel)
            add(typeFilterButton)
        }
        left.add(typeFilterUnit)
        left.add(sortButton)
        // O-1：工具栏慢查询计数标签
        slowQueryLabel = JLabel().apply {
            foreground = Color(0xD32F2F) // 红色
            font = font.deriveFont(Font.BOLD)
            isVisible = false // 默认隐藏，有慢查询时显示
        }
        left.add(slowQueryLabel)
        row.add(left, BorderLayout.CENTER)

        // 右侧：去重勾选（Flow 面板带边距，BorderLayout 垂直居中 → 与左侧图标/标签同轴平行）
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 4)).apply {
            isOpaque = false
            add(dedupeCheckBox)
        }
        row.add(right, BorderLayout.EAST)
        return row
    }

    /** 搜索条（默认隐藏；Ctrl+F/⌘F 经 ShowSearchAction 唤起）。 */
    private fun buildSearchField(): SearchTextField {
        val field = SearchTextField(false) // 不启用历史弹窗
        field.isVisible = false
        field.getTextEditor().emptyText.text = MpSqlVisualizerBundle.message("log.search.emptyText")
        field.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onSearchTextChanged()
            override fun removeUpdate(e: DocumentEvent) = onSearchTextChanged()
            override fun changedUpdate(e: DocumentEvent) = onSearchTextChanged()
        })
        // Esc 收起：文本非空时平台已注册 ACTION_CLEAR_TEXT（SearchTextField.addNotify）先清空，
        // 故仅空文本时走到这里 → 收起搜索条（满足「非空先清空，已空再收起」两段式）
        field.addKeyboardListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE && field.text.isEmpty()) {
                    hideSearch()
                    e.consume()
                }
            }
        })
        return field
    }

    /** Ctrl+F/⌘F 唤起搜索条（快捷键经 CommonShortcuts.getFind() 平台 keymap 自适应 mac/win）。 */
    private inner class ShowSearchAction : AnAction() {
        init {
            registerCustomShortcutSet(CommonShortcuts.getFind(), list)
        }

        override fun update(e: AnActionEvent) {
            // 图标必须在 update 里写（工具栏 ActionButton 按 presentation 渲染；
            // 不设则渲染为占位圆圈——用户实测缺陷）
            e.presentation.icon = AllIcons.Actions.Search
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.search")
        }

        override fun actionPerformed(e: AnActionEvent) {
            showSearch()
        }
    }

    private fun showSearch() {
        if (!searchField.isVisible) {
            searchField.isVisible = true
            searchField.parent?.revalidate()
            searchField.parent?.repaint()
        }
        searchField.requestFocus()
    }

    private fun hideSearch() {
        searchField.text = "" // 非空时触发 DocumentListener 清过滤并刷新
        searchField.isVisible = false
        searchField.parent?.revalidate()
        searchField.parent?.repaint()
        list.requestFocusInWindow()
    }

    private fun onSearchTextChanged() {
        searchText = searchField.text
        refreshList()
    }

    // ---------- SQL类型多选弹窗 ----------

    /** 更新 SQL类型按钮文案：标签独立展示，按钮只显示状态（全部 / 无 / 类型组合）。 */
    private fun updateTypeFilterButton() {
        typeFilterButton.text = when {
            activeFilter.containsAll(FILTERABLE_TYPES) -> MpSqlVisualizerBundle.message("log.filter.all")
            activeFilter.isEmpty() -> MpSqlVisualizerBundle.message("log.filter.type.none")
            else -> FILTERABLE_TYPES.filter { it in activeFilter }.joinToString(", ") { labelForType(it) }
        }
    }

    /** 当前打开的类型弹窗内勾选框（全选/全不选即时同步用；弹窗每次打开重建）。 */
    private var typePopupCheckBoxes: List<Pair<StatementType, JCheckBox>> = emptyList()

    /**
     * 弹出类型多选弹窗：勾选即时生效且弹窗不关闭（JCheckBox 非 menu item，
     * 点击不触发 popup 收起，支持连续勾选组合）；底部全选/全不选一键设置——
     * 同步刷新弹窗内勾选框选中态 + 按钮文案 + 列表（弹窗保持打开，立即可见）。
     */
    private fun showTypeFilterPopup() {
        val popup = JPopupMenu()
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false
        content.border = EmptyBorder(8, 12, 8, 12)
        val checkBoxes = mutableListOf<Pair<StatementType, JCheckBox>>()
        FILTERABLE_TYPES.forEach { type ->
            val cb = JCheckBox(labelForType(type), type in activeFilter).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                isFocusable = false
                addActionListener {
                    if (isSelected) activeFilter.add(type) else activeFilter.remove(type)
                    updateTypeFilterButton()
                    refreshList()
                }
            }
            checkBoxes += type to cb
            content.add(cb)
        }
        typePopupCheckBoxes = checkBoxes
        content.add(Box.createVerticalStrut(6))
        content.add(JSeparator().apply { alignmentX = Component.LEFT_ALIGNMENT })
        content.add(Box.createVerticalStrut(6))
        val row = JPanel(GridBagLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(JButton(MpSqlVisualizerBundle.message("log.filter.selectAll")).apply {
                isFocusable = false
                addActionListener { applyAllTypes(true) }
            })
            add(JButton(MpSqlVisualizerBundle.message("log.filter.selectNone")).apply {
                isFocusable = false
                addActionListener { applyAllTypes(false) }
            })
        }
        content.add(row)
        popup.add(content)
        popup.show(typeFilterButton, 0, typeFilterButton.height + 2)
    }

    /** 全选/全不选（全选 = 「全部」语义，列表透传含 OTHER）：同步弹窗内勾选框 + 按钮文案 + 列表。 */
    private fun applyAllTypes(selected: Boolean) {
        activeFilter.clear()
        if (selected) activeFilter.addAll(FILTERABLE_TYPES)
        typePopupCheckBoxes.forEach { (type, cb) -> cb.isSelected = type in activeFilter }
        updateTypeFilterButton()
        refreshList()
    }

    // ---------- 工具栏动作 ----------

    /** 直接更新捕捉按钮图标（绕过 ActionToolbar 更新机制——沙盒中 isShowing 检查会导致更新被跳过）。 */
    private fun updateCaptureButtonIcon() {
        val toolbar = _toolbarRef ?: return
        val capturing = manager.capturing
        val icon = if (capturing) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
        val text = MpSqlVisualizerBundle.message(if (capturing) "log.panel.stop" else "log.panel.capture")
        // 遍历工具栏组件树，找到 ActionButton 并通过 getAction() 匹配 CaptureAction
        com.intellij.util.ui.UIUtil.uiTraverser(toolbar.component)
            .preOrderDfsTraversal()
            .filter { it.javaClass.simpleName == "ActionButton" }
            .forEach { btn ->
                val action = try {
                    btn.javaClass.getMethod("getAction").invoke(btn)
                } catch (_: Throwable) { null }
                if (action is CaptureAction) {
                    (btn as? javax.swing.AbstractButton)?.let {
                        it.icon = icon
                        it.text = text
                        it.repaint()
                    }
                    LOG.info("updateCaptureButtonIcon: 设置图标=${if (capturing) "停止" else "捕捉"}")
                }
            }
    }

    /**
     * F-44：根据 logAutoSaveEnabled 设置 + 历史数据存在与否同步历史 tab 的显隐。
     * 开启自动保存时显示；关闭后若 .idea 中仍有历史文件，也保留 tab（只读查看已有数据）。
     * 仅当无自动保存且无历史数据时才移除 tab。
     */
    private fun updateHistoryTabVisibility() {
        val enabled = runCatching { PluginSettings.getInstance().state.logAutoSaveEnabled }.getOrDefault(false)
        val hasHistoryData = enabled || hasHistoryFiles()
        if (hasHistoryData && historyTabIndex < 0) {
            // 添加历史 tab
            Disposer.register(this, historyPanel)
            historyTabIndex = tabs.tabCount
            tabs.addTab(MpSqlVisualizerBundle.message("log.tab.history"), historyPanel)
        } else if (!hasHistoryData && historyTabIndex >= 0) {
            // 移除历史 tab（避免选中正在移除的 tab）
            if (tabs.selectedIndex == historyTabIndex) {
                tabs.selectedIndex = 0
            }
            tabs.removeTabAt(historyTabIndex)
            historyTabIndex = -1
        }
    }

    /** F-44：检查 .idea 目录中是否存在历史 SQL 文件。 */
    private fun hasHistoryFiles(): Boolean {
        val basePath = project.basePath ?: return false
        val dir = java.io.File("$basePath${java.io.File.separator}.idea${java.io.File.separator}mybatis-plus-sql-visualizer${java.io.File.separator}sql-history")
        return dir.isDirectory && dir.listFiles { f -> f.extension == "sql" }?.isNotEmpty() == true
    }

    /** 工具栏引用（capturing 变化时刷新按钮状态）。 */
    private var _toolbarRef: ActionToolbar? = null

    private inner class CaptureAction : AnAction() {
        override fun update(e: AnActionEvent) {
            if (manager.capturing) {
                e.presentation.icon = AllIcons.Actions.Suspend
                e.presentation.text = MpSqlVisualizerBundle.message("log.panel.stop")
                e.presentation.description = if (manager.isWaitingForConsole) "已开启 SQL 监听，等待项目运行" else null
            } else {
                e.presentation.icon = AllIcons.Actions.Execute
                e.presentation.text = MpSqlVisualizerBundle.message("log.panel.capture")
                e.presentation.description = null
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (manager.capturing) {
                manager.stopCapture()
            } else {
                manager.startCapture(manual = true)  // 手动点击：设置 userArmed 持久标志
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
            ShowSettingsUtil.getInstance().showSettingsDialog(project, SqlLogConfigurable::class.java)
        }
    }

    /** T-M39：帮助入口（? 按钮，位于设置按钮旁）→ 编辑器标签页内嵌浏览器打开本地 HTML 帮助文档。
     *  图标用 AllIcons.Actions.Help（纯灰 #6E6E6E 问号，与设置/清空按钮同色系；
     *  ContextHelp 为灰蓝圆圈 #9AA7B0，与旁边按钮色相不一致——用户反馈）。
     *
     *  打开逻辑（仿 TranslationPlugin PlatformUtil.openOnlineDoc）：
     *  1. 从 resources 读取本地 HTML 帮助页面（marketplace-custom-page.html）；
     *  2. 写入临时文件后用 HTMLEditorProvider.openEditor 在内嵌 JCEF 浏览器中打开；
     *  3. 本地文件不受 JCEF --no-proxy-server 限制，无需网络，无代理问题；
     *  4. 若读取失败或打开异常 → 降级 BrowserUtil.browse 打开在线文档。 */
    private inner class HelpAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.Help
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.help")
        }

        override fun actionPerformed(e: AnActionEvent) {
            val htmlContent = loadText("html", "marketplace-custom-page.html")
            if (htmlContent.isBlank()) {
                LOG.warn("[HelpAction] Help HTML resource is empty, falling back to online doc")
                com.intellij.ide.BrowserUtil.browse(HELP_DOCUMENTATION_URL)
                return
            }
            try {
                val tempFile = java.io.File.createTempFile("SqlVisualizer-Help-", ".html")
                tempFile.deleteOnExit()
                tempFile.writeText(htmlContent, java.nio.charset.StandardCharsets.UTF_8)
                val url = tempFile.toURI().toString()
                // 仿 TranslationPlugin：附带 timeoutHtml，加载失败时显示降级页 + 跳转在线文档链接
                val timeoutHtml = buildTimeoutHtml()
                LOG.info("[HelpAction] Opening local help HTML: ${tempFile.absolutePath}")
                com.intellij.openapi.fileEditor.impl.HTMLEditorProvider.openEditor(
                    project, HELP_TAB_TITLE, url, timeoutHtml,
                )
            } catch (ex: Throwable) {
                LOG.warn("[HelpAction] Failed to open local help HTML, falling back to online doc", ex)
                com.intellij.ide.BrowserUtil.browse(HELP_DOCUMENTATION_URL)
            }
        }

        /** 构建 JCEF 加载失败时的降级 HTML 页面（含跳转在线文档的链接）。 */
        private fun buildTimeoutHtml(): String {
            val darkTheme = com.intellij.util.ui.UIUtil.isUnderDarcula()
            return loadText("html", "Timeout.html")
                .replace("__THEME__", if (darkTheme) "theme-dark" else "")
                .replace("__TITLE__", MpSqlVisualizerBundle.message("help.html.timeout.title"))
                .replace("__MESSAGE__", MpSqlVisualizerBundle.message("help.html.timeout.message"))
                .replace("__ACTION__", MpSqlVisualizerBundle.message("help.html.timeout.action", HELP_DOCUMENTATION_URL))
        }

        /** 从 resources 加载文本文件。 */
        private fun loadText(basePath: String, fileName: String): String {
            return try {
                val stream = HelpAction::class.java.classLoader.getResourceAsStream("$basePath/$fileName")
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Throwable) {
                ""
            }
        }
    }

    // ---------- 条目列表 ----------

    private fun buildListPane(): JBScrollPane {
        list.emptyText.text = MpSqlVisualizerBundle.message("log.panel.empty")
        // 多行 SQL 预览：启用可变行高（条目高度按内容自适应，支持 SQL 自动换行）
        list.fixedCellHeight = -1
        list.cellRenderer = object : javax.swing.DefaultListCellRenderer() {
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
                    // M46：纯文本渲染（移除 HTML 标签，消除 Swing HTML 引擎开销 + EDT 上的 SQL 词法分析）
                    val timestamp = formatTimestamp(entry.timestamp)
                    val duration = formatDuration(entry.executionTimeMs)
                    // 去重徽章：该 SQL 被「移除重复的sql」丢弃过的次数（开启期间累计）
                    val dedupeBadge = dedupeCounts[entry.restoredSql]?.takeIf { it > 0 }?.let { " ⨉$it " } ?: ""
                    this.text = "$timestamp$duration${entry.summary}$dedupeBadge"
                    toolTipText = entry.restoredSql
                    // 耗时着色（仅前景色，不走 HTML）
                    foreground = durationColor(entry.executionTimeMs, isSelected)
                }
                // 行底分隔线 + 四周内边距（条目视觉分隔清晰，解决"区分不清"）
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                    EmptyBorder(5, 8, 5, 8),
                )
                return this
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
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

    // ---------- 刷新管线（类型过滤 → 搜索过滤 → 排序 → 渲染） ----------

    /**
     * 刷新列表（过滤 → 搜索 → 排序 → 渲染）。
     *
     * 执行顺序：
     * 1. [filterByType]：按 [activeFilter] 筛选（全选 = 全部，含 OTHER）
     * 2. [filterBySearch]：按 [searchText] 搜索（大小写不敏感）
     * 3. [applySort]：按 [sortMode] 排序（降序耗时）
     * 4. 渲染到 [listModel]
     */
    private fun refreshList() {
        val snapshot = manager.entries.snapshot()

        // 检测是否含耗时信息（决定是否显示排序按钮）
        hasAnyDurationInfo = snapshot.any { LogDurationParser.hasDurationInfo(it) }

        // 去重丢弃计数快照（徽章渲染数据源；丢弃不触发通知，随下一次刷新呈现）
        dedupeCounts = manager.entries.dedupeCountsSnapshot()

        val filtered = filterByType(snapshot, activeFilter)
        val searched = filterBySearch(filtered, searchText)
        val sorted = applySort(searched)

        listModel.clear()
        // 一次性替换全部内容：fireIntervalAdded 仅触发一次布局计算（M46：O(N²) → O(N)）
        if (sorted.isNotEmpty()) {
            listModel.addAll(sorted)
        }
        updateSortButton()

        // O-1：同步慢查询阈值 + 更新计数（Phase C）
        slowQueryCounter.thresholdMs = PluginSettings.getInstance().state.slowQueryThresholdMs
        slowQueryCounter.reset()
        snapshot.forEach { slowQueryCounter.report(it.executionTimeMs) }
        updateSlowQueryLabel()
    }

    /** 按当前排序模式排序条目。 */
    private fun applySort(entries: List<LogSqlEntry>): List<LogSqlEntry> = when (sortMode) {
        SortMode.NONE -> entries
        SortMode.DURATION_DESC -> entries.sortedByDescending { LogDurationParser.parse(it) ?: -1L }
    }

    /** 更新耗时排序按钮可见性 + 文案 + 选中态。 */
    private fun updateSortButton() {
        sortButton.isVisible = hasAnyDurationInfo
        val sortLabel = when (sortMode) {
            SortMode.NONE -> MpSqlVisualizerBundle.message("log.sort.duration")
            SortMode.DURATION_DESC -> MpSqlVisualizerBundle.message("log.sort.duration.desc")
        }
        sortButton.text = sortLabel
        sortButton.toolTipText = sortLabel
        sortButton.isSelected = sortMode == SortMode.DURATION_DESC
    }

    /** 切换耗时排序（NONE → DURATION_DESC → NONE）。 */
    private fun toggleDurationSort() {
        sortMode = when (sortMode) {
            SortMode.NONE -> SortMode.DURATION_DESC
            SortMode.DURATION_DESC -> SortMode.NONE
        }
        refreshList()
    }

    // ---------- 渲染辅助 ----------

    /** M46：纯文本时间戳前缀（替代 timestampHtml，不再生成 HTML）。 */
    private fun formatTimestamp(timestamp: Long): String {
        val state = PluginSettings.getInstance().state
        if (!state.logTimestampEnabled) return ""
        val formatted = try {
            SimpleDateFormat(state.logTimestampFormat).format(Date(timestamp))
        } catch (_: IllegalArgumentException) {
            SimpleDateFormat(DEFAULT_TIMESTAMP_FORMAT).format(Date(timestamp))
        }
        return "[$formatted] "
    }

    /** M46：纯文本耗时文本（替代 durationHtml，不再生成 HTML）。 */
    private fun formatDuration(ms: Long?): String {
        if (ms == null) return ""
        return " ${ms}ms "
    }

    /** M46：耗时前景色（绿/黄/红，替代 HTML span 着色）。 */
    private fun durationColor(ms: Long?, isSelected: Boolean): java.awt.Color {
        if (isSelected) return list.selectionForeground
        if (ms == null) return list.foreground
        val threshold = PluginSettings.getInstance().state.slowQueryThresholdMs
        return when {
            ms >= threshold -> JBColor.RED
            ms >= threshold / 2 -> JBColor.ORANGE
            else -> list.foreground
        }
    }

    /**
     * O-1：日志 ↔ 源码关联跳转（Phase A）：提取表名 → 搜索 Wrapper → 跳转。
     */
    private fun jumpToSource(entry: LogSqlEntry) {
        val tableName = SqlTableExtractor.extractFirst(entry.restoredSql)
        if (tableName == null) {
            Messages.showInfoMessage(
                project,
                MpSqlVisualizerBundle.message("log.panel.jumpToSource.noTable"),
                MpSqlVisualizerBundle.message("log.panel.jumpToSource"),
            )
            return
        }
        // 后台搜索（ReferencesSearch 可能耗时，不阻塞 EDT）
        object : Task.Backgroundable(
            project,
            "Searching Wrappers for table \"$tableName\"",
            false,
        ) {
            private var result: WrapperSearchResult = WrapperSearchResult.None

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Searching Wrappers..."
                result = ReadAction.compute<WrapperSearchResult, Throwable> {
                    WrapperSearchService.search(project, tableName)
                }
            }

            override fun onSuccess() {
                when (val r = result) {
                    is WrapperSearchResult.Single ->
                        WrapperSearchService.navigateTo(project, r.candidate)
                    is WrapperSearchResult.Multiple ->
                        showCandidateChooser(r.candidates)
                    is WrapperSearchResult.None ->
                        Messages.showInfoMessage(
                            project,
                            MpSqlVisualizerBundle.message("log.panel.jumpToSource.noWrapper", tableName),
                            MpSqlVisualizerBundle.message("log.panel.jumpToSource"),
                        )
                }
            }
        }.queue()
    }

    /** 多候选选择弹窗（O-1 Phase A）。 */
    private fun showCandidateChooser(candidates: List<WrapperCandidate>) {
        val options = candidates.map {
            "${it.anchorMethod.name}  (${it.file.name})"
        }.toTypedArray()
        val choice = Messages.showChooseDialog(
            project,
            MpSqlVisualizerBundle.message("log.panel.jumpToSource.multipleHeader", candidates.size, candidates.first().table.tableName),
            MpSqlVisualizerBundle.message("log.panel.jumpToSource.multipleTitle"),
            Messages.getQuestionIcon(),
            options,
            options.firstOrNull() ?: "",
        )
        if (choice >= 0 && choice < candidates.size) {
            WrapperSearchService.navigateTo(project, candidates[choice])
        }
    }

    /** 更新工具栏慢查询计数标签（O-1 Phase C）。 */
    private fun updateSlowQueryLabel() {
        val count = slowQueryCounter.slowCount
        slowQueryLabel?.let {
            if (count > 0) {
                it.text = MpSqlVisualizerBundle.message("log.toolbar.slowQueryCount", count.toString())
                it.isVisible = true
            } else {
                it.isVisible = false
            }
        }
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
        // O-1：日志 ↔ 源码关联跳转（Phase A）
        menu.add(
            JMenuItem(MpSqlVisualizerBundle.message("log.panel.jumpToSource")).apply {
                addActionListener { jumpToSource(entry) }
            },
        )
        menu.show(list, e.x, e.y)
    }

    /** 面板销毁：释放日志转 SQL 与 SQL→Java 面板编辑器（manager 生命周期由项目 @Service 管理）。 */
    override fun dispose() {
        logToSqlPanel.dispose()
        sqlToJavaPanel.dispose()
    }

    // ---------- WrapLayout（工具栏行自动换行） ----------

    /**
     * WrapLayout：FlowLayout 的换行变体——空间不足时组件自动折到下一行，且 preferred/minimum
     * 尺寸按多行计算（原生 FlowLayout.preferredLayoutSize 只按单行算，折行后第二行会被裁掉，
     * 表现为「控件消失」——2026-08-27 用户实测 SQL类型按钮选中两个类型后触发的缺陷）。
     */
    private class WrapLayout : FlowLayout(LEFT, 4, 2) {

        override fun preferredLayoutSize(target: Container): Dimension {
            return computeSize(target, preferred = true)
        }

        override fun minimumLayoutSize(target: Container): Dimension {
            return computeSize(target, preferred = false)
        }

        private fun computeSize(target: Container, preferred: Boolean): Dimension {
            synchronized(target.treeLock) {
                val insets = target.getInsets()
                val availWidth = (target.parent?.width ?: Int.MAX_VALUE) - insets.left - insets.right
                var x = 0; var y = insets.top + vgap; var rowHeight = 0; var maxRowWidth = 0
                for (i in 0 until target.componentCount) {
                    val m = target.getComponent(i)
                    if (!m.isVisible) continue
                    val d = if (preferred) m.preferredSize else m.minimumSize
                    if (x + d.width > availWidth && x > 0) {
                        maxRowWidth = maxOf(maxRowWidth, x - hgap)
                        x = 0; y += rowHeight + vgap; rowHeight = 0
                    }
                    x += d.width + hgap; rowHeight = maxOf(rowHeight, d.height)
                }
                maxRowWidth = maxOf(maxRowWidth, x - hgap)
                return Dimension(maxRowWidth + insets.left + insets.right, y + rowHeight + insets.bottom)
            }
        }

        override fun layoutContainer(target: Container) {
            synchronized(target.treeLock) {
                val insets: Insets = target.getInsets()
                val availWidth: Int = target.width - insets.left - insets.right
                var x: Int = insets.left; var y: Int = insets.top; var rowHeight = 0
                for (i in 0 until target.componentCount) {
                    val m = target.getComponent(i)
                    if (!m.isVisible) continue
                    val d: Dimension = m.preferredSize
                    val rightEdge: Int = x + d.width
                    val maxRight: Int = insets.left + availWidth
                    if (rightEdge > maxRight && x > insets.left) {
                        x = insets.left; y += rowHeight + vgap; rowHeight = 0
                    }
                    m.setBounds(x, y, d.width, d.height)
                    x += d.width + hgap; rowHeight = maxOf(rowHeight, d.height)
                }
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(SqlLogPanel::class.java)
        private const val TOOLBAR_ID = "cn.song.mybatisplus.visualizer.log.toolbar"
        private const val DEFAULT_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss"
        /** T-M39：帮助文档 URL（降级系统浏览器打开）。 */
        private const val HELP_DOCUMENTATION_URL =
            "https://plugins.jetbrains.com/plugin/33633-mybatis-plus-sql-visualizer/documentation"
        /** T-M39：帮助标签页标题。 */
        private const val HELP_TAB_TITLE = "SQL Visualizer - Help"

        /** 类型 → 文案。 */
        private fun labelForType(type: StatementType): String = when (type) {
            StatementType.SELECT -> MpSqlVisualizerBundle.message("log.filter.select")
            StatementType.INSERT -> MpSqlVisualizerBundle.message("log.filter.insert")
            StatementType.UPDATE -> MpSqlVisualizerBundle.message("log.filter.update")
            StatementType.DELETE -> MpSqlVisualizerBundle.message("log.filter.delete")
            else -> "OTHER"
        }
    }
}
