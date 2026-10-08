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
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.speedSearch.SpeedSearchUtil
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
 * 历史 SQL 面板（F-44-3 两级导航）：读取自动保存目录中的历史文件展示
 * （F-44-4：目录随插件更名，旧名 mybatis-plus-sql-visualizer 不再读取）。
 *
 * <p>目录解析与写端同源（[SqlLogAutoSaver.resolveHistoryDir]）——设置页「保存目录」填了自定义路径时，
 * 历史页读的就是同一个目录（T-HistoryTabAlways 前读端只认默认 .idea 路径，自定义目录下写的文件看不到）。
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
    /** 二级速度搜索是否已安装。构造于 CardLayout 隐藏卡时安装不生效（真机 key 流不达、焦点不在），
     *  故延后到 [showFileContent]/[loadFileContentSync] 切卡可见后安装（T-UiNative T-UN-05）。 */
    private var sqlSpeedSearchInstalled = false
    /** T-ClickPreview：单击出轻量预览弹窗（点击时按行捕获 SQL，锚点在行下缘不遮行），双击走 openPreview 完整弹窗；悬停 tooltip 已移除（卡顿根因）。
     *  T-ListPreviewCopy：预览文本经 clickPreviewSql——设置「预览时格式化还原 SQL」开启（默认）时多行格式化显示。 */
    private val sqlClickPreview = SqlClickPreview(project, sqlList) {
        i -> clickPreviewSql(sqlList.model.getElementAt(i).restoredSql, PluginSettings.getInstance().state.logFormatSql)
    }
    private var currentFile: HistoryFileEntry? = null
    private var currentEntries: List<LogSqlEntry> = emptyList()

    /** T-SqlLogPerf：二级列表滚动宿主（构造期装配 adjustment 监听驱动滚动追加）。 */
    private lateinit var sqlScrollPane: JBScrollPane

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
     * 设置变更处理（topic 监听入口，T-HistoryTabAlways）：解析出的历史目录与上次扫描所用不同时
     * 直接重扫（用户在设置页改了「保存目录」，不重扫就还得手动点刷新），未变时只重算显示模式并重绘
     * （原 [onDisplayModeChanged] 行为）。
     */
    internal fun onSettingsChanged() {
        if (historyDirChangedSinceLastScan()) refresh() else onDisplayModeChanged()
    }

    /** T-HistoryTabAlways：解析出的历史目录是否与上次扫描所用不同（设置页改了「保存目录」）。
     *  测试直接断言本判据——[refresh] 是后台任务，headless 下到点不可控。 */
    internal fun historyDirChangedSinceLastScan(): Boolean = getHistoryDir() != lastScannedDir

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
                // write-safe: UI-only（纯 Swing/只读，无文档写）
                ApplicationManager.getApplication().invokeLater(
                    { onSettingsChanged() },
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
                // T-ClickPreviewCopyRestore：修饰键一并转发——Ctrl/Shift 单击（多选第一击）不排程预览
                if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    sqlClickPreview.onClick(e.clickCount, e.point, e.modifiersEx)
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
        // T-SqlLogPerf：二级列表滚动追加——滚动条逼近底部时续写下一批（fillSqlList 只写首屏）
        sqlScrollPane = JBScrollPane(sqlList)
        sqlScrollPane.verticalScrollBar.addAdjustmentListener { maybeAppendOnScroll() }
        cards.add(sqlScrollPane, CARD_SQL)
        add(cards, BorderLayout.CENTER)
    }

    // ---------- 视图切换 ----------

    private fun showFileList() {
        currentFile = null
        currentFileStamp = null
        currentEntries = emptyList()
        sqlClickPreview.hide() // 返回一级时若二级预览弹窗还开着，一并关掉
        refreshNavToolbar() // T-UN-10：Back 可见性随层级即时刷新（原 backButton.isVisible = false）
        searchField.textEditor.emptyText.text = MpSqlVisualizerBundle.message("log.history.search.files")
        cardLayout.show(cards, CARD_FILES)
        applyFileSearch()
    }

    private fun showFileContent(fileEntry: HistoryFileEntry) {
        currentFile = fileEntry
        // T-UiNative T-UN-05：二级 SQL 列表切卡可见后重挂速度搜索（构造于隐藏卡时 installOn
        // 仍不生效的兜底；标记防重复装——平台 installOn 重复挂载同一列表不幂等）
        if (!sqlSpeedSearchInstalled) {
            sqlSpeedSearchInstalled = true
            ListSpeedSearch.installOn(sqlList) {
                entry -> withTerminator(entry.restoredSql)
            }
        }
        refreshNavToolbar() // T-UN-10：Back 可见性随层级即时刷新（原 backButton.isVisible = true）
        searchField.textEditor.emptyText.text = MpSqlVisualizerBundle.message("log.history.search.sql")
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
                    // T-SqlLogPerf：记录加载完成时的文件快照——refresh() 据此免掉未变文件的全量重读
                    currentFileStamp = fileEntry.file.lastModified() to fileEntry.file.length()
                    applySqlSearch()
                    LOG.info("HistorySqlLogPanel.showFileContent: ${fileEntry.file.name} 共 ${entries.size} 条 SQL")
                }
            }
        }.queue()
    }

    /** 测试缝：同步加载文件内容（生产路径 showFileContent 已后台化，headless 测试需要确定性）。 */
    private fun loadFileContentSync(fileEntry: HistoryFileEntry) {
        currentFile = fileEntry
        // T-UiNative T-UN-05：二级 SQL 列表可见后确保速度搜索安装（生产 showFileContent 已装，
        // 此同步路径 headless 下 CardLayout 不见真机 showing，marker 判定可能不触发，故统一补装）
        if (!sqlSpeedSearchInstalled) {
            sqlSpeedSearchInstalled = true
            ListSpeedSearch.installOn(sqlList) {
                entry -> withTerminator(entry.restoredSql)
            }
        }
        refreshNavToolbar() // T-UN-10：Back 可见性随层级即时刷新（原 backButton.isVisible = true）
        searchField.textEditor.emptyText.text = MpSqlVisualizerBundle.message("log.history.search.sql")
        cardLayout.show(cards, CARD_SQL)
        currentEntries = SqlLogFileReader.readFromFile(fileEntry.file)
        currentFileStamp = fileEntry.file.lastModified() to fileEntry.file.length()
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
     *
     * T-SqlLogPerf（20261006-1020 计划 P1）：浏览态刷新不再**无条件**全量重读当前文件——
     * 仅当 (lastModified, length) 与加载完成时快照 [currentFileStamp] 不一致（自动保存确有追加）
     * 才重读；未变则后台只做目录扫描 + countInFile（有缓存）。日常 IDE 实测 19MB/8708 条文件
     * 一次浏览窗口内被 refresh + showFileContent 全量解析约 3 遍（15~16.6s），此处消除 refresh 侧的重复成本。
     */
    fun refresh() {
        // T-HistoryTabAlways：记录本次扫描所用目录（EDT 侧纯字段写），供设置变更时判定要不要重扫
        lastScannedDir = getHistoryDir()
        val gen = ++bgGeneration
        val keepFile = currentFile?.file
        object : Task.Backgroundable(project, MpSqlVisualizerBundle.message("log.history.scan.progress"), true) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                val scanned = scanHistoryFiles(getHistoryDir())
                val keepReload = keepFile?.takeIf { it.exists() }?.let { f ->
                    val stamp = f.lastModified() to f.length()
                    // currentFileStamp 为 EDT 写的 Swing 字段，此处读只做「是否变化」启发判定：
                    // 竞态最坏结果是多读一次文件，无正确性风险（读到 null 视为变化 → 重读，行为同旧版）
                    if (stamp == currentFileStamp) null else SqlReload(f, SqlLogFileReader.readFromFile(f), stamp)
                }
                ApplicationManager.getApplication().invokeLater {
                    if (gen != bgGeneration || project.isDisposed) return@invokeLater
                    applyRefresh(scanned, keepFile, keepReload)
                }
            }
        }.queue()
    }

    /** 测试缝：同步刷新（生产路径 refresh() 已后台化；headless 测试需要确定性）。 */
    internal fun refreshForTest() {
        ++bgGeneration // 作废在途后台 apply（构造期 refresh() 的后台扫描可能尚未完成）
        val dir = getHistoryDir()
        lastScannedDir = dir
        val keepFile = currentFile?.file
        val keepReload = keepFile?.takeIf { it.exists() }?.let {
            SqlReload(it, SqlLogFileReader.readFromFile(it), it.lastModified() to it.length())
        }
        applyRefresh(scanHistoryFiles(dir), keepFile, keepReload)
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

    /** T-SqlLogPerf：浏览文件重载结果包（条目 + 读取完成时的 (lastModified, length) 快照）。 */
    private data class SqlReload(val file: File, val entries: List<LogSqlEntry>, val stamp: Pair<Long, Long>)

    /** 应用扫描结果（EDT；仅刷新/加载代际最新者执行）。 */
    private fun applyRefresh(scanned: List<HistoryFileEntry>, keepFile: File?, keepReload: SqlReload?) {
        allFiles = scanned
        LOG.info("HistorySqlLogPanel.refresh: 扫描到 ${allFiles.size} 个历史文件")

        // 保持二级视图：当前文件仍存在则保留二级
        if (keepFile != null && keepFile.exists()) {
            // 仅文件真有变化（快照不一致）才替换解析结果；未变时 currentEntries/滚动位置原样保留
            if (keepReload != null) {
                currentEntries = keepReload.entries
                currentFileStamp = keepReload.stamp
                applySqlSearch()
            }
            return
        }
        // 否则回一级（默认展示文件列表）
        currentFile = null
        currentFileStamp = null
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
     * 同款语义：行号 `#5`/`5` 精确 equals、时间按显示片段 contains（所见即所搜，开关关闭不参与）；
     * 源文件为历史页特有检索目标，保留独立分支。
     */
    private fun applySqlSearch() {
        val q = searchField.text.trim()
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
        fillSqlList(filtered)
    }

    /**
     * T-SqlHistoryFull → T-SqlLogPerf 演进（20261006-1020 计划 P3/P4）：二级列表**滚动追加**填充。
     *
     * 旧版：首屏 30 条立即写入 + 剩余每 30 条 invokeLater 链式追加——8708 条文件排 290 个 EDT 脉冲，
     * 无论用户看多少都全量进模型。改为：首屏 30 条立即写入，其余挂 [pendingEntries]，用户滚动
     * 逼近列表底部（[maybeAppendOnScroll]）时才续写下一批（每批 [REFRESH_CHUNK_SIZE] 条，
     * 滚动条 max 变更会再触发 adjustment 事件自然节流）——大文件只付出「用户真正浏览到」的成本。
     * gen 代际守卫保留：新一轮 fillSqlList（重新加载/搜索/刷新）启动即作废旧待写队列。
     */
    private fun fillSqlList(entries: List<LogSqlEntry>) {
        val gen = ++bgGeneration
        pendingEntries = emptyList()
        pendingAppendIndex = 0
        pendingAppendGen = gen
        LogPerfProbe.measure("History.fillSqlList", { "${entries.size} 条" }) {
            sqlList.ignoreRepaint = true
            sqlListModel.clear()
            if (entries.isNotEmpty()) {
                sqlListModel.addAll(entries.take(REFRESH_FIRST_BATCH_SIZE))
            }
            sqlList.ignoreRepaint = false
            sqlList.revalidate()
            sqlList.repaint()
        }
        if (entries.size > REFRESH_FIRST_BATCH_SIZE) {
            pendingEntries = entries
            pendingAppendIndex = REFRESH_FIRST_BATCH_SIZE
        }
        maybeAppendOnScroll()
    }

    /** 滚动追加触发窗口：视口底沿距列表底沿小于该像素即续写下一批（约半屏，提前装载避免跟随感断裂）。 */
    private val APPEND_TRIGGER_PIXELS = 480

    /** 滚动逼近底部时续写待写条目（adjustment 事件 + fillSqlList 尾部各调一次；一次最多一批，靠滚动条 max 变更再触发）。 */
    private fun maybeAppendOnScroll() {
        if (!sqlList.isShowing) return // 未显示（headless/隐藏卡）不自动续写——测试经 [appendPendingForTest] 驱动
        appendPendingIfNearBottom()
    }

    /** 测试缝：headless 下无真实滚动事件，直接驱动滚动追加（与 adjustment 监听同一实现路径）。 */
    internal fun appendPendingForTest() {
        appendPendingIfNearBottom()
    }

    private fun appendPendingIfNearBottom() {
        if (pendingAppendIndex >= pendingEntries.size) return
        val bar = sqlScrollPane?.verticalScrollBar
        if (bar != null) {
            val distance = bar.maximum - (bar.value + bar.visibleAmount)
            if (distance > APPEND_TRIGGER_PIXELS) return
        }
        if (pendingAppendGen != bgGeneration || project.isDisposed) {
            pendingEntries = emptyList()
            return
        }
        val end = minOf(pendingEntries.size, pendingAppendIndex + REFRESH_CHUNK_SIZE)
        sqlListModel.addAll(pendingEntries.subList(pendingAppendIndex, end))
        pendingAppendIndex = end
        if (pendingAppendIndex >= pendingEntries.size) pendingEntries = emptyList()
    }

    /** 滚动追加待写队列（fillSqlList 写完首屏后的剩余条目；追加完毕置空）。 */
    private var pendingEntries: List<LogSqlEntry> = emptyList()

    /** 待写队列下一位游标（避免每批 drop() 拷贝余量）。 */
    private var pendingAppendIndex: Int = 0

    /** 待写队列所属代际（滚动回调校验，旧代际队列作废）。 */
    private var pendingAppendGen: Int = 0

    /** T-SqlLogPerf：当前浏览文件加载完成时的 (lastModified, length) 快照——refresh() 据此判定文件是否变化。 */
    private var currentFileStamp: Pair<Long, Long>? = null

    /** T-HistoryTabAlways：上次扫描所用目录——设置变更时据此判定是否需要重扫（保存目录被改过）。 */
    private var lastScannedDir: String = ""

    /** 历史 SQL 目录：与写端同源解析（自定义保存目录优先；无项目根回落 user.home）。 */
    private fun getHistoryDir(): String =
        SqlLogAutoSaver.resolveHistoryDir(PluginSettings.getInstance().state.logAutoSavePath, project.basePath)

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
        // T-M55-01：拆「跳转到 XML」双目标（与日志页同套）
        group.add(JumpToMapperXmlAction(entry))
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

    /** 跳转到 Java 调用方（T-M55-01：与「跳转到 XML」拆分为双目标，与日志页同套）。 */
    private inner class JumpToSourceAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.Actions.EditSource
            e.presentation.text = MpSqlVisualizerBundle.message("log.history.jumpToJava")
        }

        override fun actionPerformed(e: AnActionEvent) {
            jumpToSource(entry)
        }
    }

    /**
     * T-M55-01：跳转到 mapper XML 语句节点。mapperStatement 非空且定位成功才显示
     * （update 内解析一次并存引用）；定位失败不显示，「跳转到 Java」承担回退，不劣化。
     */
    private inner class JumpToMapperXmlAction(private val entry: LogSqlEntry) : AnAction() {
        private var resolved: HistoryJumpTarget.MapperXml? = null

        override fun update(e: AnActionEvent) {
            e.presentation.icon = AllIcons.FileTypes.Xml
            e.presentation.text = MpSqlVisualizerBundle.message("log.history.jumpToXml")
            resolved = resolveXmlJumpTarget(entry)
            e.presentation.isEnabledAndVisible = resolved != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val target = resolved ?: resolveXmlJumpTarget(entry) ?: return
            OpenFileDescriptor(project, target.virtualFile, target.line, 0).navigate(true)
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
        /** T-M55-01：mapper XML 语句节点（mapperStatement 定位成功才有意义，失败回退 Java 不劣化）。 */
        data class MapperXml(val virtualFile: com.intellij.openapi.vfs.VirtualFile, val line: Int) : HistoryJumpTarget()
    }

    /**
     * 解析跳转目标（与 [jumpToSource] 共用；测试驱动直接断言分支，不触发弹窗）。
     * T-SourceJumpFunnel P0-5：判定收敛到 [EntryJumpResolver]（与实时面板同一实现）——
     * 主位置不在工程内（自研框架汇聚门面帧）时按候选栈序回退，本面板此前无此能力。
     * T-RecentExecMenu O-119：转接原语收口 [LogEntryJumpOps]（三处共用）；**本页策略**=
     * `MissingSource` 并入 `NoSource`（走引导弹窗，与实时页的轻通知不同体，各自保留）。
     */
    internal fun resolveJumpTarget(entry: LogSqlEntry): HistoryJumpTarget =
        when (val target = LogEntryJumpOps.resolveSource(project, entry)) {
            is LogEntryJumpOps.SourceTarget.Precise ->
                HistoryJumpTarget.Navigate(target.virtualFile, target.line)
            LogEntryJumpOps.SourceTarget.NoSource -> HistoryJumpTarget.NoSource
            LogEntryJumpOps.SourceTarget.MissingSource -> HistoryJumpTarget.NoSource
            is LogEntryJumpOps.SourceTarget.NotFound -> HistoryJumpTarget.NotFound(target.sourceFile)
        }

    /**
     * T-M55-01：XML 目标解析——与日志页 [SqlLogPanel.resolveEntryXmlJump] 同语义。
     * mapperStatement 非空且 [MapperXmlLocator] 定位成功才返回；否则 null（回退 Java，不劣化）。
     * T-RecentExecMenu O-119：解析原语收口 [LogEntryJumpOps.resolveXml]。
     */
    internal fun resolveXmlJumpTarget(entry: LogSqlEntry): HistoryJumpTarget.MapperXml? =
        LogEntryJumpOps.resolveXml(project, entry)
            ?.let { HistoryJumpTarget.MapperXml(it.virtualFile, it.line) }

    /**
     * 跳转到产生该 SQL 的源码位置（测试驱动；等价右键「跳转到源码」）。
     * 通过 sourceFile + sourceLine 直接定位（F-44 新格式）。
     */
    internal fun jumpToSourceForTest(entry: LogSqlEntry) = jumpToSource(entry)

    /**
     * 跳转到产生该 SQL 的源码位置。
     * 通过 sourceFile + sourceLine 直接定位（F-44 新格式）；
     * T-JumpCapture：无捕获位置时弹框引导开启源码捕获拦截器。
     * T-RecentExecMenu O-119：解析/导航/通知原语走 [LogEntryJumpOps]（本页策略保持原样：
     * NoSource/MissingSource 引导弹窗、NotFound 轻通知，标题键为本页口径 `log.history.*`）。
     */
    private fun jumpToSource(entry: LogSqlEntry) {
        when (val target = LogEntryJumpOps.resolveSource(project, entry)) {
            // 缺源码位置（旧版 F-43 条目 / 未启用拦截器捕获）：引导开启源码捕获（T-JumpCapture）
            LogEntryJumpOps.SourceTarget.NoSource,
            LogEntryJumpOps.SourceTarget.MissingSource,
            -> LogEntryJumpOps.showMissingSourceGuide(project)

            is LogEntryJumpOps.SourceTarget.NotFound -> {
                // 文件不存在时提示（项目结构可能已变化）；T-UiNative T-UN-02：改轻通知（原模态弹窗）
                LogEntryJumpOps.showToast(
                    project,
                    "log.history.jumpToSource",
                    MpSqlVisualizerBundle.message("log.history.jumpToSource.notFound", target.sourceFile),
                )
            }

            is LogEntryJumpOps.SourceTarget.Precise -> {
                LogEntryJumpOps.navigate(project, target.virtualFile, target.line)
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

        // T-RecentExecMenu O-119：轻提示通知组常量随跳转原语收口 [LogEntryJumpOps] 退役
        // （本页唯一使用者是原跳转通知，现经 ops.showToast 走共享组 id）。

        /** T-Perf-05：搜索输入防抖窗口（毫秒）。 */
        private const val SEARCH_DEBOUNCE_MS = 200

        /** T-SqlHistoryFull：二级列表分批填充——首屏立即写入条数（与实时面板同值，快速首屏）。 */
        private const val REFRESH_FIRST_BATCH_SIZE = 30

        /** T-SqlHistoryFull：二级列表分批填充——每块 invokeLater 追加条数（防 EDT 长冻结）。 */
        private const val REFRESH_CHUNK_SIZE = 30
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
            val source = when {
                SourceMarkers.isMpPaginationCount(entry.sourceFile) -> "MP 分页计数"
                entry.sourceFile != null -> {
                    // F-44-7：标记路径始终使用正斜杠（与 CallerSourceLocator 一致），不能用 File.separatorChar
                    val name = entry.sourceFile.substringAfterLast('/')
                    if (entry.sourceLine > 0) "$name:${entry.sourceLine}" else name
                }
                else -> "unknown"
            }
            // T-TwoLine：元信息行在上、SQL 高亮行在下。**片段与顺序与日志页完全一致**
            // （行号 → 时间 → 耗时 → 目标库 → 来源；耗时复用日志页 formatDurationFragment，
            // 按慢查询阈值着色、缺失占位「—」——勿再自拼 [xms]，两页同条目须逐字一致）
            // T-LogHistoryRowNo：行号片段在最前（0 = 无行号——旧文件/未注入条目；与日志页守卫一致）
            if (entry.sequenceNumber > 0) {
                metaLine.append("#${entry.sequenceNumber} ", HISTORY_META_GRAY)
            }
            // T-JdbcThreadBucket F3 + 日志时间：跟随设置（对齐日志页 formatTimestampFragment），
            // 含非法 pattern 回退默认、显示开关与时间来源（日志时间/捕获时间）语义
            formatTimestampFragment(entry)?.let { (t, a) -> metaLine.append(t, a) }
            val (durationText, durationAttrs) = formatDurationFragment(entry.executionTimeMs, useSelectionFill)
            metaLine.append(durationText, durationAttrs)
            // T-AgentDialectLoop：目标库角标（与日志页同款落点：时间/耗时后、来源前）
            formatDatabaseFragment(entry, useSelectionFill)?.let { (t, a) -> metaLine.append(t, a) }
            // T-McpAuditTrail T-MAT-05：MCP 徽标（与日志页同款落点与协议；非 MCP 返回 null 不显示）
            formatMcpFragment(entry, useSelectionFill)?.let { (t, a) -> metaLine.append(t, a) }
            metaLine.append("[$source] ", HISTORY_META_GRAY)
            sqlLineFragmentsFor(entry)
        } else {
            null
        }
        // T-ListMode：SQL 行数随显示模式伸缩；T-ClickPreview 不设 toolTipText（预览改单击触发）
        ensureSqlLineCount(if (lineFragments != null) lineFragments.size else 1)
        // T-UiNative speed search 高亮：对元信息行与各 SQL 行施加匹配片段高亮（同主日志列表）
        list?.let { l -> SpeedSearchUtil.applySpeedSearchHighlighting(l, metaLine, false, useSelectionFill) }
        sqlLinePool.forEachIndexed { i, comp ->
            comp.foreground = fg
            comp.clear()
            lineFragments?.getOrNull(i)?.forEach { f -> comp.append(f.text, f.attributes) }
            list?.let { l -> SpeedSearchUtil.applySpeedSearchHighlighting(l, comp, false, useSelectionFill) }
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
