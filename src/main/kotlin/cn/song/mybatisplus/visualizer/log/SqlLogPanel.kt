package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.PluginSettingsConfigurable
import cn.song.mybatisplus.visualizer.config.SettingsChangeListener
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.ui.highlight.SqlHighlightColors
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import java.awt.Container
import java.awt.Insets
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
import com.intellij.ui.JBColor
import javax.swing.BorderFactory
import com.intellij.util.messages.Topic
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSeparator
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

    // ---------- O-1 日志窗口差异化：慢查询计数器 + 耗时着色 ----------

    /** 慢查询计数器（O-1 Phase C）。 */
    private val slowQueryCounter = SlowQueryCounter(
        PluginSettings.getInstance().state.slowQueryThresholdMs,
    )

    /** 工具栏慢查询计数标签（动态更新）。 */
    private var slowQueryLabel: JLabel? = null

    // ---------- P-06 UI 组件引用（供刷新计数/可见性用） ----------

    /** 类型按钮映射（类型 → JToggleButton；供动态更新文案/选中态）。 */
    private val typeButtons = mutableMapOf<StatementType, JToggleButton>()

    /** 「全部」按钮（一键全选/全取消）。 */
    private var allButton: JToggleButton? = null

    /** 高频折叠切换按钮。 */
    private var foldButton: JToggleButton? = null

    /** 耗时排序按钮（JToggleButton，原生 LaF 渲染选中态；动态显隐）。 */
    private var sortButton: JToggleButton? = null

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
    internal val durationSortLabel: JToggleButton? get() = sortButton

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
        // 注册子面板到 Disposer 树：父面板 dispose 时级联释放子面板（及其 DocumentListener），
        // 避免 IDE 退出时 ObjectTree 报 Memory leak detected
        Disposer.register(this, logToSqlPanel)
        Disposer.register(this, sqlToJavaPanel)

        // 标签 1：SQL 日志（工具栏 + 过滤条 + 条目列表）
        val logPanel = JPanel(BorderLayout())
        logPanel.add(buildTopPanel(), BorderLayout.NORTH) // 工具栏 + 分割线 + 过滤条
        logPanel.add(buildListPane(), BorderLayout.CENTER) // 条目列表铺满剩余空间

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
            // 筛选条显隐跟随设置（设置页修改后切回即同步）
            refreshFilterBarVisibility()
        }

        // 设置变更即时刷新筛选条显隐（无需切 tab）
        val settingsListener = SettingsChangeListener {
            ApplicationManager.getApplication().invokeLater { refreshFilterBarVisibility() }
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
        // O-1：工具栏慢查询计数标签
        slowQueryLabel = JLabel().apply {
            foreground = Color(0xD32F2F) // 红色
            font = font.deriveFont(Font.BOLD)
            isVisible = false // 默认隐藏，有慢查询时显示
        }
        bar.add(slowQueryLabel)
        return bar
    }

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

    /** 筛选条容器（按设置显隐）。 */
    private var filterBarPanel: JPanel? = null

    /** 筛选条上方分割线（按设置显隐，与筛选条同步）。 */
    private var filterSeparator: JSeparator? = null

    /**
     * 顶部竖向容器（工具栏 + 分割线 + 过滤条）。
     * BoxLayout(Y_AXIS) 保证三者竖向堆叠、各拿首选高度；
     * 分割线全宽，maximumSize 限制竖向不被拉伸。
     * 筛选条与分割线仅在设置开启时可见。
     */
    private fun buildTopPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.alignmentX = Component.LEFT_ALIGNMENT
        val toolbar = buildToolbar().apply { alignmentX = Component.LEFT_ALIGNMENT }
        filterSeparator = JSeparator(SwingConstants.HORIZONTAL).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = EmptyBorder(3, 4, 3, 4)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        filterBarPanel = buildFilterBar().apply { alignmentX = Component.LEFT_ALIGNMENT }
        panel.add(toolbar)
        panel.add(filterSeparator!!)
        panel.add(filterBarPanel!!)
        refreshFilterBarVisibility()
        return panel
    }

    /** 按当前设置同步筛选条与分割线的可见性（runCatching 兜底：测试环境服务不可用时默认隐藏）。 */
    fun refreshFilterBarVisibility() {
        val enabled = runCatching { PluginSettings.getInstance().state.logFilterBarEnabled }.getOrDefault(false)
        filterSeparator?.isVisible = enabled
        filterBarPanel?.isVisible = enabled
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

    /**
     * 构建过滤条（P-06）：JToggleButton + IntelliJ 原生 LaF = 文字按钮 + 选中态。
     * 类型按钮（全部 + SELECT/INSERT/UPDATE/DELETE）+ 高频折叠切换 + 耗时排序按钮。
     */
    private fun buildFilterBar(): JPanel {
        val bar = JPanel(WrapLayout())

        // 「全部」按钮
        allButton = JToggleButton(MpSqlVisualizerBundle.message("log.filter.all")).apply {
            isSelected = activeFilter.size >= 4
            toolTipText = MpSqlVisualizerBundle.message("log.filter.all")
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

        // 类型按钮
        listOf(StatementType.SELECT, StatementType.INSERT, StatementType.UPDATE, StatementType.DELETE).forEach { type ->
            val label = labelForType(type)
            val button = JToggleButton(label).apply {
                isSelected = type in activeFilter
                toolTipText = label
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
            toolTipText = MpSqlVisualizerBundle.message("log.fold.frequency")
            addActionListener {
                frequencyFoldEnabled = isSelected
                refreshList()
            }
        }
        bar.add(foldButton)

        // 耗时排序按钮
        sortButton = JToggleButton(MpSqlVisualizerBundle.message("log.sort.duration")).apply {
            isSelected = sortMode == SortMode.DURATION_DESC
            toolTipText = MpSqlVisualizerBundle.message("log.sort.duration")
            addActionListener {
                toggleDurationSort()
            }
        }
        bar.add(sortButton)

        return bar
    }

    /**
     * 更新过滤条计数（refreshList 后调用）：各类型按钮文案 + 耗时排序按钮可见性。
     */
    private fun updateFilterBar() {
        typeButtons.forEach { (type, button) ->
            val baseLabel = labelForType(type)
            val count = typeCounts[type] ?: 0
            button.text = "$baseLabel ($count)"
            button.toolTipText = baseLabel
        }
        // 排序按钮显隐 + 文案 + 选中态
        sortButton?.isVisible = hasAnyDurationInfo
        sortButton?.let {
            val sortLabel = when (sortMode) {
                SortMode.NONE -> MpSqlVisualizerBundle.message("log.sort.duration")
                SortMode.DURATION_DESC -> MpSqlVisualizerBundle.message("log.sort.duration.desc")
            }
            it.text = sortLabel
            it.toolTipText = sortLabel
            it.isSelected = sortMode == SortMode.DURATION_DESC
        }
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

    // ---------- 条目列表 ----------

    private fun buildListPane(): JBScrollPane {
        list.emptyText.text = MpSqlVisualizerBundle.message("log.panel.empty")
        // 多行 SQL 预览：启用可变行高（条目高度按内容自适应，支持 SQL 自动换行）
        list.fixedCellHeight = -1
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
                    // O-1：执行耗时叠加（着色：绿/黄/红）
                    val durationStr = durationHtml(entry.executionTimeMs)
                    // P-06：检测是否为折叠态模板（高频折叠态：count > 1 && !expanded）
                    val foldedGroup = findFoldedGroup(entry)
                    if (foldedGroup != null) {
                        val badge = MpSqlVisualizerBundle.message("log.fold.badge", foldedGroup.count)
                        val tooltip = MpSqlVisualizerBundle.message("log.fold.tooltip.folded", foldedGroup.count)
                        this.text = "<html>" + timestamp + durationStr + "<br>" + sql +
                            "&nbsp;<span style='color:#888888;background:#E8E8E8;'>$badge</span></html>"
                        toolTipText = tooltip + "\n" + entry.restoredSql
                    } else {
                        this.text = "<html>" + timestamp + durationStr + "<br>" + sql + "</html>"
                        toolTipText = entry.restoredSql
                    }
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
        // 筛选条显隐跟随设置（设置页修改后立即生效，无需切 tab）
        refreshFilterBarVisibility()

        // O-1：同步慢查询阈值 + 更新计数（Phase C）
        slowQueryCounter.thresholdMs = PluginSettings.getInstance().state.slowQueryThresholdMs
        slowQueryCounter.reset()
        snapshot.forEach { slowQueryCounter.report(it.executionTimeMs) }
        updateSlowQueryLabel()
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

    /**
     * O-1：执行耗时 HTML 片段（着色：< 100ms 绿、100-1000ms 黄、> 1000ms 红；无耗时显示 N/A 灰色）。
     */
    private fun durationHtml(executionTimeMs: Long?): String {
        val level = SlowQueryDetector.level(executionTimeMs)
        val color = when (level) {
            SlowQueryDetector.DurationLevel.FAST -> "#4CAF50"      // 绿
            SlowQueryDetector.DurationLevel.MODERATE -> "#FF9800"  // 黄
            SlowQueryDetector.DurationLevel.SLOW -> "#D32F2F"      // 红
            SlowQueryDetector.DurationLevel.NONE -> "#9E9E9E"      // 灰
        }
        val text = when (executionTimeMs) {
            null -> MpSqlVisualizerBundle.message("log.duration.unavailable")
            else -> MpSqlVisualizerBundle.message("log.duration.ms", executionTimeMs.toString())
        }
        return "&nbsp;<span style='color:$color'>[$text]</span> "
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

    // ---------- WrapLayout（过滤条自动换行） ----------

    /**
     * WrapLayout：FlowLayout 的换行变体——空间不足时组件自动折到下一行。
     * 用于过滤条，解决侧边栏收缩时按钮被截断的问题。
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
