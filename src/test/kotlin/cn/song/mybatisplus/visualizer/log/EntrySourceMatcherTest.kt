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

    // ---- T-JumpPath-03：段对齐后缀互含（锚点项目根相对 vs 条目包式源码根相对） ----

    @Test
    fun `sameFile 表驱动`() {
        val table = listOf(
            // 等值
            Triple("a/b/S.java", "a/b/S.java", true),
            // 条目包式路径是锚点项目根相对路径的段对齐后缀（真机实锤形态：多模块含模块前缀）
            Triple("mod/src/main/java/cn/song/a/S.java", "cn/song/a/S.java", true),
            Triple("cn/song/a/S.java", "mod/src/main/java/cn/song/a/S.java", true),
            // 段不齐不命中：后缀虽同形但 `/` 段边界错位（xmod 前缀假命中防线）
            Triple("xmod/src/main/java/cn/song/a/S.java", "mod/src/main/java/cn/song/a/S.java", false),
            Triple("src/main/java/com/demo/UserService.java", "demo/UserService.java", true),
            Triple("src/main/java/com/xdemo/UserService.java", "demo/UserService.java", false),
            // 裸文件名段对齐互含
            Triple("cn/song/A.java", "A.java", true),
            Triple("cn/song/BA.java", "A.java", false),
        )
        for ((a, b, expected) in table) {
            assertEquals("sameFile($a, $b)", expected, EntrySourceMatcher.sameFile(a, b))
            assertEquals("sameFile($b, $a) 对称性", expected, EntrySourceMatcher.sameFile(b, a))
        }
    }

    @Test
    fun `锚点项目根形态与条目包式形态互含命中`() {
        // 真机场景：gutter 锚点 = 项目根相对（多模块含模块前缀），条目 sourceFile = 拦截器包式路径
        val entries = listOf(
            entry("join", sourceFile = "cn/song/mpjdemo/service/CarService.java", sourceLine = 42),
            entry("other", sourceFile = "cn/song/other/CarService.java", sourceLine = 42),
        )
        val matched = EntrySourceMatcher.match(
            entries, "mybatis-plus-join-demo/src/main/java/cn/song/mpjdemo/service/CarService.java", 45,
        )
        assertEquals(listOf("join"), matched.map { it.restoredSql() })
    }
}
