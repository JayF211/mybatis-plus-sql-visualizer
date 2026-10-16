package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.io.File

/**
 * 历史面板检索「查询 → 过滤」纯逻辑测试（T-HistorySearchToggles S6）：
 *
 * `applySqlSearch` 抽缝 [filterHistoryEntries] 后的正则 / 大小写 / 前缀兼容 / 非法回退 /
 * memo 换旗全覆盖——历史正则检索路径此前零单测，形态照日志页 filterBySearch
 * （SqlLogPanelFilterTest）先例；另含面板开关接线测试（toggle 点击 → 过滤生效）。
 */
class HistorySearchFilterTest : BasePlatformTestCase() {

    private val historyDir: File
        get() = File(project.basePath!!, ".idea${File.separator}${SqlLogAutoSaver.DATA_DIR_NAME}${File.separator}sql-history")

    override fun setUp() {
        super.setUp()
        historyDir.deleteRecursively()
    }

    private fun entry(sql: String, sourceFile: String? = null, sequenceNumber: Long = 0L) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = 0L,
        sourceFile = sourceFile,
        sequenceNumber = sequenceNumber,
    )

    /** 三条样本：大写 SELECT / 无匹配 / 小写 select——正则与大小写语义的判别基础。 */
    private val entries = listOf(
        entry("SELECT * FROM user WHERE id = 1", sourceFile = "UserMapper.java:10", sequenceNumber = 1L),
        entry("UPDATE orders SET status = 'PAID'", sourceFile = "OrderMapper.java:5", sequenceNumber = 2L),
        entry("select 1", sourceFile = null, sequenceNumber = 3L),
    )

    // ---------- 正则路径：前缀兼容（R16 已发行能力，不回归） ----------

    fun test正则经前缀触发_开关关闭时照旧() {
        val out = filterHistoryEntries(entries, "regex:SELECT.*user")
        assertEquals(listOf("SELECT * FROM user WHERE id = 1"), out.map { it.restoredSql })
    }

    fun test正则经开关触发_无前缀时整串按正则() {
        // 默认 matchCase=false（IGNORE_CASE）：小写 select 1 命中 pattern "SELECT \d+"
        val out = filterHistoryEntries(entries, "SELECT \\d+", regexEnabled = true)
        assertEquals(listOf("select 1"), out.map { it.restoredSql })
    }

    fun test前缀在场先剥_剥后整段才是pattern() {
        // 开关开 + 前缀在场：pattern 是 "user" 而非 "regex:user"（未剥则无一命中）
        val out = filterHistoryEntries(entries, "regex:user", regexEnabled = true)
        assertEquals(listOf("SELECT * FROM user WHERE id = 1"), out.map { it.restoredSql })
    }

    // ---------- 正则路径：大小写随开关（行为变更声明：恒敏感 → 随开关，默认关=忽略） ----------

    fun test正则大小写随开关() {
        val sensitive = filterHistoryEntries(entries, "select", regexEnabled = true, matchCase = true)
        assertEquals(listOf("select 1"), sensitive.map { it.restoredSql })
        val insensitive = filterHistoryEntries(entries, "select", regexEnabled = true, matchCase = false)
        assertEquals(2, insensitive.size)
    }

    fun test正则命中源文件目标() {
        val out = filterHistoryEntries(entries, "OrderMapper\\.java", regexEnabled = true)
        assertEquals(listOf("UPDATE orders SET status = 'PAID'"), out.map { it.restoredSql })
    }

    // ---------- 非法正则 fail-closed 回退子串（现状保持） ----------

    fun test非法正则经前缀_回退原文子串() {
        val withLiteral = entries + entry("note: regex:( kept literal")
        val out = filterHistoryEntries(withLiteral, "regex:(")
        assertEquals(listOf("note: regex:( kept literal"), out.map { it.restoredSql })
    }

    fun test非法正则经开关_回退子串() {
        val withParen = entries + entry("SELECT COUNT(*) FROM t")
        val out = filterHistoryEntries(withParen, "(", regexEnabled = true)
        assertEquals(listOf("SELECT COUNT(*) FROM t"), out.map { it.restoredSql })
    }

    // ---------- 子串路径：大小写统一由 matchCase 控制 ----------

    fun test子串大小写随开关() {
        assertEquals(1, filterHistoryEntries(entries, "USER", matchCase = false).size)
        assertEquals(0, filterHistoryEntries(entries, "USER", matchCase = true).size)
    }

    fun test子串命中源文件目标且随开关() {
        assertEquals(1, filterHistoryEntries(entries, "ordermapper", matchCase = false).size)
        assertEquals(0, filterHistoryEntries(entries, "ordermapper", matchCase = true).size)
    }

    fun test行号精确匹配不受matchCase影响() {
        assertEquals(1, filterHistoryEntries(entries, "#2", matchCase = true).size)
        assertEquals(1, filterHistoryEntries(entries, "2", matchCase = true).size)
    }

    fun test空查询透传全部() {
        assertEquals(entries, filterHistoryEntries(entries, ""))
        assertEquals(entries, filterHistoryEntries(entries, "   ", regexEnabled = true))
    }

    // ---------- memo（S3：键 = 源串 + matchCase 旗，最易埋点） ----------

    fun testresolve换旗必重编_同键复用() {
        val memo = HistoryRegexMemo()
        val ignoreCase = resolveHistoryRegex("user", matchCase = false, memo = memo)!!
        assertTrue(ignoreCase.options.contains(RegexOption.IGNORE_CASE))
        val sensitive = resolveHistoryRegex("user", matchCase = true, memo = memo)!!
        assertFalse(sensitive.options.contains(RegexOption.IGNORE_CASE))
        assertNotSame("换旗必须重编（复用即 memo 键缺旗的存量隐患）", ignoreCase, sensitive)
        assertSame("同源串同旗复用", sensitive, resolveHistoryRegex("user", matchCase = true, memo = memo))
    }

    fun testmemo换旗重编_过滤结果随旗翻转() {
        val memo = HistoryRegexMemo()
        val upper = listOf(entry("SELECT * FROM USER"))
        // 第一遍：忽略大小写编译，大写目标命中
        assertEquals(1, filterHistoryEntries(upper, "user", regexEnabled = true, matchCase = false, memo = memo).size)
        // 第二遍：同 memo 同源串、只切旗——必须重编（单字段 memo 会复用 IGNORE_CASE 编译结果而误命中）
        assertEquals(0, filterHistoryEntries(upper, "user", regexEnabled = true, matchCase = true, memo = memo).size)
    }

    fun testmemo记住非法源串_换旗换串即重试() {
        val memo = HistoryRegexMemo()
        val withLiteral = listOf(entry("regex:( literal"))
        assertEquals(1, filterHistoryEntries(withLiteral, "regex:(", memo = memo).size)
        // 同坏串同旗反复 apply：行为一致（失败被 memo，不再重复抛编译异常）
        assertEquals(1, filterHistoryEntries(withLiteral, "regex:(", memo = memo).size)
        // 换合法串即重试（正则路径命中）
        assertEquals(1, filterHistoryEntries(withLiteral, "literal", regexEnabled = true, memo = memo).size)
    }

    // ---------- 面板接线：toggle 点击 → 过滤生效 ----------

    /** NavigationTest 同款：写 F-44-2 新版格式历史文件。 */
    private fun writeHistoryFile(name: String, sqlLines: List<String>): File {
        val dir = historyDir
        dir.mkdirs()
        val content = sqlLines.joinToString("\n\n") { sql ->
            "-- source: TestMapper.java:1 | time: 3ms | 2026-10-09 12:00:00.000 --\n$sql"
        } + "\n\n"
        return File(dir, name).apply { writeText(content) }
    }

    fun test面板开关驱动二级检索() {
        writeHistoryFile(
            "sql-history-toggles.sql",
            listOf("SELECT * FROM USER WHERE id = 1", "select 1"),
        )
        val panel = HistorySqlLogPanel(project)
        panel.refreshForTest()
        panel.openFileForTest(panel.fileListEntries.single())
        try {
            // 默认（两开关均关）= 现状：子串忽略大小写，两条全命中
            panel.setSearchForTest("select")
            assertEquals(2, panel.sqlListEntries.size)

            // 点 MatchCase：子串变敏感，只剩小写条目
            panel.matchCaseToggleForTest = !panel.matchCaseToggleForTest
            assertEquals(listOf("select 1"), panel.sqlListEntries.map { it.restoredSql })

            // 点 Regex：正则开关接线生效——pattern "WHERE id = 1$" 命中第一条（子串语义下无命中）
            panel.regexToggleForTest = !panel.regexToggleForTest
            panel.setSearchForTest("WHERE id = 1$")
            assertEquals(listOf("SELECT * FROM USER WHERE id = 1"), panel.sqlListEntries.map { it.restoredSql })
        } finally {
            panel.dispose()
        }
    }

    // UI009：断言真实组件状态，不以过滤结果为零冒充「错误已经可见」。
    private fun searchOf(panel: HistorySqlLogPanel): cn.song.mybatisplus.visualizer.ui.search.NativeSearchTextField =
        HistorySqlLogPanel::class.java.getDeclaredField("nativeSearch").apply { isAccessible = true }
            .get(panel) as cn.song.mybatisplus.visualizer.ui.search.NativeSearchTextField

    private fun filesOf(panel: HistorySqlLogPanel): com.intellij.ui.components.JBList<*> =
        HistorySqlLogPanel::class.java.getDeclaredField("fileList").apply { isAccessible = true }
            .get(panel) as com.intellij.ui.components.JBList<*>

    private fun message(key: String, vararg args: Any): String =
        cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message(key, *args)

    private fun sqlEmptyText(panel: HistorySqlLogPanel): String =
        (panel.sqlListForTest as com.intellij.ui.components.JBList<*>).emptyText.text

    private fun withHistoryPanel(sql: List<String>, test: (HistorySqlLogPanel) -> Unit) {
        writeHistoryFile("sql-history-ui009.sql", sql)
        val panel = HistorySqlLogPanel(project)
        try {
            panel.refreshForTest()
            panel.openFileForTest(panel.fileListEntries.single())
            test(panel)
        } finally {
            panel.dispose()
        }
    }

    fun testUI009_七条SQL非法正则明确报错而非空文件() {
        withHistoryPanel((1..7).map { "SELECT $it" }) { panel ->
            assertEquals(7, panel.sqlListEntries.size)
            panel.regexToggleForTest = true
            panel.setSearchForTest("(")
            val search = searchOf(panel)
            val error = message("log.history.search.invalidRegex")
            assertEquals(0, panel.sqlListEntries.size)
            assertEquals(error, search.component.statusText)
            assertEquals(error, search.textEditor.toolTipText)
            assertEquals(error, sqlEmptyText(panel))
            // 右侧实际可见的标签也要报错，不能只写平台未挂载的状态区。
            val visibleStatus = com.intellij.util.ui.UIUtil.uiTraverser(search.row)
                .filter(javax.swing.JLabel::class.java).filter { it.isVisible && it.text == error }.toList()
            assertFalse("非法正则必须有可见反馈", visibleStatus.isEmpty())
            assertEquals(search.component.statusColor, visibleStatus.first().foreground)
        }
    }

    fun testUI009_非法前缀有反馈且保留子串回退() {
        withHistoryPanel(listOf("SELECT 'regex:('")) { panel ->
            panel.setSearchForTest("regex:(")
            assertEquals(1, panel.sqlListEntries.size)
            assertEquals(message("log.history.search.invalidRegex"), searchOf(panel).component.statusText)
            panel.setSearchForTest("regex:SELECT")
            assertEquals(message("log.history.search.results", 1), searchOf(panel).component.statusText)
            assertNull(searchOf(panel).textEditor.toolTipText)
        }
    }

    fun testUI009_原生正则开关立即更新错误并可恢复() {
        withHistoryPanel(listOf("SELECT COUNT(*) FROM t")) { panel ->
            panel.setSearchForTest("(")
            val search = searchOf(panel)
            assertEquals(1, panel.sqlListEntries.size)
            assertNull(search.textEditor.toolTipText)
            // 直接改原生按钮使用的 FindModel，不走会主动 apply 的测试 setter。
            search.findModel.isRegularExpressions = true
            assertEquals(message("log.history.search.invalidRegex"), search.component.statusText)
            assertEquals(1, panel.sqlListEntries.size)
            search.findModel.isRegularExpressions = false
            assertEquals(message("log.history.search.results", 1), search.component.statusText)
            assertNull(search.textEditor.toolTipText)
            search.findModel.isRegularExpressions = true
            panel.setSearchForTest("")
            assertEquals("", search.component.statusText)
            assertNull(search.textEditor.toolTipText)
            assertEquals(1, panel.sqlListEntries.size)
        }
    }

    fun testUI009_有SQL无匹配与真实空文件区分() {
        withHistoryPanel(listOf("SELECT 1")) { panel ->
            panel.regexToggleForTest = true
            panel.setSearchForTest("^UPDATE")
            assertEquals(0, panel.sqlListEntries.size)
            assertEquals(message("log.history.search.noMatches"), sqlEmptyText(panel))
            assertEquals(message("log.history.search.results", 0), searchOf(panel).component.statusText)
            assertNull(searchOf(panel).textEditor.toolTipText)
            panel.setSearchForTest("")
            panel.applyTemplateFocus("nonexistent-structure")
            assertEquals(0, panel.sqlListEntries.size)
            assertEquals(message("log.history.search.noMatches"), sqlEmptyText(panel))
        }
        withHistoryPanel(emptyList()) { panel ->
            assertEquals(0, panel.sqlListEntries.size)
            assertEquals(message("log.history.entries.empty"), sqlEmptyText(panel))
            panel.setSearchForTest("missing")
            assertEquals(message("log.history.entries.empty"), sqlEmptyText(panel))
        }
    }

    fun testUI009_返回文件级清除正则错误且无匹配不冒充无文件() {
        withHistoryPanel(listOf("SELECT 1")) { panel ->
            panel.regexToggleForTest = true
            panel.setSearchForTest("(")
            assertNotNull(searchOf(panel).textEditor.toolTipText)
            panel.backToFilesForTest()
            assertEquals(0, panel.fileListEntries.size)
            assertNull(searchOf(panel).textEditor.toolTipText)
            assertEquals(message("log.history.search.results", 0), searchOf(panel).component.statusText)
            assertEquals(message("log.history.search.noMatches"), filesOf(panel).emptyText.text)
            panel.setSearchForTest("sql-history")
            assertEquals(1, panel.fileListEntries.size)
            panel.openFileForTest(panel.fileListEntries.single())
            assertEquals("", searchOf(panel).component.statusText)
            assertNull(searchOf(panel).textEditor.toolTipText)
        }
    }

    fun test历史页空态链接显示条件() {
        // T-SettingsFocusFromLink：仅「未开启自动保存 且 无历史文件」时露出
        org.junit.Assert.assertTrue(shouldShowAutoSaveHistoryLink(autoSaveEnabled = false, fileCount = 0))
        org.junit.Assert.assertFalse("已开自动保存不显示", shouldShowAutoSaveHistoryLink(true, 0))
        org.junit.Assert.assertFalse("有历史文件不显示", shouldShowAutoSaveHistoryLink(false, 3))
    }

    fun test进条目卡清空搜索框且条目回全量() {
        // 用户定（20261010）：文件级搜索词不带入条目级——点进文件后搜索框应清空、条目全量
        writeHistoryFile("sql-history-clear.sql", listOf("SELECT 1", "SELECT 2"))
        val panel = HistorySqlLogPanel(project)
        panel.refreshForTest()
        try {
            panel.setSearchForTest("clear")
            assertEquals(listOf("sql-history-clear.sql"), panel.fileListEntries.map { it.displayName })
            panel.openFileForTest(panel.fileListEntries.single())
            assertEquals("进条目卡应清空搜索框", "", panel.searchTextForTest())
            assertEquals("条目应为全量（文件级搜索词未带入）", 2, panel.sqlListEntries.size)
        } finally {
            panel.dispose()
        }
    }

    fun testF88_聚合只随快照换代_重复apply不重算() {
        // F88（T-PerfSweep25）：同模板聚合与查询无关、只随条目快照换代——同快照反复 apply
        // （空查询防抖、查询进出）不再重复 O(N) 聚合；徽章口径不丢（首算一次即全量口径）。
        writeHistoryFile("sql-history-f88.sql", listOf("SELECT * FROM user WHERE id = 1", "select 1"))
        val panel = HistorySqlLogPanel(project)
        panel.refreshForTest()
        var aggregations = 0
        panel.structureKeyCountsHookForTest = { entries ->
            aggregations++
            structureKeyCounts(entries)
        }
        try {
            panel.openFileForTest(panel.fileListEntries.single())
            assertEquals("加载后首算恰一次（修复前同）", 1, aggregations)
            // 空查询反复 apply（防抖反复触发形态）+ 非空查询 apply——同快照不再重算
            panel.setSearchForTest("")
            panel.setSearchForTest("")
            panel.setSearchForTest("select")
            panel.setSearchForTest("")
            assertEquals("同快照四次 apply 聚合仍恰一次（修复前 5 → 红）", 1, aggregations)
            // 语义等价守卫：空查询首算后徽章计数在位（朴素「空查询即跳过」会让首屏徽章丢算 → 此行红）
            org.junit.Assert.assertTrue(
                "空查询下徽章计数已首算（全量口径不丢）",
                panel.templateCountsForTest.isNotEmpty(),
            )
            // 快照换代（重读文件）→ 每代恰算一次：refreshForTest 换一代（重读文件）、
            // 重开文件再换一代——两次换代共重算两次（修复前每次 apply 都重算 → 此处同样红）
            panel.refreshForTest()
            panel.openFileForTest(panel.fileListEntries.single())
            assertEquals("两次快照换代各重算恰一次（修复前每次 apply 都重算 → 红）", 3, aggregations)
        } finally {
            panel.structureKeyCountsHookForTest = null
            panel.dispose()
        }
    }

    fun test面板开关驱动一级文件名检索() {
        writeHistoryFile("sql-history-case.sql", listOf("SELECT 1"))
        val panel = HistorySqlLogPanel(project)
        panel.refreshForTest()
        try {
            assertEquals(1, panel.fileListEntries.size)
            // 一级：matchCase 生效、regex 不参与——"SQL-HISTORY" 关态命中（忽略大小写）、开态不命中
            panel.setSearchForTest("SQL-HISTORY")
            assertEquals(1, panel.fileListEntries.size)
            panel.matchCaseToggleForTest = !panel.matchCaseToggleForTest
            assertEquals(0, panel.fileListEntries.size)
        } finally {
            panel.dispose()
        }
    }
}
