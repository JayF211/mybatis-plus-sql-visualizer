package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R10 T-ShardingSphereCompat 第四解析器判别测试（骨架首版，V36 语料采样外部动作缺位）。
 *
 * **夹具全部 synthetic**：按 ShardingSphere 5.x 公开文档 sql-show 输出形态合成
 * （`Logic SQL: …` / `Actual SQL: <ds> ::: <sql> ::: Parameters: [v, …]`），
 * **不是**真实用户环境采到的双段日志语料——语料到位后的行为细节（参数值含 ", " 的拆分、
 * 4.x 形态、与 MyBatis 行交错的配对语义 V11a）登记在解析器 TODO 与计划 §实施记录，勿当作已验证。
 *
 * 判别红线：实现前本类编译红（ShardingSphereLogParser / SqlSource.SHARDINGSPHERE /
 * LogSqlEntry.actualSql / BridgeLineContext 第四解析器参数均未定义）。
 */
class ShardingSphereLogParserTest {

    // ---------- 合成夹具（synthetic，5.x 公开文档形态） ----------

    /** 标准 logback 前缀（线程 token 供 LogThreadRouter 分桶；级别关键词供 token 定位）。 */
    private val logicLine =
        "[INFO] 2026-09-30 17:00:00.000 [main] org.apache.shardingsphere.infra.script - Logic SQL: SELECT * FROM t_user WHERE id = ?"
    private val actualLine =
        "[INFO] 2026-09-30 17:00:00.001 [main] org.apache.shardingsphere.infra.script - " +
            "Actual SQL: ds_0 ::: SELECT * FROM t_user_0 WHERE id = ? ::: Parameters: [1]"

    // ---------- 双段配对（D1=A：Logic 主条目 + Actual 折叠附注） ----------

    @Test
    fun `双段配对_Logic主条目_Actual折叠附注`() {
        val parser = ShardingSphereLogParser()
        parser.feed(logicLine)
        parser.feed(actualLine)
        // 语句尚未闭合（等下一条 Logic / closePending / 静默冲刷）
        assertTrue(parser.takeEntries().isEmpty())
        parser.closePending()
        val entries = parser.takeEntries()
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals(SqlSource.SHARDINGSPHERE, entry.source)
        assertEquals("SELECT * FROM t_user WHERE id = ?", entry.rawSql)
        assertEquals("Logic SQL 为主条目且参数可还原（无类型标注数值原样）", "SELECT * FROM t_user WHERE id = 1", entry.restoredSql)
        assertEquals(1, entry.params.size)
        assertEquals("1", entry.params[0].value)
        assertNull(entry.params[0].type)
        assertEquals(
            "Actual 段折叠附注（关键字后原文，不含日志前缀）",
            "ds_0 ::: SELECT * FROM t_user_0 WHERE id = ? ::: Parameters: [1]",
            entry.actualSql,
        )
    }

    @Test
    fun `多分片Actual逐行折叠_参数取首个Actual`() {
        val parser = ShardingSphereLogParser()
        parser.feed(logicLine)
        parser.feed(actualLine)
        parser.feed(
            "[INFO] 2026-09-30 17:00:00.002 [main] org.apache.shardingsphere.infra.script - " +
                "Actual SQL: ds_1 ::: SELECT * FROM t_user_1 WHERE id = ? ::: Parameters: [1]",
        )
        parser.closePending()
        val entries = parser.takeEntries()
        assertEquals(1, entries.size)
        val actual = entries[0].actualSql()!!
        assertEquals(2, actual.lineSequence().count())
        assertTrue(actual.contains("ds_0 ::: SELECT * FROM t_user_0"))
        assertTrue(actual.contains("ds_1 ::: SELECT * FROM t_user_1"))
        assertEquals("SELECT * FROM t_user WHERE id = 1", entries[0].restoredSql)
    }

    @Test
    fun `无参数Actual_还原等于原文`() {
        val parser = ShardingSphereLogParser()
        parser.feed(logicLine)
        parser.feed(
            "[INFO] 2026-09-30 17:00:00.003 [main] org.apache.shardingsphere.infra.script - " +
                "Actual SQL: ds_0 ::: SELECT * FROM t_user_0",
        )
        parser.closePending()
        val entries = parser.takeEntries()
        assertEquals(1, entries.size)
        assertTrue(entries[0].params.isEmpty())
        assertEquals(entries[0].rawSql(), entries[0].restoredSql)
        assertEquals("ds_0 ::: SELECT * FROM t_user_0", entries[0].actualSql)
    }

