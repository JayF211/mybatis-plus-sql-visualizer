package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.SearchTextField
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
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
    private val fileList = JBList<HistoryFileEntry>(fileListModel)
    private var allFiles: List<HistoryFileEntry> = emptyList()

    // ---------- 二级：SQL 列表（复用 SqlEntryList——Ctrl+C 复制纯 SQL 链路） ----------
    private val sqlListModel = DefaultListModel<LogSqlEntry>()
    private val sqlList = SqlEntryList(sqlListModel)
    private var currentFile: HistoryFileEntry? = null
    private var currentEntries: List<LogSqlEntry> = emptyList()

    // ---------- T-Perf-05：后台任务代际 + 搜索防抖 ----------

    /** 后台刷新/加载代际：新任务启动即作废旧任务的 EDT apply。 */
    private var bgGeneration = 0

    /** T-Perf-05：搜索防抖（每击键 O(N) clear+add → 停顿 200ms 应用一次）。 */
    private val searchDebounceTimer =
        javax.swing.Timer(SEARCH_DEBOUNCE_MS) { applySearch() }.apply { isRepeats = false }

    private val searchField = SearchTextField()
    private val backButton = JButton().apply {
        icon = AllIcons.Actions.Back
        toolTipText = MpSqlVisualizerBundle.message("log.history.back.tip")
        isFocusable = false
        preferredSize = Dimension(28, 28)
        border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
        addActionListener { showFileList() }
        isVisible = false // 默认一级，不显示返回
    }
    private val refreshButton = JButton().apply {
        icon = AllIcons.Actions.Refresh
        toolTipText = MpSqlVisualizerBundle.message("log.history.refresh.tip")
        isFocusable = false
        preferredSize = Dimension(28, 28)
        border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
        addActionListener { refresh() }
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

    init {
        buildUI()
        refresh()
    }

    private fun buildUI() {
        // 顶部工具栏：返回（仅二级可见）+ 刷新 + 搜索
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        toolbar.add(backButton)
        toolbar.add(refreshButton)
        searchField.textEditor.emptyText.text = MpSqlVisualizerBundle.message("log.history.search.files")
        searchField.textEditor.minimumSize = Dimension(200, searchField.textEditor.minimumSize.height)
        searchField.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = scheduleSearch()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = scheduleSearch()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = scheduleSearch()
        })
        toolbar.add(searchField)
        add(toolbar, BorderLayout.NORTH)

        // 一级：文件列表
        fileList.emptyText.text = MpSqlVisualizerBundle.message("log.history.files.empty")
        fileList.fixedCellHeight = -1
        fileList.cellRenderer = HistoryFileCellRenderer()
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
        sqlList.fixedCellHeight = -1
        sqlList.cellRenderer = HistoryCellRenderer()
        sqlList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
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
        backButton.isVisible = false
        searchField.textEditor.emptyText.text = MpSqlVisualizerBundle.message("log.history.search.files")
        cardLayout.show(cards, CARD_FILES)
        applyFileSearch()
    }

    private fun showFileContent(fileEntry: HistoryFileEntry) {
        currentFile = fileEntry
        backButton.isVisible = true
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
        backButton.isVisible = true
        searchField.textEditor.emptyText.text = MpSqlVisualizerBundle.message("log.history.search.empty")
        cardLayout.show(cards, CARD_SQL)
        currentEntries = SqlLogFileReader.readFromFile(fileEntry.file)
        applySqlSearch()
    }

    // ---------- 刷新 ----------

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
        backButton.isVisible = false
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

    /** 二级：按 SQL 内容 / 源文件过滤。 */
    private fun applySqlSearch() {
        val query = searchField.text.trim().lowercase()
        sqlListModel.clear()
        val filtered = if (query.isEmpty()) {
            currentEntries
        } else {
            currentEntries.filter { entry ->
                entry.restoredSql.lowercase().contains(query) ||
                    entry.sourceFile?.lowercase()?.contains(query) == true
            }
        }
        filtered.forEach { sqlListModel.addElement(it) }
    }

    private fun getHistoryDir(): String {
        val basePath = project.basePath ?: return ""
        return "$basePath${File.separator}.idea${File.separator}${SqlLogAutoSaver.DATA_DIR_NAME}${File.separator}sql-history"
    }

    private fun openPreview(entry: LogSqlEntry) {
        val base = withTerminator(entry.restoredSql)
        SqlPreviewService.showResult(
            project,
            cn.song.mybatisplus.visualizer.model.PreviewResult(
                sql = base,
                compactSql = entry.restoredSql,
                unresolvedParams = emptyList(),
                paramTable = null,
                wrapperType = null,
                tableName = null,
                warning = null,
            ),
        )
    }

    // ---------- 二级右键菜单 ----------

    private fun showSqlContextMenu(e: MouseEvent) {
        val idx = sqlList.locationToIndex(e.point)
        if (idx < 0) return
        sqlList.selectedIndex = idx
        val entry = sqlList.model.getElementAt(idx) ?: return

        val menu = JPopupMenu()
        menu.add(JMenuItem(MpSqlVisualizerBundle.message("log.history.copy"), AllIcons.Actions.Copy).apply {
            addActionListener {
                val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(java.awt.datatransfer.StringSelection(withTerminator(entry.restoredSql)), null)
            }
        })
        menu.add(JMenuItem(MpSqlVisualizerBundle.message("log.history.jumpToSource"), AllIcons.Actions.EditSource).apply {
            addActionListener { jumpToSource(entry) }
        })
        menu.show(sqlList, e.x, e.y)
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
     * 通过 sourceFile + sourceLine 直接定位（F-44 新格式）。
     */
    private fun jumpToSource(entry: LogSqlEntry) {
        when (val target = resolveJumpTarget(entry)) {
            is HistoryJumpTarget.NoSource -> {
                // 缺源码位置：旧版 F-43 保存的条目无 sourceFile，提示无法跳转
                Messages.showInfoMessage(
                    project,
                    MpSqlVisualizerBundle.message("log.history.jumpToSource.noSource"),
                    MpSqlVisualizerBundle.message("log.history.jumpToSource"),
                )
            }
            is HistoryJumpTarget.NotFound -> {
                // 文件不存在时提示（项目结构可能已变化）
                Messages.showInfoMessage(
                    project,
                    MpSqlVisualizerBundle.message("log.history.jumpToSource.notFound", target.sourceFile),
                    MpSqlVisualizerBundle.message("log.history.jumpToSource"),
                )
            }
            is HistoryJumpTarget.Navigate -> {
                OpenFileDescriptor(project, target.virtualFile, target.line, 0).navigate(true)
            }
        }
    }

    override fun dispose() {
        // T-Perf-05：面板销毁停止防抖 Timer（防晚到 apply 触碰已销毁面板）
        searchDebounceTimer.stop()
    }

    companion object {
        private val LOG = Logger.getInstance(HistorySqlLogPanel::class.java)
        private const val CARD_FILES = "files"
        private const val CARD_SQL = "sql"

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

/**
 * 一级文件列表单元格渲染器（F-44-3）：文件名（加粗）+ 文件大小 | SQL 条数（灰）。
 * internal 供 UI 测试断言文件名/大小/条数渲染与文件大小格式化边界。
 */
internal class HistoryFileCellRenderer : javax.swing.DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: javax.swing.JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        val fe = value as? HistoryFileEntry
        if (fe != null) {
            val size = formatFileSize(fe.sizeBytes)
            val sqlCount = MpSqlVisualizerBundle.message("log.history.sqlCount", fe.sqlCount.toString())
            this.text = "<html><b>${escapeHtml(fe.displayName)}</b>  " +
                "<span style='color:#9E9E9E'>$size | $sqlCount</span></html>"
            toolTipText = fe.file.absolutePath
            icon = AllIcons.FileTypes.Text
            foreground = if (isSelected) (list?.selectionForeground ?: JBColor.foreground())
            else (list?.foreground ?: JBColor.foreground())
        }
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            javax.swing.BorderFactory.createEmptyBorder(5, 8, 5, 8),
        )
        return this
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.ENGLISH, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(Locale.ENGLISH, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

/**
 * 二级 SQL 列表单元格渲染器（F-44-2）：耗时 + 执行时间 + 源码位置 + SQL 摘要。
 * SQL 摘要走 [SqlListHtmlRenderer] 高亮（关键字/表名/字符串/数字分色）；
 * 耗时/时间戳/源码走灰色 `#9E9E9E` 以与 SQL 视觉分层。
 * internal 供 UI 测试断言各种条目状态（空耗时/空源码/特殊字符/超长 SQL）下的渲染健壮性。
 */
internal class HistoryCellRenderer : javax.swing.DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: javax.swing.JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        val entry = value as? LogSqlEntry
        if (entry != null) {
            // T-Perf-05：HTML 缓存照抄 SqlLogPanel cachedListHtml 方案——滚动时每可见行只渲染一次
            // （此前每次滚动重跑 SqlListHtmlRenderer 词法高亮 + BasicHTML 解析，长列表持续高 CPU）。
            // 历史条目为每次加载新建的实例，缓存随条目生命周期天然失效。
            val html = entry.cachedListHtml ?: run {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date(entry.timestamp))
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
                val sqlHtml = SqlListHtmlRenderer.render(withTerminator(entry.summary))
                "<html><span style='color:#9E9E9E'>[$duration]</span> " +
                    "<span style='color:#9E9E9E'>[$timestamp]</span> " +
                    "<span style='color:#9E9E9E'>[$source]</span> $sqlHtml</html>"
            }.also { entry.cachedListHtml = it }
            this.text = html
            toolTipText = entry.restoredSql
            foreground = if (isSelected) (list?.selectionForeground ?: JBColor.foreground())
            else (list?.foreground ?: JBColor.foreground())
        }
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            javax.swing.BorderFactory.createEmptyBorder(5, 8, 5, 8),
        )
        return this
    }
}
