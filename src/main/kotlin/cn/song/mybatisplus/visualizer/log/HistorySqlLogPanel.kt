package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.SearchTextField
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.border.Border
import java.io.File
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 历史 SQL 面板（F-44-3 两级导航）：读取 .idea/sql-visualizer/sql-history/ 文件展示
 * （F-44-4：目录随插件更名，旧名 mybatis-plus-sql-visualizer 不再读取）。
 *
 * <p>展示层级（用户要求）：
 * <ol>
 *   <li><b>一级（默认）</b>：文件列表——每行 = 文件名 | 文件大小 | SQL 条数；双击进入二级；</li>
 *   <li><b>二级</b>：单个文件的 SQL 列表——每行 = 耗时 + 执行时间 + 源码位置 + SQL 摘要（高亮）；
 *       双击 SQL → 预览弹窗；右键 → 复制 SQL / 跳转到源码；顶部「返回」回一级。</li>
 * </ol>
 *
 * <p>搜索框一级按文件名过滤、二级按 SQL 内容/源文件过滤（emptyText 动态切换）。
 */
class HistorySqlLogPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    // ---------- 一级：文件列表 ----------
    private val fileListModel = DefaultListModel<HistoryFileEntry>()
    private val fileList = JBList<HistoryFileEntry>(fileListModel).also {
        // T-UiNative P0：一级文件列表打字即搜（平台 ListSpeedSearch，按文件名匹配）
        ListSpeedSearch.installOn(it) { entry -> entry.displayName }
    }
    private var allFiles: List<HistoryFileEntry> = emptyList()

    // ---------- 二级：SQL 列表（复用 SqlEntryList——Ctrl+C 复制纯 SQL 链路） ----------
    private val sqlListModel = DefaultListModel<LogSqlEntry>()
    private val sqlList = SqlEntryList(sqlListModel)
    /** T-ClickPreview：单击出轻量预览弹窗（点击时按行捕获 SQL，锚点在行下缘不遮行），双击走 openPreview 完整弹窗；悬停 tooltip 已移除（卡顿根因）。
     *  T-ListPreviewCopy：预览文本经 clickPreviewSql——设置「预览时格式化还原 SQL」开启（默认）时多行格式化显示。 */
    private val sqlClickPreview = SqlClickPreview(project, sqlList) {
        i -> clickPreviewSql(sqlList.model.getElementAt(i).restoredSql, PluginSettings.getInstance().state.logFormatSql)
    }
    private var currentFile: HistoryFileEntry? = null
    private var currentEntries: List<LogSqlEntry> = emptyList()

    // ---------- T-Perf-05：后台任务代际 + 搜索防抖 ----------

    /** 后台刷新/加载代际：新任务启动即作废旧任务的 EDT apply。 */
    private var bgGeneration = 0

    /** T-Perf-05：搜索防抖（每击键 O(N) clear+add → 停顿 200ms 应用一次）。 */
    private val searchDebounceTimer =
        javax.swing.Timer(SEARCH_DEBOUNCE_MS) { applySearch() }.apply { isRepeats = false }

    private val searchField = SearchTextField()

    // ---------- T-UiNative T-UN-10：顶部导航动作（原手摆 28×28「仿平台按钮」JButton 平移） ----------

    /** 导航工具栏引用（层级切换后即时刷新 Back 可见性；T-UN-10）。 */
    private var _navToolbarRef: ActionToolbar? = null

    /** 返回一级（原 backButton）：图标 Back、tooltip 沿用 log.history.back.tip；可见性 = 二级列表（update 输出）。 */
    private inner class BackAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.Back
            e.presentation.description = MpSqlVisualizerBundle.message("log.history.back.tip")
            e.presentation.isVisible = !isShowingFiles
        }

        override fun actionPerformed(e: AnActionEvent) {
            showFileList()
        }
    }

    /** 刷新（原 refreshButton）：图标 Refresh、tooltip 沿用 log.history.refresh.tip。 */
    private inner class RefreshAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.Refresh
            e.presentation.description = MpSqlVisualizerBundle.message("log.history.refresh.tip")
        }

        override fun actionPerformed(e: AnActionEvent) {
            refresh()
        }
    }

    /** 层级切换后即时刷新导航工具栏（Back 可见性走 update；组件不可见时平台跳过，无副作用）。 */
    private fun refreshNavToolbar() {
        _navToolbarRef?.updateActionsImmediately()
    }

    /** 二级列表当前行高（测试断言行高随模式重设用）。 */
    internal val sqlListFixedCellHeightForTest: Int get() = sqlList.fixedCellHeight

    /** 二级列表实例（测试断言 getCellBounds 行高与渲染 preferred 一致——布局缓存失效防回归用）。 */
    internal val sqlListForTest: javax.swing.JList<*> get() = sqlList

    /** 显示模式变更处理（topic 监听与测试共用）：行高随模式重设 + 二级可见时全量重建。
     *  多行片段缓存按条目内模式键自动失效（无需显式清）。日志面板同款处理经 topic 对齐。
     *  T-MergeFollowup-03（复查二轮）：BasicListUI 的 cellHeights 布局缓存不归 Swing layout 管
     *  （revalidate 无效），多行模式间切换 -1→-1 也不触发 setFixedCellHeight 的 propertyChange——
     *  updateUI() 重装列表 UI 强制布局状态失效（与日志页同款），applySqlSearch 全量重建作为双保险。
     *  T-DisplayModeToSettings：入口迁设置页「列表显示」，此处仅响应设置广播。 */
    internal fun onDisplayModeChanged() {
        applySqlListCellHeight()
        sqlList.updateUI()
        if (currentFile != null) applySqlSearch()
    }

    /**
     * 行高随显示模式分流（T-ListMode × T-LogListFreeze-04 权衡，与日志 tab [SqlLogPanel.applyListCellHeight] 同款）：
     * SINGLE_LINE 固定行高走 O(1) 布局快路径；KEYWORD_WRAP/FORMATTED 可变行高（条目行数不等），
     * 多行片段有条目级缓存，逐条测量只剩轻量组件布局。
     */
    internal fun applySqlListCellHeight() {
        sqlList.fixedCellHeight =
            if (PluginSettings.getInstance().sqlListDisplayMode() == SqlListDisplayMode.SINGLE_LINE) {
                sqlList.cellRenderer
                    .getListCellRendererComponent(sqlList, LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList()), 0, false, false)
                    .preferredSize.height
            } else {
                -1
            }
    }

    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout)

    // ---------- 测试访问器（F-44-3 两级导航真实场景测试） ----------

    /** 一级文件列表模型条目（测试断言：文件名/大小/条数）。 */
    internal val fileListEntries: List<HistoryFileEntry>
        get() = (0 until fileListModel.size()).map { fileListModel.getElementAt(it) }

    /** 二级 SQL 列表模型条目（测试断言）。 */
    internal val sqlListEntries: List<LogSqlEntry>
        get() = (0 until sqlListModel.size()).map { sqlListModel.getElementAt(it) }

    /** 当前是否处于一级文件列表视图（测试断言）。 */
    internal val isShowingFiles: Boolean get() = currentFile == null

    /** 打开指定文件进入二级（测试驱动；等价双击文件）。 */
    internal fun openFileForTest(entry: HistoryFileEntry) = loadFileContentSync(entry)

    /** 返回一级（测试驱动；等价点击返回按钮）。 */
    internal fun backToFilesForTest() = showFileList()

    /** 设置搜索词（测试驱动；等价在搜索框输入，立即应用过滤——生产路径 DocumentListener 走防抖）。 */
    internal fun setSearchForTest(query: String) {
        searchField.text = query
        applySearch()
    }

    /** 二级 SQL 列表单击预览控制器（T-ClickPreview 测试断言）。 */
    internal val sqlClickPreviewForTest: SqlClickPreview get() = sqlClickPreview

    init {
        buildUI()
        refresh()
    }

    private fun buildUI() {
        // 顶部工具栏：返回（仅二级可见）+ 刷新 + 搜索
        // T-UiNative T-UN-10：手摆按钮 → 平台 ActionToolbar（Back 可见性由 BackAction.update 按当前层级输出）
        val navToolbar = ActionManager.getInstance()
            .createActionToolbar("mpvs.history.nav", DefaultActionGroup(BackAction(), RefreshAction()), true)
            .apply { targetComponent = this@HistorySqlLogPanel }
        _navToolbarRef = navToolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        toolbar.add(navToolbar.component)
        // T-DisplayModeToSettings：显示模式入口迁设置页「列表显示」（工具栏按钮移除）
        searchField.textEditor.emptyText.text = MpSqlVisualizerBundle.message("log.history.search.files")
        searchField.textEditor.minimumSize = Dimension(200, searchField.textEditor.minimumSize.height)
        searchField.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = scheduleSearch()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = scheduleSearch()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = scheduleSearch()
        })
        toolbar.add(searchField)
        add(toolbar, BorderLayout.NORTH)

        // T-ListMode：设置变更（含模式切换广播）→ 清二级列表渲染缓存并重绘；
        // connect(this) 随面板 Disposer 树自动注销。invokeLater 显式 ModalityState.any()
        // （设置页红线：模态弹窗存续期默认 modality 会挂起回调）
        project.messageBus.connect(this).subscribe(
            PluginSettings.SETTINGS_CHANGE_TOPIC,
            cn.song.mybatisplus.visualizer.config.SettingsChangeListener {
                ApplicationManager.getApplication().invokeLater(
                    { onDisplayModeChanged() },
                    com.intellij.openapi.application.ModalityState.any(),
                )
            },
        )

        // 一级：文件列表
        fileList.emptyText.text = MpSqlVisualizerBundle.message("log.history.files.empty")
        fileList.cellRenderer = HistoryFileCellRenderer()
        // T-LogListFreeze-04：固定行高（单行恒定高）——JList 布局走 O(1) 快路径，不再逐条调渲染器测量
        fileList.fixedCellHeight = fileList.cellRenderer
            .getListCellRendererComponent(fileList, HistoryFileEntry(File("sample.sql"), 1), 0, false, false)
            .preferredSize.height
        fileList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2) {
                    fileList.selectedValue?.let { showFileContent(it) }
                }
            }
        })
        cards.add(JBScrollPane(fileList), CARD_FILES)

        // 二级：SQL 列表
        sqlList.emptyText.text = MpSqlVisualizerBundle.message("log.history.entries.empty")
        sqlList.cellRenderer = HistoryCellRenderer()
        applySqlListCellHeight()
        sqlList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // T-ClickPreview：先转发（仅左键；单击命中行才排程预览/双击停表并关旧预览），双击再开完整弹窗
                if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    sqlClickPreview.onClick(e.clickCount, e.point)
                }
                if (e.clickCount >= 2) {
                    sqlList.selectedValue?.let { openPreview(it) }
                }
            }
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showSqlContextMenu(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showSqlContextMenu(e)
            }
        })
        cards.add(JBScrollPane(sqlList), CARD_SQL)
        add(cards, BorderLayout.CENTER)
    }

    // ---------- 视图切换 ----------

    private fun showFileList() {
        currentFile = null
        currentEntries = emptyList()
        sqlClickPreview.hide() // 返回一级时若二级预览弹窗还开着，一并关掉
        refreshNavToolbar() // T-UN-10：Back 可见性随层级即时刷新（原 backButton.isVisible = false）
        searchField.textEditor.emptyText.text = MpSqlVisualizerBundle.message("log.history.search.files")
        cardLayout.show(cards, CARD_FILES)
        applyFileSearch()
    }

    private fun showFileContent(fileEntry: HistoryFileEntry) {
        currentFile = fileEntry
        refreshNavToolbar() // T-UN-10：Back 可见性随层级即时刷新（原 backButton.isVisible = true）
        searchField.textEditor.emptyText.text = MpSqlVisualizerBundle.message("log.history.search.empty")
        cardLayout.show(cards, CARD_SQL)
        // T-Perf-05：立即清空旧文件内容（大文件后台读取期间不残留旧文件条目，防误操作）
        currentEntries = emptyList()
        applySqlSearch()
        val gen = ++bgGeneration
        object : Task.Backgroundable(project, MpSqlVisualizerBundle.message("log.history.load.progress"), true) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                val entries = SqlLogFileReader.readFromFile(fileEntry.file)
                ApplicationManager.getApplication().invokeLater {
                    if (gen != bgGeneration || project.isDisposed) return@invokeLater
                    currentEntries = entries
                    applySqlSearch()
                    LOG.info("HistorySqlLogPanel.showFileContent: ${fileEntry.file.name} 共 ${entries.size} 条 SQL")
                }
            }
        }.queue()
    }

    /** 测试缝：同步加载文件内容（生产路径 showFileContent 已后台化，headless 测试需要确定性）。 */
    private fun loadFileContentSync(fileEntry: HistoryFileEntry) {
        currentFile = fileEntry
        refreshNavToolbar() // T-UN-10：Back 可见性随层级即时刷新（原 backButton.isVisible = true）
        searchField.textEditor.emptyText.text = MpSqlVisualizerBundle.message("log.history.search.empty")
        cardLayout.show(cards, CARD_SQL)
        currentEntries = SqlLogFileReader.readFromFile(fileEntry.file)
        applySqlSearch()
    }

    // ---------- 刷新 ----------

    /**
     * T-CrudColor：仅重绘二级 SQL 列表（不重扫文件、不重载列表）。
     *
     * 设置页改了配色/标记样式后由 [SqlLogPanel] 的设置变更监听调用——渲染器直读设置，
     * 重绘即生效；走 repaint 而非 [refresh] 是为了不打断用户当前浏览位置、不触发磁盘扫描。
     */
    internal fun repaintSqlList() {
        sqlList.repaint()
    }

    /**
     * 扫描 .idea 目录：生成文件摘要列表；若正在浏览某文件且文件仍存在则保持二级并重载，否则回一级。
     *
     * T-Perf-05：目录扫描 + countInFile / readFromFile 全部移入 [Task.Backgroundable]
     * （100MB 自动保存文件的逐行扫描在 EDT 直跑必然长冻结）；EDT 只做结果应用。
     * 代际守卫：新一轮刷新/加载启动后，旧后台任务的 apply 自动作废。
     */
    fun refresh() {
        val gen = ++bgGeneration
        val keepFile = currentFile?.file
        object : Task.Backgroundable(project, MpSqlVisualizerBundle.message("log.history.scan.progress"), true) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                val scanned = scanHistoryFiles(getHistoryDir())
                val keepReload = keepFile?.takeIf { it.exists() }?.let { SqlLogFileReader.readFromFile(it) }
                ApplicationManager.getApplication().invokeLater {
                    if (gen != bgGeneration || project.isDisposed) return@invokeLater
                    applyRefresh(scanned, keepReload)
                }
            }
        }.queue()
    }

    /** 测试缝：同步刷新（生产路径 refresh() 已后台化；headless 测试需要确定性）。 */
    internal fun refreshForTest() {
        ++bgGeneration // 作废在途后台 apply（构造期 refresh() 的后台扫描可能尚未完成）
        val keepFile = currentFile?.file
        applyRefresh(scanHistoryFiles(getHistoryDir()), keepFile?.takeIf { it.exists() }?.let { SqlLogFileReader.readFromFile(it) })
    }

    /** 目录扫描 + 条目计数（纯 IO，后台执行）。 */
    private fun scanHistoryFiles(dir: String): List<HistoryFileEntry> {
        val directory = File(dir)
        val files = if (directory.isDirectory) {
            directory.listFiles { f -> f.extension == "sql" }?.sortedBy { it.name } ?: emptyList()
        } else {
            emptyList()
        }
        return files.map { file -> HistoryFileEntry(file, SqlLogFileReader.countInFile(file)) }
    }

    /** 应用扫描结果（EDT；仅刷新/加载代际最新者执行）。 */
    private fun applyRefresh(scanned: List<HistoryFileEntry>, keepReload: List<LogSqlEntry>?) {
        allFiles = scanned
        LOG.info("HistorySqlLogPanel.refresh: 扫描到 ${allFiles.size} 个历史文件")

        // 保持二级视图：当前文件仍存在则重载内容
        if (keepReload != null) {
            currentEntries = keepReload
            applySqlSearch()
            return
        }
        // 否则回一级（默认展示文件列表）
        currentFile = null
        currentEntries = emptyList()
        refreshNavToolbar() // T-UN-10：Back 可见性随层级即时刷新（原 backButton.isVisible = false）
        searchField.textEditor.emptyText.text = MpSqlVisualizerBundle.message("log.history.search.files")
        cardLayout.show(cards, CARD_FILES)
        applyFileSearch()
    }

    // ---------- 搜索过滤 ----------

    /** T-Perf-05：搜索输入防抖调度（DocumentListener 入口）。 */
    private fun scheduleSearch() {
        searchDebounceTimer.restart()
    }

    private fun applySearch() {
        if (currentFile != null) applySqlSearch() else applyFileSearch()
    }

    /** 一级：按文件名过滤。 */
    private fun applyFileSearch() {
        val query = searchField.text.trim().lowercase()
        fileListModel.clear()
        val filtered = if (query.isEmpty()) {
            allFiles
        } else {
            allFiles.filter { it.displayName.lowercase().contains(query) }
        }
        filtered.forEach { fileListModel.addElement(it) }
    }

    /**
     * 二级：按 SQL 内容 / 行号 / 时间 / 源文件过滤（T-LogSearchRowTime 与日志页 filterBySearch
     * 同款语义——行号 `#5`/`5` 精确 equals、时间按显示片段 contains（所见即所搜，开关关闭不参与）；
     * 源文件为历史页特有检索目标，保留独立分支。
     */
    private fun applySqlSearch() {
        val q = searchField.text.trim()
        sqlListModel.clear()
        val filtered = if (q.isEmpty()) {
            currentEntries
        } else {
            val rowNumber = q.removePrefix("#").toLongOrNull()
            currentEntries.filter { entry ->
                entry.restoredSql.contains(q, ignoreCase = true) ||
                    entry.summary.contains(q, ignoreCase = true) ||
                    (rowNumber != null && entry.sequenceNumber == rowNumber) ||
                    timestampDisplayText(entry)?.contains(q, ignoreCase = true) == true ||
                    entry.sourceFile?.contains(q, ignoreCase = true) == true
            }
        }
        filtered.forEach { sqlListModel.addElement(it) }
    }

    private fun getHistoryDir(): String {
        val basePath = project.basePath ?: return ""
        return "$basePath${File.separator}.idea${File.separator}${SqlLogAutoSaver.DATA_DIR_NAME}${File.separator}sql-history"
    }

    private fun openPreview(entry: LogSqlEntry) {
        SqlPreviewService.showResult(project, buildPreviewResult(entry))
    }

    /**
     * 双击预览的 PreviewResult 构建（T-HistParam F3，internal 供 UI 测试不弹窗断言识别结果）：
     * 与日志 tab 共用 [toPreviewResult] 统一识别通道——v2 历史条目（rawSql/params 齐全）参数行
     * 与日志页一致，字面量条目走 F4 恢复；T-ListPreviewCopy：formatSql 改跟随设置
     * 「预览时格式化还原 SQL」（logFormatSql，默认开=默认格式化），消除与日志 tab 双击预览的不一致。
     */
    internal fun buildPreviewResult(entry: LogSqlEntry): PreviewResult =
        entry.toPreviewResult(
            formatSql = PluginSettings.getInstance().state.logFormatSql,
            literalRecovery = PluginSettings.getInstance().state.literalParamRecovery,
        )

    // ---------- 二级右键菜单 ----------

    private fun showSqlContextMenu(e: MouseEvent) {
        val idx = sqlList.locationToIndex(e.point)
        if (idx < 0) return
        // T-ListPreviewCopy：与日志 tab 同款选中收敛——命中行已在多选内保留（复制项=批量复制多条），
        // 否则收敛为该行；跳转等其余菜单项恒作用于被右键的行
        sqlList.selectedIndices = resolveContextMenuSelection(sqlList.selectedIndices, idx)
        val entry = sqlListModel.getElementAt(idx)
        val selectedEntries = sqlList.selectedIndices.map { sqlListModel.getElementAt(it) }

        // T-UiNative P1a：JPopupMenu+JMenuItem → 平台 DefaultActionGroup+AnAction（ActionPopupMenu 承载）
        val group = DefaultActionGroup()
        group.add(CopySqlAction(selectedEntries))
        // T-LogSearchRowTime：复制时间戳（与日志页同款——纯时间文本无方括号；显示关闭时置灰，
        // 置灰逻辑在 CopyTimestampAction.update）
        val timestampClip = batchCopyTimestamps(selectedEntries)
        group.add(CopyTimestampAction(selectedEntries, timestampClip))
        group.add(JumpToSourceAction(entry))
        val popup = ActionManager.getInstance().createActionPopupMenu("mpvs.history.sql", group)
        // 243 的 ActionPopupMenu 只有 setter 无 getter，Kotlin 不合成 targetComponent 属性——显式调 setter
        popup.setTargetComponent(sqlList)
        popup.component.show(sqlList, e.x, e.y)
    }

    // ---------- T-UiNative P1a：二级右键菜单动作（原 JMenuItem 语义平移，update 设图标/文案/灰置） ----------

    private inner class CopySqlAction(private val entries: List<LogSqlEntry>) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.Copy
            e.presentation.text = copyMenuText("log.history.copy", "log.history.copySelected", entries.size)
        }

        override fun actionPerformed(e: AnActionEvent) {
            UiTextClipboard.copy(batchCopySql(entries))
        }
    }

    private inner class CopyTimestampAction(private val entries: List<LogSqlEntry>, private val clip: String) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.Copy
            e.presentation.text = copyMenuText("log.history.copyTimestamp", "log.history.copyTimestampSelected", entries.size)
            e.presentation.isEnabled = clip.isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (clip.isNotEmpty()) {
                UiTextClipboard.copy(clip)
            }
        }
    }

    private inner class JumpToSourceAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.EditSource
            e.presentation.text = MpSqlVisualizerBundle.message("log.history.jumpToSource")
        }

        override fun actionPerformed(e: AnActionEvent) {
            jumpToSource(entry)
        }
    }

    /**
     * 跳转到产生该 SQL 的源码位置的解析结果（internal 供 UI 测试确定性断言跳转分支，
     * 规避 headless 下 Messages 弹窗阻塞）：Navigate=文件存在可跳转；NoSource=缺源码位置；
     * NotFound=源码路径无效/文件不存在。
     *
     * 抽取原因（F-44-5 全插件 UI 测试）：用户反馈「历史缺代码位置导致无法跳转」——
     * 跳转分支（可跳 / 缺位置 / 文件丢失）必须用确定性断言锁定，而非依赖弹窗副作用。
     */
    internal sealed class HistoryJumpTarget {
        data class Navigate(val virtualFile: com.intellij.openapi.vfs.VirtualFile, val line: Int) : HistoryJumpTarget()
        data object NoSource : HistoryJumpTarget()
        data class NotFound(val sourceFile: String) : HistoryJumpTarget()
    }

    /** 解析跳转目标（与 [jumpToSource] 共用；测试驱动直接断言分支，不触发弹窗）。 */
    internal fun resolveJumpTarget(entry: LogSqlEntry): HistoryJumpTarget {
        if (entry.sourceFile == null) return HistoryJumpTarget.NoSource
        // M37.1：多源根容错——basePath 直拼失败后按模块 content root 匹配（SourcePathResolver）
        val virtualFile = SourcePathResolver.resolveVirtualFile(project, entry.sourceFile)
        if (virtualFile != null) {
            return HistoryJumpTarget.Navigate(virtualFile, maxOf(0, entry.sourceLine - 1))
        }
        return HistoryJumpTarget.NotFound(entry.sourceFile)
    }

    /**
     * 跳转到产生该 SQL 的源码位置（测试驱动；等价右键「跳转到源码」）。
     * 通过 sourceFile + sourceLine 直接定位（F-44 新格式）。
     */
    internal fun jumpToSourceForTest(entry: LogSqlEntry) = jumpToSource(entry)

    /**
     * 跳转到产生该 SQL 的源码位置。
     * 通过 sourceFile + sourceLine 直接定位（F-44 新格式）；
     * T-JumpCapture：无捕获位置时弹框引导开启源码捕获拦截器。
     */
    private fun jumpToSource(entry: LogSqlEntry) {
        when (val target = resolveJumpTarget(entry)) {
            is HistoryJumpTarget.NoSource -> {
                // 缺源码位置（旧版 F-43 条目 / 未启用拦截器捕获）：引导开启源码捕获（T-JumpCapture）
                SourceCaptureGuide.showMissingSourceDialog(project)
            }
            is HistoryJumpTarget.NotFound -> {
                // 文件不存在时提示（项目结构可能已变化）；T-UiNative T-UN-02：改轻通知（原模态弹窗）
                // 测试模式跳过（对齐 CopyExecutableSqlAction.showToast 约定）
                if (!ApplicationManager.getApplication().isUnitTestMode) {
                    runCatching {
                        Notification(
                            NOTIFICATION_GROUP_ID,
                            MpSqlVisualizerBundle.message("log.history.jumpToSource"),
                            MpSqlVisualizerBundle.message("log.history.jumpToSource.notFound", target.sourceFile),
                            NotificationType.INFORMATION,
                        ).notify(project)
                    }
                }
            }
            is HistoryJumpTarget.Navigate -> {
                OpenFileDescriptor(project, target.virtualFile, target.line, 0).navigate(true)
            }
        }
    }

    override fun dispose() {
        // T-Perf-05：面板销毁停止防抖 Timer（防晚到 apply 触碰已销毁面板）
        searchDebounceTimer.stop()
        sqlClickPreview.dispose()
    }

    companion object {
        private val LOG = Logger.getInstance(HistorySqlLogPanel::class.java)
        private const val CARD_FILES = "files"
        private const val CARD_SQL = "sql"

        /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
        private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

        /** T-Perf-05：搜索输入防抖窗口（毫秒）。 */
        private const val SEARCH_DEBOUNCE_MS = 200
    }
}

