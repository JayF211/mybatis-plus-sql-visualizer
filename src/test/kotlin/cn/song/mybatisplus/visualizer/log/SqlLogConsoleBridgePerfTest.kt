package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志捕捉性能优化测试（T-LogPerf-01/03/04，源自《性能分析 20260902-0054》优化建议）。
 *
 * 覆盖：快速轮询指数退避计算、descriptor 短路判定、processConsoleLine 配置快照语义
 * （hibernateEnabled 快照 + 逐行零 configProvider 依赖）。纯 JUnit，无平台服务依赖。
 */
class SqlLogConsoleBridgePerfTest {

    // ---------- T-LogPerf-01：快速轮询指数退避 ----------

    @Test
    fun `退避计算——起步500ms每拍翻倍2s封顶`() {
        var delay = SqlLogConsoleBridge.nextFastDelay(500, backoff = true)
        assertEquals(1000, delay)
        delay = SqlLogConsoleBridge.nextFastDelay(delay, backoff = true)
        assertEquals(2000, delay)
        // 已到上限后不再增长
        delay = SqlLogConsoleBridge.nextFastDelay(delay, backoff = true)
        assertEquals(2000, delay)
    }

    @Test
    fun `退避重置——检测到控制台回落起步间隔`() {
        assertEquals(500, SqlLogConsoleBridge.nextFastDelay(2000, backoff = false))
        assertEquals(500, SqlLogConsoleBridge.nextFastDelay(1000, backoff = false))
    }

    // ---------- T-LogPerf-03：descriptor 短路判定 ----------

    private class ConsoleStub

    @Test
    fun `短路判定——缓存命中的console已挂载则跳过`() {
        val console = ConsoleStub()
        assertTrue(SqlLogConsoleBridge.shouldSkipRescan(console, listOf(console)))
    }

    @Test
    fun `短路判定——未挂载或未知console不跳过`() {
        val console = ConsoleStub()
        // 未挂载（attachedConsoles 为空）
        assertFalse(SqlLogConsoleBridge.shouldSkipRescan(console, emptyList()))
        // 缓存为 null（上一轮未找到 console，如 HTTP 控制台/无组件）
        assertFalse(SqlLogConsoleBridge.shouldSkipRescan(null, listOf(console)))
        // 身份不同（不同 console 实例）——IdentityHashMap 语义
        assertFalse(SqlLogConsoleBridge.shouldSkipRescan(ConsoleStub(), listOf(console)))
    }

    // ---------- T-LogPerf-04：processConsoleLine 配置快照语义 ----------

    private fun newCtx(hibernateEnabled: Boolean): BridgeLineContext = BridgeLineContext(
        myBatisParser = MyBatisLogParser(sqlPrefix = "Preparing:", paramPrefix = "Parameters:", mixedPrefix = true, maxParseLength = 20000),
        hibernateParser = HibernateLogParser(sqlPrefix = "Hibernate:", paramPrefix = "binding parameter", mixedPrefix = true, maxParseLength = 20000),
        jdbcParser = JdbcTemplateLogParser(enabled = false, maxParseLength = 20000),
        configProvider = { ParserConfig("Preparing:", "Parameters:", hibernateEnabled, 20000) },
        hibernateEnabled = hibernateEnabled,
    )

    @Test
    fun `配置快照——hibernateEnabled=false时hibernate解析器不喂行`() {
        val ctx = newCtx(hibernateEnabled = false)
        processConsoleLine("Hibernate: SELECT * FROM user WHERE id=?", ctx, null, {})
        val entries = processConsoleLine("binding parameter [1] as [INTEGER] - [1]", ctx, null, {})
        // hibernate 解析器未被喂行 → 不产生条目
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `配置快照——逐行不再依赖configProvider`() {
        // configProvider 抛异常也不影响喂行（逐行零调用——T-LogPerf-04 核心断言）
        val ctx = BridgeLineContext(
            myBatisParser = MyBatisLogParser(sqlPrefix = "Preparing:", paramPrefix = "Parameters:", mixedPrefix = true, maxParseLength = 20000),
            hibernateParser = HibernateLogParser(sqlPrefix = "Hibernate:", paramPrefix = "binding parameter", mixedPrefix = true, maxParseLength = 20000),
            jdbcParser = JdbcTemplateLogParser(enabled = false, maxParseLength = 20000),
            configProvider = { error("per-line config sync should be gone") },
            hibernateEnabled = false,
        )
        processConsoleLine("==>  Preparing: SELECT * FROM user WHERE id = 1", ctx, null, {})
        val entries = processConsoleLine("==>  Parameters: 1(Long)", ctx, null, {})
        assertEquals(1, entries.size)
        assertEquals("SELECT * FROM user WHERE id = 1", entries.first().rawSql)
    }
}
