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
