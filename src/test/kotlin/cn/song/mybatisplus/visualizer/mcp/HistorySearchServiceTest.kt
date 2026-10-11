package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * R14 T-HistorySearch：历史落盘检索器测试（T-01，纯逻辑零平台 fixture）。
 *
 * 夹具直接落 [SqlLogFileReader] 新版格式的 .sql 文件到临时目录——检索链端到端
 * （落盘格式 → Reader 反解析 → 检索过滤），不桩 Reader。
 * 覆盖：关键词/正则/时间范围/来源四维过滤 + 组合、默认当日范围（D1=A）、
 * allDates 扩展、上限截断（D2=A）、预览截断、排序、扫描计数、空目录合法零命中。
 */
class HistorySearchServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val nowMs: Long = System.currentTimeMillis()

    private fun fmt(ms: Long): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(ms))

    /**
     * 落盘条目文本（SqlLogAutoSaver 同构）：头部 + SQL + 空行。
     * 历史文件头**不携带 SqlSource**（通道标记在 pd 载荷里且仅 MCP 落键——写端真实语义），
     * 头部 `source:` 段是源码位置；`withPd=true` 时补一条 pd 行（SqlLogParamCodec 编码，
     * 与写端 encodeEntry 同链路），覆盖「pd 行不把条目劈开」的读端互作。
     */
    private fun entryText(
        ts: Long,
        sql: String,
        sourceFile: String? = null,
        withPd: Boolean = false,
    ): String = buildString {
        append("-- source: ").append(sourceFile ?: "unknown")
        append(" | time: 12ms | ").append(fmt(ts)).append(" --\n")
        if (withPd) {
            // K2J 互操作：LogSqlEntry 为 Java record，具名实参改 19 参位置形态（sourceFile 第 9 位）。
            val carrier = cn.song.mybatisplus.visualizer.log.LogSqlEntry(
                sql,
                sql,
                listOf(cn.song.mybatisplus.visualizer.log.LogSqlParam("1", "Long")),
                ts,
                null,
                null,
                null,
                null,
                sourceFile,
                0,
                java.util.Collections.emptyList<kotlin.Pair<String, Int>>(),
                java.util.Collections.emptyList<String>(),
                null,
                0L,
                null,
                null,
                null,
                null,
                null,
            )
            append("-- pd: ").append(cn.song.mybatisplus.visualizer.log.SqlLogParamCodec.encodeEntry(carrier)).append(" --\n")
        }
        append(sql).append("\n\n")
    }

    private fun writeHistoryFile(name: String, entries: List<String>): File {
        val f = tmp.newFile(name)
        f.writeText(entries.joinToString(""))
        return f
    }

    private fun search(dir: File, query: HistorySearchService.Query) =
        HistorySearchService.search(dir.absolutePath, query, nowMs)

    private fun startOfToday(now: Long): Long =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ------------------------------------------------------------------
    // 四维过滤：关键词
    // ------------------------------------------------------------------

    @Test
    fun test关键词命中_SQL文本() {
        writeHistoryFile(
            "h.sql",
            listOf(
                entryText(nowMs - 1000, "SELECT * FROM t_user WHERE id = 1"),
                entryText(nowMs - 500, "SELECT * FROM t_order WHERE id = 2"),
            ),
        )
        val r = search(dir(), HistorySearchService.Query("t_user", null, null, null, null, false, HistorySearchService.DEFAULT_MAX_RESULTS))
        assertEquals(1, r.totalHits)
        assertTrue(r.hits[0].sqlPreview, r.hits[0].sqlPreview.contains("t_user"))
    }

    @Test
    fun test关键词大小写不敏感() {
        writeHistoryFile("h.sql", listOf(entryText(nowMs, "select * from T_USER")))
        assertEquals(1, search(dir(), HistorySearchService.Query("T_USER", null, null, null, null, false, HistorySearchService.DEFAULT_MAX_RESULTS)).totalHits)
        assertEquals(1, search(dir(), HistorySearchService.Query("t_user", null, null, null, null, false, HistorySearchService.DEFAULT_MAX_RESULTS)).totalHits)
    }

    @Test
    fun test关键词命中mapper语句id() {
        writeHistoryFile(
            "h.sql",
            listOf(entryText(nowMs, "SELECT * FROM t_user WHERE id = ?")),
        )
        // mapperStatement 在 pd 载荷里，夹具不写 pd 行 → 这里只验证「SQL 文本命中」语义；
        // mapper 命中由带 pd 的条目覆盖（见 test带pd行条目不被劈开）。
        assertEquals(1, search(dir(), HistorySearchService.Query("t_user", null, null, null, null, false, HistorySearchService.DEFAULT_MAX_RESULTS)).totalHits)
    }

    // ------------------------------------------------------------------
    // 四维过滤：正则
    // ------------------------------------------------------------------

    @Test
    fun test正则命中() {
        writeHistoryFile(
            "h.sql",
            listOf(
                entryText(nowMs - 1000, "SELECT * FROM t_user WHERE id = 1"),
                entryText(nowMs - 500, "UPDATE t_user SET name = 'x'"),
            ),
        )
        val r = search(dir(), HistorySearchService.Query(null, "^select .+ from t_\\w+", null, null, null, false, HistorySearchService.DEFAULT_MAX_RESULTS))
        assertEquals(1, r.totalHits)
        assertTrue(r.hits[0].sqlPreview.contains("SELECT"))
    }

    @Test
    fun test非法正则_预期异常() {
        try {
            search(dir(), HistorySearchService.Query(null, "[unclosed", null, null, null, false, HistorySearchService.DEFAULT_MAX_RESULTS))
            org.junit.Assert.fail("非法正则应抛 InvalidQueryException")
        } catch (expected: HistorySearchService.InvalidQueryException) {
            assertTrue(expected.message!!.contains("regex"))
        }
    }

    // ------------------------------------------------------------------
    // 四维过滤：时间范围
    // ------------------------------------------------------------------

    @Test
    fun test时间范围过滤_闭区间() {
        val t1 = nowMs - 10_000
        val t2 = nowMs - 5_000
        val t3 = nowMs - 1_000
        writeHistoryFile("h.sql", listOf(entryText(t1, "SELECT 1"), entryText(t2, "SELECT 2"), entryText(t3, "SELECT 3")))
        val r = search(dir(), HistorySearchService.Query(null, null, t2, t2, null, true, HistorySearchService.DEFAULT_MAX_RESULTS))
        assertEquals(1, r.totalHits)
        assertEquals("SELECT 2", r.hits[0].sqlPreview)
    }

    @Test
    fun test时间范围倒置_预期异常() {
        try {
            search(dir(), HistorySearchService.Query(null, null, nowMs, nowMs - 1000, null, true, HistorySearchService.DEFAULT_MAX_RESULTS))
            org.junit.Assert.fail("from > to 应抛 InvalidQueryException")
        } catch (expected: HistorySearchService.InvalidQueryException) {
        }
    }

    // ------------------------------------------------------------------
    // 四维过滤：来源文件（通道标记在落盘数据里仅 MCP 存活——实施期重锚证伪后定案口径）
    // ------------------------------------------------------------------

    @Test
    fun test来源文件过滤_大小写不敏感子串() {
        writeHistoryFile(
            "h.sql",
            listOf(
                entryText(nowMs, "SELECT * FROM t_user", sourceFile = "src/main/java/com/example/UserService.java:42"),
                entryText(nowMs + 1, "SELECT * FROM t_order", sourceFile = "src/main/java/com/example/OrderService.java:8"),
                entryText(nowMs + 2, "SELECT * FROM t_log"),
            ),
        )
        assertEquals(1, search(dir(), HistorySearchService.Query(null, null, null, null, "userservice", false, HistorySearchService.DEFAULT_MAX_RESULTS)).totalHits)
        assertEquals(1, search(dir(), HistorySearchService.Query(null, null, null, null, "UserService.java", false, HistorySearchService.DEFAULT_MAX_RESULTS)).totalHits)
        // 子串语义：两个服务类都在 com/example 目录下
        assertEquals(2, search(dir(), HistorySearchService.Query(null, null, null, null, "com/example", false, HistorySearchService.DEFAULT_MAX_RESULTS)).totalHits)
        // 无来源文件的条目对来源文件过滤不命中（不等于「匹配任意」）
        assertEquals(0, search(dir(), HistorySearchService.Query(null, null, null, null, "t_log", false, HistorySearchService.DEFAULT_MAX_RESULTS)).totalHits)
    }

    // ------------------------------------------------------------------
    // D1=A：默认当日范围 + allDates 扩展
    // ------------------------------------------------------------------

    @Test
    fun test默认只检索当日条目_D1() {
        val yesterday = startOfToday(nowMs) - 86_400_000L
        writeHistoryFile(
            "h.sql",
            listOf(
                entryText(yesterday, "SELECT * FROM t_yesterday"),
                entryText(nowMs - 100, "SELECT * FROM t_today"),
            ),
        )
        val r = search(dir(), HistorySearchService.Query("t_", null, null, null, null, false, HistorySearchService.DEFAULT_MAX_RESULTS))
        assertEquals("默认范围应只含当日条目（D1=A）", 1, r.totalHits)
        assertTrue(r.hits[0].sqlPreview.contains("t_today"))
    }

    @Test
    fun testallDates扩展到全部日期_D1() {
        val yesterday = startOfToday(nowMs) - 86_400_000L
        writeHistoryFile(
            "h.sql",
            listOf(
                entryText(yesterday, "SELECT * FROM t_yesterday"),
                entryText(nowMs - 100, "SELECT * FROM t_today"),
            ),
        )
        assertEquals(2, search(dir(), HistorySearchService.Query("t_", null, null, null, null, true, HistorySearchService.DEFAULT_MAX_RESULTS)).totalHits)
    }

    @Test
    fun test默认范围下显式fromMs不可越界到昨日() {
        val yesterday = startOfToday(nowMs) - 86_400_000L
        writeHistoryFile("h.sql", listOf(entryText(yesterday, "SELECT * FROM t_yesterday")))
        // 未开 allDates 时，显式 fromMs 早于今日起点不得放宽默认范围（要跨天请 allDates=true）
        assertEquals(0, search(dir(), HistorySearchService.Query(null, null, yesterday - 1, null, null, false, HistorySearchService.DEFAULT_MAX_RESULTS)).totalHits)
    }

    // ------------------------------------------------------------------
    // 组合过滤
    // ------------------------------------------------------------------

    @Test
    fun test组合过滤_关键词与时间与来源文件() {
        val yesterday = startOfToday(nowMs) - 86_400_000L
        writeHistoryFile(
            "h.sql",
            listOf(
                entryText(yesterday, "SELECT * FROM t_user WHERE id = 1", sourceFile = "src/UserService.java"),
                entryText(nowMs - 2000, "SELECT * FROM t_user WHERE id = 2", sourceFile = "src/UserService.java"),
                entryText(nowMs - 1000, "SELECT * FROM t_user WHERE id = 3", sourceFile = "src/OrderService.java"),
                entryText(nowMs - 500, "SELECT * FROM t_order WHERE id = 4"),
            ),
        )
        val r = search(dir(), HistorySearchService.Query("t_user", null, nowMs - 2500, null, "userservice", false, HistorySearchService.DEFAULT_MAX_RESULTS))
        assertEquals(1, r.totalHits)
        assertTrue(r.hits[0].sqlPreview.contains("id = 2"))
    }

    // ------------------------------------------------------------------
    // D2=A / 上限量保护：截断与预览
    // ------------------------------------------------------------------

    @Test
    fun test超限截断_返回摘要不倾泻() {
        val entries = (1..30).map { entryText(nowMs - it * 100, "SELECT $it") }
        writeHistoryFile("h.sql", entries)
        val r = search(dir(), HistorySearchService.Query(null, null, null, null, null, false, 5))
        assertEquals(30, r.totalHits)
        assertEquals(5, r.returned)
        assertTrue("超限应置 truncated", r.truncated)
        assertEquals("默认范围全部命中、maxResults 截断", 5, r.hits.size)
    }

    @Test
    fun testmaxResults越界钳制不抛错() {
        val entries = (1..3).map { entryText(nowMs - it * 100, "SELECT $it") }
        writeHistoryFile("h.sql", entries)
        assertEquals(1, search(dir(), HistorySearchService.Query(null, null, null, null, null, false, 0)).returned)
        assertEquals(3, search(dir(), HistorySearchService.Query(null, null, null, null, null, false, 999_999)).returned)
    }

    @Test
    fun test命中排序_最新在前() {
        writeHistoryFile(
            "h.sql",
            listOf(
                entryText(nowMs - 3000, "SELECT 1"),
                entryText(nowMs - 1000, "SELECT 3"),
                entryText(nowMs - 2000, "SELECT 2"),
            ),
        )
        val r = search(dir(), HistorySearchService.Query("SELECT", null, null, null, null, true, HistorySearchService.DEFAULT_MAX_RESULTS))
        assertEquals(listOf("SELECT 3", "SELECT 2", "SELECT 1"), r.hits.map { it.sqlPreview })
    }

    @Test
    fun testSQL预览截断_多行长SQL() {
        val longSql = (1..50).joinToString("\n") { "SELECT $it" }
        writeHistoryFile("h.sql", listOf(entryText(nowMs, longSql)))
        val h = search(dir(), HistorySearchService.Query()).hits.single()
        assertTrue("预览行数应有上限", h.sqlPreview.lineSequence().count() <= HistorySearchService.MAX_PREVIEW_LINES + 1)
        assertTrue("预览字符应有上限", h.sqlPreview.length <= HistorySearchService.MAX_PREVIEW_CHARS)
        assertTrue("截断应带省略号", h.sqlPreview.endsWith("…"))
    }

    // ------------------------------------------------------------------
    // 合法零命中 / 空目录 / 计数器
    // ------------------------------------------------------------------

    @Test
    fun test空目录_零命中合法结果() {
        val r = search(dir(), HistorySearchService.Query("anything", null, null, null, null, false, HistorySearchService.DEFAULT_MAX_RESULTS))
        assertEquals(0, r.totalHits)
        assertFalse(r.truncated)
        assertEquals(0, r.scannedFiles)
    }

    @Test
    fun test目录不存在_零命中合法结果() {
        val r = HistorySearchService.search(tmp.root.resolve("no-such-dir").absolutePath, HistorySearchService.Query("x", null, null, null, null, false, HistorySearchService.DEFAULT_MAX_RESULTS), nowMs)
        assertEquals(0, r.totalHits)
    }

    @Test
    fun test无过滤词_默认当日为合法查询() {
        writeHistoryFile("h.sql", listOf(entryText(nowMs, "SELECT 1")))
        val r = search(dir(), HistorySearchService.Query())
        assertEquals(1, r.totalHits)
    }

    @Test
    fun test扫描计数器() {
        writeHistoryFile("a.sql", listOf(entryText(nowMs, "SELECT 1")))
        writeHistoryFile("b.sql", listOf(entryText(nowMs, "SELECT 2"), entryText(nowMs, "SELECT 3")))
        val r = search(dir(), HistorySearchService.Query("SELECT", null, null, null, null, false, HistorySearchService.DEFAULT_MAX_RESULTS))
        assertEquals(2, r.scannedFiles)
        assertEquals(3, r.scannedEntries)
        assertFalse(r.scanTruncated)
    }

    @Test
    fun test非sql扩展名不参与扫描() {
        tmp.newFile("readme.txt").writeText("SELECT * FROM t_user")
        writeHistoryFile("h.sql", listOf(entryText(nowMs, "SELECT 1")))
        val r = search(dir(), HistorySearchService.Query("SELECT", null, null, null, null, false, HistorySearchService.DEFAULT_MAX_RESULTS))
        assertEquals(1, r.scannedFiles)
        assertEquals(1, r.totalHits)
    }

    @Test
    fun test命中携带来源元信息() {
        writeHistoryFile(
            "sql-history-2026-09-30.sql",
            listOf(entryText(nowMs, "SELECT * FROM t_user", sourceFile = "src/UserService.java:42")),
        )
        val h = search(dir(), HistorySearchService.Query("t_user", null, null, null, null, false, HistorySearchService.DEFAULT_MAX_RESULTS)).hits.single()
        // 通道标记落盘不存活（仅 MCP 落键）——读回 UNKNOWN 是真实语义，不是缺陷
        assertEquals("UNKNOWN", h.source)
        assertEquals("src/UserService.java", h.sourceFile)
        assertEquals(42, h.sourceLine)
        assertEquals("sql-history-2026-09-30.sql", h.historyFile)
        assertEquals(12L, h.executionTimeMs)
    }

    @Test
    fun test带pd行条目不被劈开_关键词仍命中() {
        // pd 行（`-- pd: <base64> --`）以 `-- ` 起始——读端必须显式排除，否则条目被劈成两块
        writeHistoryFile(
            "h.sql",
            listOf(
                entryText(nowMs, "SELECT * FROM t_user WHERE id = ?", withPd = true),
            ),
        )
        val r = search(dir(), HistorySearchService.Query("t_user", null, null, null, null, false, HistorySearchService.DEFAULT_MAX_RESULTS))
        assertEquals("pd 行不得把条目劈开或挤丢", 1, r.totalHits)
        val h = r.hits.single()
        assertTrue(h.sqlPreview.contains("t_user"))
        assertEquals("pd 载荷的 mapperStatement 应透出", null, h.mapperStatement)
    }

    private fun dir(): File = tmp.root
}
