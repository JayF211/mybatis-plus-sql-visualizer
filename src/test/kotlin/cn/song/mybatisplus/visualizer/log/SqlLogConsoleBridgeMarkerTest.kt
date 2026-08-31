package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [processConsoleLine] 纯逻辑测试（F-44-6 实时日志可靠跳转）：
 * 验证 SqlSourceInterceptor 打印的标记行被解析为 sourceFile/sourceLine，
 * 并附加到紧随其后的 SQL 条目上（与历史面板的精确跳转同源）。
 * 不依赖 IDE 运行环境，直接驱动解析管线。
 */
class SqlLogConsoleBridgeMarkerTest {

    private fun newCtx(): BridgeLineContext {
        val cfg = ParserConfig("Preparing:", "Parameters:", true, 20000)
        return BridgeLineContext(
            myBatisParser = MyBatisLogParser(
                sqlPrefix = cfg.sqlPrefix,
                paramPrefix = cfg.paramPrefix,
                mixedPrefix = cfg.mixedPrefix,
                maxParseLength = cfg.maxParseLength,
            ),
            hibernateParser = HibernateLogParser(
                sqlPrefix = cfg.hibernateSqlPrefix,
                paramPrefix = cfg.hibernateParamPrefix,
                mixedPrefix = cfg.mixedPrefix,
                maxParseLength = cfg.maxParseLength,
            ),
            jdbcParser = JdbcTemplateLogParser(enabled = cfg.jdbcEnabled, maxParseLength = cfg.maxParseLength),
            configProvider = { cfg },
        )
    }

    @Test
    fun 标记行先于SQL_源码位置写入条目() {
        val ctx = newCtx()
        // 标记行（拦截器打印）→ 不产生 SQL 条目，仅暂存
        assertTrue(processConsoleLine("SQLVIS-SOURCE|src/main/java/com/example/service/UserService.java|87", ctx, null, {}).isEmpty())
        // Preparing 行 → 缓冲，不闭合
        assertTrue(processConsoleLine("==>  Preparing: SELECT * FROM user WHERE id = 1", ctx, null, {}).isEmpty())
        // Parameters 行 → 闭合条目，附上暂存源码位置
        val entries = processConsoleLine("==>  Parameters: 1(Long)", ctx, null, {})
        assertEquals(1, entries.size)
        assertEquals("src/main/java/com/example/service/UserService.java", entries[0].sourceFile)
        assertEquals(87, entries[0].sourceLine)
        assertNull("使用后暂存清空", ctx.pendingSource)
    }

    @Test
    fun 无标记_源码位置为null_回退表名搜索() {
        val ctx = newCtx()
        processConsoleLine("==>  Preparing: SELECT * FROM user WHERE id = 1", ctx, null, {})
        val entries = processConsoleLine("==>  Parameters: 1(Long)", ctx, null, {})
        assertEquals(1, entries.size)
        assertNull(entries[0].sourceFile)
        assertEquals(0, entries[0].sourceLine)
    }

    @Test
    fun 标记后无SQL_暂存保留() {
        val ctx = newCtx()
        processConsoleLine("SQLVIS-SOURCE|com/x.java|5", ctx, null, {})
        assertEquals("com/x.java" to 5, ctx.pendingSource)
    }

    @Test
    fun 普通SQL行不误判为标记() {
        assertNull(SqlSourceMarker.parse("==>  Preparing: SELECT 1"))
        assertNull(SqlSourceMarker.parse("==>  Parameters: 1(Long)"))
    }

    @Test
    fun 多语句各自绑定标记() {
        val ctx = newCtx()
        processConsoleLine("SQLVIS-SOURCE|a/A.java|1", ctx, null, {})
        processConsoleLine("==>  Preparing: SELECT * FROM t1", ctx, null, {})
        val e1 = processConsoleLine("==>  Parameters: 1(Long)", ctx, null, {})
        assertEquals("a/A.java", e1[0].sourceFile)

        processConsoleLine("SQLVIS-SOURCE|b/B.java|2", ctx, null, {})
        processConsoleLine("==>  Preparing: SELECT * FROM t2", ctx, null, {})
        val e2 = processConsoleLine("==>  Parameters: 2(Long)", ctx, null, {})
        assertEquals("b/B.java", e2[0].sourceFile)
    }
}
