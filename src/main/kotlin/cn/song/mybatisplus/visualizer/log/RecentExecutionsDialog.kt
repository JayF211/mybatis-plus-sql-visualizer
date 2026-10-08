package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.EditorWhitespaceSettings
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.util.EditorCopyShortcuts
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
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
 * T-RecentMulti（用户令）：列表支持**多选**——语义照抄 SQL 日志页：Ctrl/Shift 多选、Ctrl+C 批量复制
 * （`SqlEntryList.itemToText` 逐行拼接）、右键命中行已在多选内则保留多选（[resolveContextMenuSelection]）、
 * 多选时编辑器按行序拼接显示（与复制产物同源）、状态行「已选 N 条」、主按钮打开 lead（最后交互行）。
 * 单选路径行为逐字节不变。
 *
 * 左侧列表直接复用日志面板条目渲染器 [LogEntryListCellRenderer]（同包 internal 直用）——
 * 两行形态、类型色条、耗时/来源角标与「SQL 日志」页完全一致（D86/D87 用户拍板：恒弹对话框
 * 含空态、编辑器内容=restoredSql）。
 *
 * 右侧编辑器三件（D88=A → D91 用户改定）：**恒为格式化显示**（[SqlFormatter.format]，与预览
 * 「格式化还原 SQL」同源口径；「查看原文」切换已按用户令删除）+「格式化」图标按钮（幂等重排 +
 * 格式化降级后的手动重试）+「复制」（[UiTextClipboard]，复制当前显示文本）——两枚按钮取日志转 SQL
 * 结果区同款图标（`AllIcons.Actions.ReformatCode` / `AllIcons.Actions.Copy`）。
 *
 * 列表右键菜单六件（T-RecentExecMenu O-119，形制对齐日志页）：复制时间戳 / 屏蔽此 SQL /
 * 发送到 SQL → Java / 发送到日志转 SQL / 跳转到 Java / 跳转到 Mapper XML——动作不自建，
 * 一律走共享件（[LogEntryJumpOps] / [batchCopyTimestamps] / [SqlSuppressRuleManager] /
 * [SqlLogManager.onOpenEntryInTab] 送页签通道）。
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
    private val entryList = SqlEntryList(listModel)

    /** 日志面板条目渲染器（复用点；字段持有供测试钉断言——JBList getter 平台侧会包 ExpandedItemListCellRendererWrapper 壳）。 */
    private val entryRenderer = LogEntryListCellRenderer()

    /** 只读高亮编辑器；null 仅在 dispose 之后（SqlSuppressManagerDialog 同生命周期约定）。 */
    private var editor: Editor? = null

    private val statusLabel = JBLabel().apply { foreground = UIUtil.getInactiveTextColor() }

    /** 弹框内可变视图（抑制成功后移除行用；构造期快照的本地副本，不回查日志缓冲）。 */
    private val currentEntries = mutableListOf<LogSqlEntry>()

    /**
     * 「格式化」按钮（D91）：**图标形态**照抄日志转 SQL 结果区的格式动作（`AllIcons.Actions.ReformatCode`
     * 图标 + 悬停文案；实现在 `LogToSqlPanel.ToggleFormatAction`，此处取同款图标与 tooltip 形态，
     * 但语义为「重新格式化」——显示恒为格式化态，此按钮是幂等重排 + 格式化降级后的手动重试）。
     */
    private val reformatButton = JButton(AllIcons.Actions.ReformatCode).apply {
        isFocusable = false
        toolTipText = MpSqlVisualizerBundle.message("log.recent.dialog.format")
        addActionListener { updateEditorFromSelection() }
    }

    /**
     * 「复制」按钮（D91）：图标同日志转 SQL 结果区复制动作（`AllIcons.Actions.Copy`）；
     * 复制**当前显示文本**（格式化版），走 [UiTextClipboard] 统一收口通道。
     */
    private val copyButton = JButton(AllIcons.Actions.Copy).apply {
        isFocusable = false
        toolTipText = MpSqlVisualizerBundle.message("log.recent.dialog.copy")
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
        // T-RecentMulti（用户令：本弹框也支持多选）：选择语义照抄 SQL 日志页——Ctrl/Shift 多选、
        // Ctrl+C 批量复制（走 SqlEntryList 的 itemToText 逐行拼接）、右键命中行已在多选内则保留多选。
        // 单选路径行为逐字节不变（编辑器显示单条、状态行计数、按钮动作对象同为该条）。
        entryList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        entryList.addListSelectionListener { event ->
            if (event.valueIsAdjusting) return@addListSelectionListener
            updateEditorFromSelection()
            syncSelectionScopedControls()
            syncStatusText()
        }
        // 双击 = 打开预览（与主按钮同路径）；右键 = 条目动作菜单（T-RecentExecMenu，形制同日志页）
        entryList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) doOKAction()
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
        })

        val leftPanel = JBScrollPane(entryList)

        val rightPanel = JPanel(BorderLayout()).apply {
            // 编辑器按钮行（D91）：格式化 + 复制，两枚均为图标按钮（图标/形态对齐日志转 SQL 结果区）
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                isOpaque = false
                add(reformatButton)
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
        // T-SqlCopyShortcuts：无选中 Ctrl+C 复制整个 SQL、有选中只复制片段（与复制按钮同反馈）。
        // 本类此前只有按钮路径——编辑器上按 Ctrl+C 因平台取不到 COPY_PROVIDER 而落回 Swing 无选中 no-op。
        EditorCopyShortcuts.install(
            created,
            UiTextClipboard::copy,
            onCopied = { cn.song.mybatisplus.visualizer.ui.flashCopied(copyButton) },
        )
        return created.component
    }

    /**
     * 选中条目 → 只读编辑器换文（D91：恒为格式化显示，「查看原文」已按用户令退役；
     * runWriteAction 直写不重建 editor）。
     *
     * T-RecentMulti：多选时按行序逐条格式化后换行拼接（与 Ctrl+C 复制产物同源——「所见即复制」）；
     * 单选时与改造前逐字节一致；空选中 → 清空。
     */
    private fun updateEditorFromSelection() {
        val ed = editor ?: return
        val selected = entryList.selectedValuesList
        val text = selected.joinToString("\n") { displayTextFor(it) }
        ApplicationManager.getApplication().runWriteAction {
            if (ed.document.text != text) ed.document.setText(text)
        }
    }

    /**
     * 条目 → 编辑器显示文本：格式化（与预览「格式化还原 SQL」同源 [SqlFormatter.format]，
     * 含语句结束符——口径对齐预览侧 `formatIfNeeded(withTerminator(restoredSql))`）；
     * 格式化异常按容错优先降级原文，不抛、不空屏（此时可用「格式化」按钮手动重试）。
     */
    private fun displayTextFor(entry: LogSqlEntry): String =
        runCatching { SqlFormatter.format(withTerminator(entry.restoredSql)) }
            .getOrDefault(entry.restoredSql)

    /**
     * 复制当前编辑器显示文本（格式化版）。
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
        currentEntries.clear()
        currentEntries.addAll(entries)
        listModel.replaceAll(currentEntries)
        val first = currentEntries.firstOrNull()
        if (first != null) entryList.setSelectedValue(first, false)
        syncStatusText()
        syncSelectionScopedControls()
    }

    /**
     * 状态行：空态复用 gutter 菜单既有「未找到」文案（不造重复键）；多选显示「已选 N 条」；
     * 否则「共 N 条」（T-RecentMulti 新增多选态）。
     */
    private fun syncStatusText() {
        val selectedCount = entryList.selectedIndices.size
        statusLabel.text = when {
            currentEntries.isEmpty() ->
                MpSqlVisualizerBundle.message("gutter.popup.recent.empty")
            selectedCount > 1 ->
                MpSqlVisualizerBundle.message("log.recent.dialog.selected", selectedCount.toString())
            else ->
                MpSqlVisualizerBundle.message("log.recent.dialog.count", currentEntries.size.toString())
        }
    }

    /**
     * T-RecentExecMenu（D92=A）：抑制成功后**即刻从弹框列表移除该行**——与日志页「存量行立即消失」
     * 的最强反馈一致，避免「balloon 说已屏蔽、行还杵在那」。只动弹框本地视图，不回查日志缓冲。
     */
    private fun removeEntryRow(entry: LogSqlEntry) {
        val index = currentEntries.indexOf(entry)
        if (index < 0) return
        currentEntries.removeAt(index)
        listModel.replaceAll(currentEntries)
        if (currentEntries.isEmpty()) {
            entryList.clearSelection()
            updateEditorFromSelection()
        } else {
            // 保持视点：选中原位置（末尾则左移一条）
            entryList.selectedIndex = minOf(index, currentEntries.size - 1)
        }
        syncStatusText()
        syncSelectionScopedControls()
    }

    /**
     * 选中态联动：主按钮「打开预览」/「格式化」/「复制」的可用态。
     * 空态（无选中）三者全禁用——双击/回车同样无动作。
     */
    private fun syncSelectionScopedControls() {
        val hasSelection = entryList.selectedValue != null
        okAction.isEnabled = hasSelection
        reformatButton.isEnabled = hasSelection
        copyButton.isEnabled = hasSelection
    }

    // ---------- T-RecentExecMenu O-119：列表右键菜单（六件，形制对齐日志页） ----------

    /**
     * 右键菜单：复制时间戳 / 屏蔽此 SQL / 发送到 SQL → Java / 发送到日志转 SQL /
     * 跳转到 Java / 跳转到 Mapper XML。
     *
     * 明确不做（与日志页的差异是刻意的）：**删除**（弹框是浏览视图，删的是全局日志缓冲条目，
     * 属越权副作用）、**方法级聚焦**（弹框锚点本身就是那个方法的位置）、**按请求聚焦**（跳走页签）、
     * **复制 SQL**（编辑器上方按钮已有，且口径更好：所见即复制）。
     */
    private fun showContextMenu(e: MouseEvent) {
        val index = entryList.locationToIndex(e.point)
        if (index < 0) return
        // T-RecentMulti：命中行已在多选集合内 → 保留原多选（复制项=批量）；否则收敛为该行
        //（与日志页 resolveContextMenuSelection 同一语义）
        entryList.selectedIndices = resolveContextMenuSelection(entryList.selectedIndices, index)
        val entry = entryList.model.getElementAt(index)
        val popup = ActionManager.getInstance().createActionPopupMenu(
            POPUP_ID,
            buildMenuGroup(entry, entryList.selectedValuesList),
        )
        // 243 的 ActionPopupMenu 只有 setter 无 getter，Kotlin 不合成属性——显式调 setter
        popup.setTargetComponent(entryList)
        popup.component.show(entryList, e.x, e.y)
    }

    /** 菜单动作组（[showContextMenu] 与测试访问器共用，单一来源）；[selectedEntries] 供批量项（复制时间戳）。 */
    private fun buildMenuGroup(
        entry: LogSqlEntry,
        selectedEntries: List<LogSqlEntry> = listOf(entry),
    ): DefaultActionGroup = DefaultActionGroup(
        CopyTimestampAction(selectedEntries),
        SuppressSqlAction(entry),
        SendToSqlToJavaAction(entry),
        SendToLogToSqlAction(entry),
        JumpToJavaAction(entry),
        JumpToMapperXmlAction(entry),
    )

    /** 复制选中条目的时间戳片段（与日志页同源 [batchCopyTimestamps]；多选批量、文案带条数；显示开关关闭时无片段 → 置灰）。 */
    private inner class CopyTimestampAction(private val entries: List<LogSqlEntry>) : AnAction() {
        private val clip = batchCopyTimestamps(entries)

        override fun update(e: AnActionEvent) {
            e.presentation.text =
                copyMenuText("log.panel.copyTimestamp", "log.panel.copyTimestampSelected", entries.size)
            e.presentation.isEnabled = clip.isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (clip.isNotEmpty()) UiTextClipboard.copy(clip)
        }
    }

    /**
     * 屏蔽此 SQL：规则入库（[SqlSuppressRuleManager] 公开 API）→ 从弹框列表移除该行（D92=A）→
     * balloon 反馈附「管理屏蔽规则…」直达（与日志页 `log.suppress.*` 文案/通知组同源）。
     */
    private inner class SuppressSqlAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("log.suppress.action")
        }

        override fun actionPerformed(e: AnActionEvent) {
            when (SqlSuppressRuleManager.getInstance().addRule(entry.restoredSql)) {
                SqlSuppressRuleManager.AddResult.ADDED -> {
                    removeEntryRow(entry)
                    showSuppressBalloon(1)
                }
                // 已存在：幂等——行照旧移除（该 SQL 已在屏蔽态，弹框不应再展示它）
                SqlSuppressRuleManager.AddResult.DUPLICATE -> removeEntryRow(entry)
                SqlSuppressRuleManager.AddResult.LIMIT_REACHED ->
                    showSuppressLimitBalloon()
            }
        }
    }

    /** 发送到 SQL → Java（第三页签）：经 [SqlLogManager.onOpenEntryInTab] 通道（面板私有字段不可直达）。 */
    private inner class SendToSqlToJavaAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.sendToReverse")
        }

        override fun actionPerformed(e: AnActionEvent) {
            sendEntryToTab(entry, LogEntryTab.SQL_TO_JAVA)
        }
    }

    /** 发送到日志转 SQL（第二页签）。 */
    private inner class SendToLogToSqlAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("logToSql.sendTo")
        }

        override fun actionPerformed(e: AnActionEvent) {
            sendEntryToTab(entry, LogEntryTab.LOG_TO_SQL)
        }
    }

    /** 跳转到 Java 调用方（T-M55-01 双目标之一；实时页口径——与日志页同文案同反馈）。 */
    private inner class JumpToJavaAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.jumpToJava")
        }

        override fun actionPerformed(e: AnActionEvent) {
            LogEntryJumpOps.jumpToSourceRealtime(project, entry)
        }
    }

    /**
     * 跳转到 mapper XML 语句节点：mapperStatement 非空且定位成功才显示
     * （update 内解析一次并存引用，actionPerformed 复用——与日志页单次右键一次索引查询同款）。
     */
    private inner class JumpToMapperXmlAction(private val entry: LogSqlEntry) : AnAction() {
        private var resolved: LogEntryJumpOps.XmlTarget? = null

        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.jumpToXml")
            resolved = LogEntryJumpOps.resolveXml(project, entry)
            e.presentation.isEnabledAndVisible = resolved != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val target = resolved ?: LogEntryJumpOps.resolveXml(project, entry) ?: return
            LogEntryJumpOps.navigate(project, target.virtualFile, target.line)
        }
    }

    /**
     * 送条目到工具窗页签：通道由 [SqlLogPanel] 注册（工具窗未创建即未注册）——
     * 未就绪时状态行轻提示降级，不静默（对齐跳转侧「不静默」约定）。
     *
     * 取管理器走**只读入口** [sqlLogManagerIfCreated]（T-ST-02b2 定论：读路径不得发起服务创建，
     * 否则可能因 temporarily-disposed 窗口把服务在本 JVM 内永久毒化；由
     * `SqlLogManagerLookupGuardTest` 扫描守卫锁定）。
     */
    private fun sendEntryToTab(entry: LogSqlEntry, tab: LogEntryTab) {
        val open = sqlLogManagerIfCreated(project)?.onOpenEntryInTab
        if (open == null) {
            statusLabel.text = MpSqlVisualizerBundle.message("log.recent.dialog.menuPanelNotReady")
            return
        }
        open(entry, tab)
    }

    /** 屏蔽成功 balloon（与日志页 `showSuppressBalloon` 同通知组同文案；附管理直达）。 */
    private fun showSuppressBalloon(addedCount: Int) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(SUPPRESS_NOTIFICATION_GROUP_ID)
                .createNotification(
                    MpSqlVisualizerBundle.message("log.suppress.added.title"),
                    MpSqlVisualizerBundle.message("log.suppress.added.body", addedCount.toString()),
                    NotificationType.INFORMATION,
                )
            notification.addAction(
                NotificationAction.createSimple(MpSqlVisualizerBundle.message("log.suppress.manage")) {
                    notification.expire()
                    SqlSuppressManagerDialog(project).show()
                },
            )
            notification.notify(project)
        }
    }

    /** 屏蔽规则达上限 balloon（D6：拒新增不淘汰）。 */
    private fun showSuppressLimitBalloon() {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(SUPPRESS_NOTIFICATION_GROUP_ID)
                .createNotification(
                    MpSqlVisualizerBundle.message("log.suppress.added.title"),
                    MpSqlVisualizerBundle.message(
                        "log.suppress.limit",
                        SqlSuppressRuleManager.MAX_RULES.toString(),
                    ),
                    NotificationType.WARNING,
                )
                .notify(project)
        }
    }

    /**
     * 主按钮/回车/双击：打开预览后**不关闭**本对话框（浏览对比场景）。
     * T-RecentMulti：多选下取 **lead**（最后交互行）——与「我点的那条」意图一致；单选等价于原 selectedValue。
     * 无选中直接返回（okAction 已禁用，防御直达调用）。
     */
    override fun doOKAction() {
        val lead = entryList.leadSelectionIndex
        val selected = if (lead >= 0) {
            listModel.getElementAt(lead)
        } else {
            entryList.selectedValue ?: return
        }
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

    /** 测试访问器：编辑器实例（T-SqlCopyShortcuts：键盘复制接线断言用）。 */
    internal fun editorForTest(): com.intellij.openapi.editor.Editor? = editor

    /** 测试访问器：条目列表实例（T-SqlCopyShortcuts：列表复制接线断言用）。 */
    internal fun entryListForTest(): javax.swing.JList<*> = entryList

    /** 测试访问器：状态行文本。 */
    internal fun statusTextForTest(): String = statusLabel.text

    /** 测试访问器：按索引选中（驱动选中切换链）。 */
    internal fun selectIndexForTest(index: Int) {
        entryList.selectedIndex = index
    }

    /** 测试访问器：多选（T-RecentMulti）。 */
    internal fun selectIndicesForTest(indices: IntArray) {
        entryList.selectedIndices = indices
    }

    /** 测试访问器：当前选中条目。 */
    internal fun selectedEntryForTest(): LogSqlEntry? = entryList.selectedValue

    /** 测试访问器：主按钮可用态。 */
    internal fun openPreviewEnabledForTest(): Boolean = okAction.isEnabled

    /** 测试访问器：触发主按钮动作（含「不关闭」契约断言的前半段）。 */
    internal fun triggerOpenPreviewForTest() = doOKAction()

    /** 测试访问器：「格式化」图标动作（D91 幂等重排）。 */
    internal fun triggerReformatForTest() = reformatButton.doClick()

    /** 测试访问器：「格式化」按钮可用态。 */
    internal fun reformatEnabledForTest(): Boolean = reformatButton.isEnabled

    /** 测试访问器：「格式化」按钮 tooltip（图标按钮的可见文案载体）。 */
    internal fun reformatTooltipForTest(): String? = reformatButton.toolTipText

    /** 测试访问器：「格式化」按钮图标（与日志转 SQL 结果区同源断言用）。 */
    internal fun reformatIconForTest(): javax.swing.Icon? = reformatButton.icon

    /** 测试访问器：触发复制。 */
    internal fun triggerCopyForTest() = copyCurrentSql()

    /** 测试访问器：复制按钮可用态。 */
    internal fun copyEnabledForTest(): Boolean = copyButton.isEnabled

    /** 测试访问器：复制按钮图标是否与日志转 SQL 结果区复制动作同源（`AllIcons.Actions.Copy`）。 */
    internal fun copyIconForTest(): javax.swing.Icon? = copyButton.icon

    /**
     * 测试访问器：右键菜单动作清单（按当前选中条目构建；headless 断言在场/文案/置灰，
     * 用 `AnActionEvent.createFromAnAction(...)` 驱动 update——仓内既有测试同法）。
     */
    internal fun menuActionsForTest(): List<AnAction> {
        val entry = entryList.selectedValue ?: return emptyList()
        return buildMenuGroup(entry).getChildren(null).toList()
    }

    /** 测试访问器：按**当前多选集合**构建菜单（T-RecentMulti：批量项文案断言用）。 */
    internal fun menuActionsForSelectionForTest(): List<AnAction> {
        val lead = entryList.leadSelectionIndex
        val entry = if (lead >= 0) listModel.getElementAt(lead) else entryList.selectedValue ?: return emptyList()
        return buildMenuGroup(entry, entryList.selectedValuesList).getChildren(null).toList()
    }

    /** 测试访问器：屏蔽规则经理（断言规则确实入库）。 */
    internal fun suppressRuleCountForTest(): Int = SqlSuppressRuleManager.getInstance().rules.size

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

        /** 列表右键 ActionPopupMenu 的 place（与日志页 `mpvs.log.list` 分开，两个菜单各自独立）。 */
        const val POPUP_ID = "mpvs.recent.exec.list"

        /** 屏蔽反馈通知组（plugin.xml 已注册；与日志页 `SUPPRESS_NOTIFICATION_GROUP_ID` 同组同文案）。 */
        const val SUPPRESS_NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.logsuppress"
    }
}
