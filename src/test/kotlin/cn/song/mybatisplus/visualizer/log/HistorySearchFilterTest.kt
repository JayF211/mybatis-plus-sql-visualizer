package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import cn.song.mybatisplus.visualizer.log.HistorySqlLogPanelKt
import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.SqlSource
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

    private fun entry(sql: String, sourceFile: String? = null, sequenceNumber: Long = 0L): LogSqlEntry {
        var e = LogSqlEntry(sql, sql, emptyList(), 0L, null, null, SqlSource.UNKNOWN,
            null, sourceFile, 0, emptyList(), emptyList(), null, sequenceNumber,
            null, null, null, null, null, null)
        return e
    }

    /** 三条样本：大写 SELECT / 无匹配 / 小写 select——正则与大小写语义的判别基础。 */
    private val entries = listOf(
        entry("SELECT * FROM user WHERE id = 1", sourceFile = "UserMapper.java:10", sequenceNumber = 1L),
        entry("UPDATE orders SET status = 'PAID'", sourceFile = "OrderMapper.java:5", sequenceNumber = 2L),
        entry("select 1", sourceFile = null, sequenceNumber = 3L),
    )

    // ---------- 正则路径：前缀兼容（R16 已发行能力，不回归） ----------

    fun test正则经前缀触发_开关关闭时照旧() {
        val out = HistorySqlLogPanelKt.filterHistoryEntries(entries, "regex:SELECT.*user")
        assertEquals(listOf("SELECT * FROM user WHERE id = 1"), out.map { it.restoredSql() })
    }

    fun test正则经开关触发_无前缀时整串按正则() {
        // 默认 matchCase=false（IGNORE_CASE）：小写 select 1 命中 pattern "SELECT \d+"
        val out = HistorySqlLogPanelKt.filterHistoryEntries(entries, "SELECT \\d+", true, false, HistoryRegexMemo())
        assertEquals(listOf("select 1"), out.map { it.restoredSql() })
    }

    fun test前缀在场先剥_剥后整段才是pattern() {
        // 开关开 + 前缀在场：pattern 是 "user" 而非 "regex:user"（未剥则无一命中）
        val out = HistorySqlLogPanelKt.filterHistoryEntries(entries, "regex:user", true, false, HistoryRegexMemo())
        assertEquals(listOf("SELECT * FROM user WHERE id = 1"), out.map { it.restoredSql() })
    }

    // ---------- 正则路径：大小写随开关（行为变更声明：恒敏感 → 随开关，默认关=忽略） ----------

    fun test正则大小写随开关() {
        val sensitive = HistorySqlLogPanelKt.filterHistoryEntries(entries, "select", true, true, HistoryRegexMemo())
        assertEquals(listOf("select 1"), sensitive.map { it.restoredSql() })
        val insensitive = HistorySqlLogPanelKt.filterHistoryEntries(entries, "select", true, false, HistoryRegexMemo())
        assertEquals(2, insensitive.size)
    }

    fun test正则命中源文件目标() {
        val out = HistorySqlLogPanelKt.filterHistoryEntries(entries, "OrderMapper\\.java", true, false, HistoryRegexMemo())
        assertEquals(listOf("UPDATE orders SET status = 'PAID'"), out.map { it.restoredSql() })
    }

    // ---------- 非法正则 fail-closed 回退子串（现状保持） ----------

    fun test非法正则经前缀_回退原文子串() {
        val withLiteral = entries + entry("note: regex:( kept literal")
        val out = HistorySqlLogPanelKt.filterHistoryEntries(withLiteral, "regex:(")
        assertEquals(listOf("note: regex:( kept literal"), out.map { it.restoredSql() })
    }

    fun test非法正则经开关_回退子串() {
        val withParen = entries + entry("SELECT COUNT(*) FROM t")
        val out = HistorySqlLogPanelKt.filterHistoryEntries(withParen, "(", true, false, HistoryRegexMemo())
        assertEquals(listOf("SELECT COUNT(*) FROM t"), out.map { it.restoredSql() })
    }

    // ---------- 子串路径：大小写统一由 matchCase 控制 ----------

    fun test子串大小写随开关() {
        assertEquals(1, HistorySqlLogPanelKt.filterHistoryEntries(entries, "USER", false, false, HistoryRegexMemo()).size)
        assertEquals(0, HistorySqlLogPanelKt.filterHistoryEntries(entries, "USER", false, true, HistoryRegexMemo()).size)
    }

    fun test子串命中源文件目标且随开关() {
        assertEquals(1, HistorySqlLogPanelKt.filterHistoryEntries(entries, "ordermapper", false, false, HistoryRegexMemo()).size)
        assertEquals(0, HistorySqlLogPanelKt.filterHistoryEntries(entries, "ordermapper", false, true, HistoryRegexMemo()).size)
    }

    fun test行号精确匹配不受matchCase影响() {
        assertEquals(1, HistorySqlLogPanelKt.filterHistoryEntries(entries, "#2", false, true, HistoryRegexMemo()).size)
        assertEquals(1, HistorySqlLogPanelKt.filterHistoryEntries(entries, "2", false, true, HistoryRegexMemo()).size)
    }

    fun test空查询透传全部() {
        assertEquals(entries, HistorySqlLogPanelKt.filterHistoryEntries(entries, ""))
        assertEquals(entries, HistorySqlLogPanelKt.filterHistoryEntries(entries, "   ", true, false, HistoryRegexMemo()))
    }

    // ---------- memo（S3：键 = 源串 + matchCase 旗，最易埋点） ----------

    fun testresolve换旗必重编_同键复用() {
        val memo = HistoryRegexMemo()
        val ignoreCase = HistorySqlLogPanelKt.resolveHistoryRegex("user", false, memo)!!
        assertTrue(ignoreCase.options.contains(RegexOption.IGNORE_CASE))
        val sensitive = HistorySqlLogPanelKt.resolveHistoryRegex("user", true, memo)!!
        assertFalse(sensitive.options.contains(RegexOption.IGNORE_CASE))
        assertNotSame("换旗必须重编（复用即 memo 键缺旗的存量隐患）", ignoreCase, sensitive)
        assertSame("同源串同旗复用", sensitive, HistorySqlLogPanelKt.resolveHistoryRegex("user", true, memo))
    }

    fun testmemo换旗重编_过滤结果随旗翻转() {
        val memo = HistoryRegexMemo()
        val upper = listOf(entry("SELECT * FROM USER"))
        // 第一遍：忽略大小写编译，大写目标命中
        assertEquals(1, HistorySqlLogPanelKt.filterHistoryEntries(upper, "user", true, false, memo).size)
        // 第二遍：同 memo 同源串、只切旗——必须重编（单字段 memo 会复用 IGNORE_CASE 编译结果而误命中）
        assertEquals(0, HistorySqlLogPanelKt.filterHistoryEntries(upper, "user", true, true, memo).size)
    }

    fun testmemo记住非法源串_换旗换串即重试() {
        val memo = HistoryRegexMemo()
        val withLiteral = listOf(entry("regex:( literal"))
        assertEquals(1, HistorySqlLogPanelKt.filterHistoryEntries(withLiteral, "regex:(", false, false, memo).size)
        // 同坏串同旗反复 apply：行为一致（失败被 memo，不再重复抛编译异常）
        assertEquals(1, HistorySqlLogPanelKt.filterHistoryEntries(withLiteral, "regex:(", false, false, memo).size)
        // 换合法串即重试（正则路径命中）
        assertEquals(1, HistorySqlLogPanelKt.filterHistoryEntries(withLiteral, "literal", true, false, memo).size)
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
            assertEquals(listOf("select 1"), panel.sqlListEntries.map { it.restoredSql() })

            // 点 Regex：正则开关接线生效——pattern "WHERE id = 1$" 命中第一条（子串语义下无命中）
            panel.regexToggleForTest = !panel.regexToggleForTest
            panel.setSearchForTest("WHERE id = 1$")
            assertEquals(listOf("SELECT * FROM USER WHERE id = 1"), panel.sqlListEntries.map { it.restoredSql() })
        } finally {
            panel.dispose()
        }
    }

    fun test历史页空态链接显示条件() {
        // T-SettingsFocusFromLink：仅「未开启自动保存 且 无历史文件」时露出
        org.junit.Assert.assertTrue(HistorySqlLogPanelKt.shouldShowAutoSaveHistoryLink(false, 0))
        org.junit.Assert.assertFalse("已开自动保存不显示", HistorySqlLogPanelKt.shouldShowAutoSaveHistoryLink(true, 0))
        org.junit.Assert.assertFalse("有历史文件不显示", HistorySqlLogPanelKt.shouldShowAutoSaveHistoryLink(false, 3))
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
