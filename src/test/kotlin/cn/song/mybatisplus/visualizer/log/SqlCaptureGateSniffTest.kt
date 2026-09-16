package cn.song.mybatisplus.visualizer.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat

/**
 * T-AgentDialectLoop 嗅探模式测试：增强接管（[SqlCaptureGate.paused]=true）期间，
 * [processConsoleLine] 不产出条目，但把「指纹 → 日志行原始时间」暂存 [LogTimeCorrelator]；
 * agent 事件侧以同指纹取回（agent/桥两通道同 SQL 同指纹为 M56 T-M56-02 既定口径）。
 * 纯逻辑测试，不依赖 IDE 环境；全局态（paused/相关器）随用例收尾还原。
 */
class SqlCaptureGateSniffTest {

    @After
    fun tearDown() {
        SqlCaptureGate.paused.set(false)
        LogTimeCorrelator.clear()
    }

    private fun newCtx(sniff: Boolean = true): BridgeLineContext {
        val cfg = ParserConfig("Preparing:", "Parameters:", true, 20000, sniffLogTime = sniff)
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
            hibernateEnabled = cfg.hibernateEnabled,
            sniffLogTime = cfg.sniffLogTime,
        )
    }

    @Test
    fun `嗅探态_零条目产出_日志时间进相关器`() {
        SqlCaptureGate.paused.set(true)
        val ctx = newCtx()
        val entries = listOf(
            "2026-09-16 10:30:45.123 [main] ==>  Preparing: SELECT * FROM user WHERE id = 1",
            "2026-09-16 10:30:45.124 [main] ==>  Parameters: 1(Long)",
        ).flatMap { processConsoleLine(it, ctx, null) {} }
        assertTrue("接管期间控制台链不产出条目", entries.isEmpty())
        assertEquals(1, LogTimeCorrelator.size())
        // agent 事件侧回填用同构指纹（restoredSql 归一化后 FNV-1a）
        val fp = sqlFingerprint(normalizeSqlWhitespace("SELECT * FROM user WHERE id = 1"))
        val ts = LogTimeCorrelator.take(fp)!!
        val expected = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").parse("2026-09-16 10:30:45.123").time
        assertEquals("回填的是日志行原始时间（Preparing 行）", expected, ts)
    }

    @Test
    fun `嗅探开关关闭_完全旁路`() {
        SqlCaptureGate.paused.set(true)
        val ctx = newCtx(sniff = false)
        listOf(
            "2026-09-16 10:30:45.123 [main] ==>  Preparing: SELECT * FROM user WHERE id = 1",
            "2026-09-16 10:30:45.124 [main] ==>  Parameters: 1(Long)",
        ).forEach { processConsoleLine(it, ctx, null) {} }
        assertEquals("时间显示关/时间源=捕获时零成本", 0, LogTimeCorrelator.size())
    }

    @Test
    fun `未接管时正常产出条目_不写相关器`() {
        val ctx = newCtx()
        val entries = listOf(
            "==>  Preparing: SELECT * FROM user WHERE id = 1",
            "==>  Parameters: 1(Long)",
        ).flatMap { processConsoleLine(it, ctx, null) {} }
        assertEquals("回落态控制台链照常捕获", 1, entries.size)
        assertEquals("正常捕获路径不写相关器", 0, LogTimeCorrelator.size())
    }

    @Test
    fun `无日志时间戳的行_回落条目捕获时间`() {
        SqlCaptureGate.paused.set(true)
        val ctx = newCtx()
        listOf(
            "==>  Preparing: SELECT * FROM t WHERE id = 9",
            "==>  Parameters: 9(Long)",
        ).forEach { processConsoleLine(it, ctx, null) {} }
        val fp = sqlFingerprint(normalizeSqlWhitespace("SELECT * FROM t WHERE id = 9"))
        val ts = LogTimeCorrelator.take(fp)
        assertTrue("未解析到时间戳时回落条目捕获时间（>0）", ts != null && ts > 0)
    }

    @Test
    fun `take未命中返回null_回落捕获时间语义`() {
        SqlCaptureGate.paused.set(true)
        LogTimeCorrelator.clear()
        assertNull(LogTimeCorrelator.take(sqlFingerprint(normalizeSqlWhitespace("SELECT 1"))))
    }
}