    @Test
    fun `连续语句_下一条Logic闭合上一条`() {
        val parser = ShardingSphereLogParser()
        parser.feed(logicLine)
        parser.feed(actualLine)
        parser.feed(
            "[INFO] 2026-09-30 17:00:00.010 [main] org.apache.shardingsphere.infra.script - " +
                "Logic SQL: UPDATE t_user SET name = ? WHERE id = ?",
        )
        var entries = parser.takeEntries()
        assertEquals("下一条 Logic SQL 闭合上一条（上一条先出）", 1, entries.size)
        assertEquals("SELECT * FROM t_user WHERE id = ?", entries[0].rawSql())
        parser.feed(
            "[INFO] 2026-09-30 17:00:00.011 [main] org.apache.shardingsphere.infra.script - " +
                "Actual SQL: ds_0 ::: UPDATE t_user_0 SET name = ? WHERE id = ? ::: Parameters: [abc, 1]",
        )
        parser.closePending()
        entries = parser.takeEntries()
        assertEquals(1, entries.size)
        assertEquals("UPDATE t_user SET name = ? WHERE id = ?", entries[0].rawSql())
        assertEquals("字符串值无类型标注按启发式加引号", "UPDATE t_user SET name = 'abc' WHERE id = 1", entries[0].restoredSql)
    }

    // ---------- 骨架边界（噪声/未配对/静默冲刷） ----------

    @Test
    fun `噪声行不产条目`() {
        val parser = ShardingSphereLogParser()
        parser.feed("[INFO] 2026-09-30 17:00:00.000 [main] com.example.App - started")
        parser.feed("business log without sql")
        parser.closePending()
        assertTrue(parser.takeEntries().isEmpty())
    }

    @Test
    fun `Actual先于Logic无上下文_丢弃不产条目`() {
        val parser = ShardingSphereLogParser()
        parser.feed(actualLine)
        parser.closePending()
        assertTrue("未配对 Actual 不造条目（等待态配对先例同口径）", parser.takeEntries().isEmpty())
    }

    @Test
    fun `静默冲刷闭合待产出条目`() {
        // master 语义 = clock 注入、restorer 取默认（Kotlin 默认参）；k2j 全参构造须显式
        // 传 LogSqlRestorer()（传 null 会下渗 flushSettled → restore NPE）
        val parser = ShardingSphereLogParser(LogSqlRestorer(), 20000, 1000L, { 0L })
        parser.feed(logicLine)
        parser.feed(actualLine)
        assertTrue(parser.takeEntries().isEmpty())
        // 静默阈值 1s 后冲刷
        val produced = parser.flushSettled(2_000L)
        assertEquals(1, produced)
        assertEquals(1, parser.takeEntries().size)
    }

    // ---------- 注册链判别（第四通道接入 processConsoleLine，摘注册即红） ----------

    private fun newCtx(): BridgeLineContext {
        val cfg = ParserConfig("Preparing:", "Parameters:", true, 20000)
        return BridgeLineContext(
            MyBatisLogParser(cfg.sqlPrefix(), cfg.paramPrefix(), cfg.mixedPrefix(), cfg.maxParseLength()),
            HibernateLogParser(cfg.hibernateSqlPrefix(), cfg.hibernateParamPrefix(), cfg.mixedPrefix(), cfg.maxParseLength()),
            JdbcTemplateLogParser(cfg.jdbcEnabled(), cfg.maxParseLength()),
            ShardingSphereLogParser(),
            { cfg },
            cfg.hibernateEnabled(),
            cfg.shardingEnabled(),
        )
    }

    @Test
    fun `注册链_processConsoleLine产出ShardingSphere条目`() {
        val ctx = newCtx()
        assertTrue(SqlLogConsoleBridgeKt.processConsoleLine(logicLine, ctx, null, {}).isEmpty())
        assertTrue(
            "Actual 行只折叠不产出",
            SqlLogConsoleBridgeKt.processConsoleLine(actualLine, ctx, null, {}).isEmpty(),
        )
        // 下一条 ShardingSphere Logic 行触发上一条闭合并随本行产出
        val entries = SqlLogConsoleBridgeKt.processConsoleLine(
            "[INFO] 2026-09-30 17:00:00.100 [main] org.apache.shardingsphere.infra.script - " +
                "Logic SQL: SELECT * FROM t_order WHERE id = ?",
            ctx,
            null,
            {},
        )
        assertEquals(1, entries.size)
        assertEquals(SqlSource.SHARDINGSPHERE, entries[0].source())
        assertEquals("SELECT * FROM t_user WHERE id = ?", entries[0].rawSql())
        assertTrue("Actual 附注随条目透传", entries[0].actualSql()!!.contains("ds_0"))
    }

    @Test
    fun `注册链_开关关闭零产出`() {
        val cfg = ParserConfig("Preparing:", "Parameters:", true, 20000,
            HibernateLogParser.DEFAULT_SQL_PREFIX, HibernateLogParser.DEFAULT_PARAM_PREFIX, true, true, false)
        val ctx = BridgeLineContext(
            MyBatisLogParser(),
            HibernateLogParser(),
            JdbcTemplateLogParser(true, 20000),
            ShardingSphereLogParser(),
            { cfg },
            true,
            cfg.shardingEnabled(),
        )
        assertTrue(SqlLogConsoleBridgeKt.processConsoleLine(logicLine, ctx, null, {}).isEmpty())
        assertTrue(
            SqlLogConsoleBridgeKt.processConsoleLine(
                "[INFO] 2026-09-30 17:00:00.100 [main] org.apache.shardingsphere.infra.script - " +
                    "Logic SQL: SELECT 1",
                ctx,
                null,
                {},
            ).isEmpty(),
        )
    }
}
