package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M37.1 反向深链纯函数测试：EntrySourceMatcher 按源码位置反查日志条目。
 *
 * 行号窗口语义：拦截器记录的是调用处行（通常在链锚点行之下），窗口匹配而非等值；
 * 同文件时间倒序、窗口内优先、结果上限。
 */
class EntrySourceMatcherTest {

    private fun entry(
        sql: String,
        sourceFile: String? = "src/main/java/com/demo/UserService.java",
        sourceLine: Int = 0,
        timestamp: Long = 0L,
    ): LogSqlEntry = LogSqlEntry(sql, sql, emptyList(), timestamp, sourceFile, sourceLine, null)

    private val anchorFile = "src/main/java/com/demo/UserService.java"

    @Test
    fun `同文件条目按时间倒序返回`() {
        val entries = listOf(
            entry("SELECT 1", timestamp = 100L),
            entry("SELECT 2", timestamp = 300L),
            entry("SELECT 3", timestamp = 200L),
        )
        val matched = EntrySourceMatcher.match(entries, anchorFile, 50)
        assertEquals(listOf("SELECT 2", "SELECT 3", "SELECT 1"), matched.map { it.restoredSql() })
    }

    @Test
    fun `窗口内条目优先于窗口外`() {
        val entries = listOf(
            entry("far", sourceLine = 10, timestamp = 300L),
            entry("near", sourceLine = 55, timestamp = 100L),
        )
        val matched = EntrySourceMatcher.match(entries, anchorFile, 50)
        // near(|Δ|=5)在窗口内优先,尽管时间更早
        assertEquals(listOf("near", "far"), matched.map { it.restoredSql() })
    }

    @Test
    fun `不同文件条目被过滤`() {
        val entries = listOf(
            entry("other", sourceFile = "src/main/java/com/demo/OrderService.java"),
            entry("mine", timestamp = 1L),
        )
        val matched = EntrySourceMatcher.match(entries, anchorFile, 10)
        assertEquals(listOf("mine"), matched.map { it.restoredSql() })
    }

    @Test
    fun `未知行号条目 sourceLine 0 排在窗口命中之后`() {
        val entries = listOf(entry("legacy", sourceLine = 0), entry("hit", sourceLine = 48, timestamp = 1L))
        val matched = EntrySourceMatcher.match(entries, anchorFile, 50)
        // 窗口内 hit 优先;未知行(Δ=50>30)作为同文件兜底随后返回(非排除)
        assertEquals(listOf("hit", "legacy"), matched.map { it.restoredSql() })
    }

    @Test
    fun `空锚点或非法行号返回空`() {
        assertTrue(EntrySourceMatcher.match(listOf(entry("x")), null, 10).isEmpty())
        assertTrue(EntrySourceMatcher.match(listOf(entry("x")), "", 10).isEmpty())
        assertTrue(EntrySourceMatcher.match(listOf(entry("x")), anchorFile, 0).isEmpty())
    }

    @Test
    fun `结果上限截断`() {
        val many = (1..50).map { entry("s$it", sourceLine = 40, timestamp = it.toLong()) }
        assertEquals(EntrySourceMatcher.MAX_RESULTS, EntrySourceMatcher.match(many, anchorFile, 40).size)
    }

    @Test
    fun `路径规约统一正斜杠与尾斜杠`() {
        assertEquals("a/b/c.java", EntrySourceMatcher.normalize(" a\\b\\c.java "))
        assertEquals("a/b/c.java", EntrySourceMatcher.normalize("a/b/c.java/"))
    }

    @Test
    fun `条目路径反斜杠形态仍可匹配`() {
        val entries = listOf(entry("win", sourceFile = "src\\main\\java\\com\\demo\\UserService.java"))
        val matched = EntrySourceMatcher.match(entries, anchorFile, 10)
        assertEquals(listOf("win"), matched.map { it.restoredSql() })
    }
}