/**
 * 历史 SQL 文件摘要（一级列表数据源，F-44-3）。
 */
data class HistoryFileEntry(
    /** 历史文件（.sql）。 */
    val file: File,
    /** 文件内 SQL 条目数（[SqlLogFileReader.countInFile] 轻量统计）。 */
    val sqlCount: Int,
) {
    val displayName: String get() = file.name
    val sizeBytes: Long get() = file.length()
}

/** 历史面板元信息灰（与原 HTML 通道 #9E9E9E 一致）。T-UN-13（T-UiNative）：改 namedColor，亮值不变。 */
private val HISTORY_META_GRAY = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.HISTORY_META_GRAY)

/**
 * 一级文件列表单元格渲染器（F-44-3；T-LogListFreeze-04 弃 HTML 通道）：
 * 文件名（加粗）+ 文件大小 | SQL 条数（灰），SimpleColoredComponent 片段渲染。
 * internal 供 UI 测试断言文件名/大小/条数渲染与文件大小格式化边界。
 */
internal class HistoryFileCellRenderer : JPanel(BorderLayout()), javax.swing.ListCellRenderer<Any?> {
    /** 渲染行（internal 供 UI 测试遍历片段断言文本）。 */
    internal val line = SimpleColoredComponent()

    init {
        layout = BorderLayout()
        isOpaque = true
        line.isOpaque = false
        add(line, BorderLayout.CENTER)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            javax.swing.BorderFactory.createEmptyBorder(5, 8, 5, 8),
        )
    }

    override fun getListCellRendererComponent(
        list: javax.swing.JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        background = if (isSelected) list?.selectionBackground else list?.background
        line.foreground = if (isSelected) (list?.selectionForeground ?: JBColor.foreground())
        else (list?.foreground ?: JBColor.foreground())
        line.clear()
        val fe = value as? HistoryFileEntry
        if (fe != null) {
            val size = formatFileSize(fe.sizeBytes)
            val sqlCount = MpSqlVisualizerBundle.message("log.history.sqlCount", fe.sqlCount.toString())
            line.icon = AllIcons.FileTypes.Text
            line.append(fe.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            line.append("  $size | $sqlCount ", HISTORY_META_GRAY)
            // 文件条目少、低频，tooltip（完整路径）保留；条目级 tooltip 卡顿根因在二级 SQL 列表（T-ClickPreview）
            toolTipText = fe.file.absolutePath
        }
        return this
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.ENGLISH, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(Locale.ENGLISH, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

/**
 * 二级 SQL 列表单元格渲染器（F-44-2；T-LogListFreeze-04 弃 HTML 通道）：
 * 双 [SimpleColoredComponent] 两行结构（T-TwoLine 语义保留）：元信息行在上（[耗时] [时间戳] [来源]，
 * 均灰 `#9E9E9E` 以与 SQL 视觉分层），SQL 高亮行在下（[SqlListFragmentRenderer] 分词着色）。
 * 原 JLabel-HTML 通道每次 setText 触发 BasicHTML 解析排版 + 可变行高逐条测量 = 列表卡顿根因。
 * internal 供 UI 测试断言各种条目状态（空耗时/空源码/特殊字符/超长 SQL）下的渲染健壮性。
 */
internal class HistoryCellRenderer : JPanel(BorderLayout()), javax.swing.ListCellRenderer<Any?> {
    /** 元信息行（internal 供 UI 测试遍历片段断言）。 */
    internal val metaLine = SimpleColoredComponent()

    /** SQL 行组件池（T-ListMode：单行模式恒 1 行；多行模式按变换后行数伸缩）。 */
    private val sqlLinePool = mutableListOf(SimpleColoredComponent())

    /** SQL 首行（SINGLE_LINE 恒为唯一 SQL 行；测试断言兼容单行语义）。 */
    internal val sqlLine: SimpleColoredComponent get() = sqlLinePool[0]

    /** 全部 SQL 行（internal 供 UI 测试按行断言多行模式渲染）。 */
    internal val sqlLines: List<SimpleColoredComponent> get() = sqlLinePool

    private val linesPanel = JPanel(GridLayout(0, 1)).apply { isOpaque = false }

    /**
     * T-CrudColor：左侧类型色条（色条样式下着色可见；其他样式 `isOpaque=false` 但仍占位——
     * 占位是刻意的：切换样式时文本左缘不位移，且左内边距 5 + 色条 3 = 原 8）。
     */
    internal val colorBar = JPanel().apply {
        preferredSize = Dimension(SqlListTypeColor.BAR_WIDTH, 1)
        isOpaque = false
    }

    /** Border（分隔线 + 内边距；T-UN-09 描边退役后选中态同样只用它，选中视觉归平台绘制）。 */
    internal val normalBorder: Border = BorderFactory.createCompoundBorder(
        BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
        javax.swing.BorderFactory.createEmptyBorder(5, 5, 5, 8), // 左 5 + 色条 3 = 原 8
    )

    init {
        layout = BorderLayout()
        isOpaque = true
        metaLine.isOpaque = false
        sqlLinePool[0].isOpaque = false
        linesPanel.add(metaLine)
        linesPanel.add(sqlLinePool[0])
        // T-CrudColor：色条挂 WEST（CENTER 已被组件池面板占用）
        add(colorBar, BorderLayout.WEST)
        add(linesPanel, BorderLayout.CENTER)
        border = normalBorder
    }

    override fun getListCellRendererComponent(
        list: javax.swing.JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val entry = value as? LogSqlEntry
        // T-CrudColor：标记形态分派（与日志页共用 applyCrudColorMark，语义完全一致）
        val tint = entry?.let { SqlListTypeColor.colorOf(it.statementType) }
        // T-UN-09：选中描边退役后，三种样式的选中行统一走平台选中填充 → 前景按选中态取
        // selectionForeground（与日志页 LogEntryListCellRenderer 同款）
        val useSelectionFill = isSelected
        applyCrudColorMark(this, colorBar, normalBorder, tint, isSelected, list)

        val fg = if (useSelectionFill) (list?.selectionForeground ?: JBColor.foreground())
        else (list?.foreground ?: JBColor.foreground())
        metaLine.foreground = fg
        metaLine.clear()
        val lineFragments = if (entry != null) {
            val duration = entry.executionTimeMs?.let { "${it}ms" } ?: "—"
            val source = when {
                SourceMarkers.isMpPaginationCount(entry.sourceFile) -> "MP 分页计数"
                entry.sourceFile != null -> {
                    // F-44-7：标记路径始终使用正斜杠（与 CallerSourceLocator 一致），不能用 File.separatorChar
                    val name = entry.sourceFile.substringAfterLast('/')
                    if (entry.sourceLine > 0) "$name:${entry.sourceLine}" else name
                }
                else -> "unknown"
            }
            // T-TwoLine：元信息行在上（uniform 灰——不按耗时变色避免与 SQL 高亮冲突），SQL 高亮行在下
            // T-LogHistoryRowNo：行号片段在最前（0 = 无行号——旧文件/未注入条目；与日志页守卫一致）
            if (entry.sequenceNumber > 0) {
                metaLine.append("#${entry.sequenceNumber} ", HISTORY_META_GRAY)
            }
            metaLine.append("[$duration] ", HISTORY_META_GRAY)
            // T-JdbcThreadBucket F3 + 日志时间：跟随设置（对齐日志页 formatTimestampFragment），
            // 含非法 pattern 回退默认、显示开关与时间来源（日志时间/捕获时间）语义
            formatTimestampFragment(entry)?.let { (t, a) -> metaLine.append(t, a) }
            metaLine.append("[$source] ", HISTORY_META_GRAY)
            sqlLineFragmentsFor(entry)
        } else {
            null
        }
        // T-ListMode：SQL 行数随显示模式伸缩；T-ClickPreview 不设 toolTipText（预览改单击触发）
        ensureSqlLineCount(if (lineFragments != null) lineFragments.size else 1)
        sqlLinePool.forEachIndexed { i, comp ->
            comp.foreground = fg
            comp.clear()
            lineFragments?.getOrNull(i)?.forEach { f -> comp.append(f.text, f.attributes) }
        }
        return this
    }

    /** SQL 行组件池伸缩到 n 行（首位恒保留——[sqlLine] 测试缝；收缩移除末尾）。 */
    private fun ensureSqlLineCount(n: Int) {
        while (sqlLinePool.size < n) {
            val comp = SimpleColoredComponent()
            comp.isOpaque = false
            sqlLinePool += comp
            linesPanel.add(comp)
        }
        while (sqlLinePool.size > maxOf(1, n)) {
            linesPanel.remove(sqlLinePool.removeAt(sqlLinePool.size - 1))
        }
    }

    /**
     * SQL 行片段（T-ListMode 模式分流，与 [LogEntryListCellRenderer] 同款）：
     * SINGLE_LINE 直渲染不缓存；多行模式走条目级缓存（键含模式自动失效）。
     * 历史条目为每次加载新建的实例，缓存随条目生命周期天然失效。
     */
    private fun sqlLineFragmentsFor(entry: LogSqlEntry): List<List<SqlTextFragment>> {
        val sql = withTerminator(entry.summary)
        val mode = PluginSettings.getInstance().sqlListDisplayMode()
        if (mode == SqlListDisplayMode.SINGLE_LINE) {
            return listOf(SqlListFragmentRenderer.renderFragments(sql))
        }
        entry.cachedListLineFragments
            ?.takeIf { entry.cachedListLineFragmentsMode == mode }
            ?.let { return it }
        return SqlListFragmentRenderer.renderLineFragments(sql, mode).also {
            entry.cachedListLineFragments = it
            entry.cachedListLineFragmentsMode = mode
        }
    }
}
