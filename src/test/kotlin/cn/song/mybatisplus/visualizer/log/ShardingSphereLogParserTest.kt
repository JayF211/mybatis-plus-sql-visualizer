package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R10 T-ShardingSphereCompat 第四解析器判别测试（骨架首版，V36 语料采样外部动作缺位）。
 *
 * UI-008 新增真实 5.5.2/H2 原始日志；以下原有夹具为 synthetic，按旧公开样例合成
 * （`Logic SQL: …` / `Actual SQL: <ds> ::: <sql> ::: Parameters: [v, …]`），
 * **不是**真实用户环境采到的双段日志语料——语料到位后的行为细节（参数值含 ", " 的拆分、
 * 4.x 形态、与 MyBatis 行交错的配对语义 V11a）登记在解析器 TODO 与计划 §实施记录，勿当作已验证。
 *
 * 判别红线：实现前本类编译红（ShardingSphereLogParser / SqlSource.SHARDINGSPHERE /
 * LogSqlEntry.actualSql / BridgeLineContext 第四解析器参数均未定义）。
 */
class ShardingSphereLogParserTest {

    // UI-008：真实 ShardingSphere 5.5.2 + H2 2.2.224 main both 的原始输出（非合成 Parameters 形态）。
    private val real552 = """
        18:32:08.911 INFO  [main] ShardingSphere-SQL - Logic SQL: SELECT order_id, reference_code FROM ui_order WHERE order_id = ? AND reference_code = ?
        18:32:08.914 INFO  [main] ShardingSphere-SQL - Actual SQL: ds_0 ::: SELECT order_id, reference_code FROM ui_order_0 WHERE order_id = ? AND reference_code = ? ::: [100, BOOK,100]
        PASS route 100 rows=[100:BOOK,100]
        18:32:08.949 INFO  [main] ShardingSphere-SQL - Logic SQL: SELECT order_id, reference_code FROM ui_order WHERE order_id = ? AND reference_code = ?
        18:32:08.949 INFO  [main] ShardingSphere-SQL - Actual SQL: ds_1 ::: SELECT order_id, reference_code FROM ui_order_1 WHERE order_id = ? AND reference_code = ? ::: [101, BOOK,101]
        PASS route 101 rows=[101:BOOK,101]
        18:32:08.963 INFO  [main] ShardingSphere-SQL - Logic SQL: SELECT order_id, reference_code FROM ui_order WHERE state_code = ? ORDER BY order_id
        18:32:08.963 INFO  [main] ShardingSphere-SQL - Actual SQL: ds_0 ::: SELECT order_id, reference_code FROM ui_order_0 WHERE state_code = ? ORDER BY order_id ::: [1]
        18:32:08.963 INFO  [main] ShardingSphere-SQL - Actual SQL: ds_1 ::: SELECT order_id, reference_code FROM ui_order_1 WHERE state_code = ? ORDER BY order_id ::: [1]
        PASS cross-shard rows=[100:BOOK,100, 101:BOOK,101]
    """.trimIndent().lines()

    @Test
    fun `真实552三查询两分片_逗号保真且不伪造类型`() {
        val parser = ShardingSphereLogParser()
        real552.forEach(parser::feed)
        parser.closePending()
        val entries = parser.takeEntries()
        assertEquals(3, entries.size)
        assertEquals(listOf("100", "BOOK,100"), entries[0].params.map { it.value })
        assertEquals(listOf("101", "BOOK,101"), entries[1].params.map { it.value })
        assertEquals(listOf("1"), entries[2].params.map { it.value })
        assertTrue(entries.flatMap { it.params }.all { it.type == null })
        assertTrue(entries[0].restoredSql.endsWith("order_id = 100 AND reference_code = 'BOOK,100'"))
        assertTrue(entries[1].restoredSql.endsWith("order_id = 101 AND reference_code = 'BOOK,101'"))
        assertTrue(entries[2].restoredSql.endsWith("state_code = 1 ORDER BY order_id"))
        assertEquals(2, entries[2].actualSql!!.lines().size)
        assertTrue(entries[2].actualSql!!.contains("ds_0 :::"))
        assertTrue(entries[2].actualSql!!.contains("ds_1 :::"))
    }

    @Test
    fun `真实格式交错线程_不串参数和路由`() {
        val parser = ShardingSphereLogParser()
        listOf(real552[0].replace("[main]", "[worker-1]"),
            real552[3].replace("[main]", "[worker-2]"),
            real552[4].replace("[main]", "[worker-2]"),
            real552[1].replace("[main]", "[worker-1]")).forEach(parser::feed)
        parser.closePending()
        val entries = parser.takeEntries().associateBy { it.params.first().value }
        assertEquals(2, entries.size)
        assertEquals("BOOK,100", entries["100"]!!.params[1].value)
        assertTrue(entries["100"]!!.actualSql!!.startsWith("ds_0 :::"))
        assertEquals("BOOK,101", entries["101"]!!.params[1].value)
        assertTrue(entries["101"]!!.actualSql!!.startsWith("ds_1 :::"))
    }

    private fun parseActual(suffix: String, sql: String = "SELECT * FROM t WHERE a = ? AND b = ?"): LogSqlEntry {
        val parser = ShardingSphereLogParser()
        parser.feed("Logic SQL: $sql")
        parser.feed("Actual SQL: ds_0 ::: $sql$suffix")
        parser.closePending()
        return parser.takeEntries().single()
    }

    @Test
    fun `未知不完整和歧义参数整组拒绝_原文仍在`() {
        listOf(" ::: [100, BOOK, 100]", " ::: [100]", " ::: [100, BOOK,100",
            " ::: [100, null]", " ::: [100, [a, b]]", " ::: Bindings: [100, abc]",
            " ::: [100, abc] trailing", " ::: [100, ]", " ::: [100, x ::: [abc]]", "").forEach { suffix ->
            val entry = parseActual(suffix)
            assertTrue("不得猜测 $suffix", entry.params.isEmpty())
            assertEquals(entry.rawSql, entry.restoredSql)
            assertTrue(entry.actualSql!!.endsWith(suffix))
        }
    }

    @Test
    fun `逗号空格单参数可由占位数消歧_旧格式并存`() {
        val sql = "SELECT '?' FROM t WHERE name = ? /* ? */"
        assertEquals(listOf("BOOK, 100"), parseActual(" ::: [BOOK, 100]", sql).params.map { it.value })
        assertEquals(listOf("100", "BOOK,100"), parseActual(" ::: Parameters: [100, BOOK,100]").params.map { it.value })
        assertTrue(parseActual(" ::: [100, BOOK,100]", sql).params.single().type == null)
    }

    @Test
    fun `实际占位数不同和分片参数不同不回填逻辑SQL`() {
        val parser = ShardingSphereLogParser()
        parser.feed(real552[0])
        parser.feed(real552[1])
        parser.feed(real552[4])
        parser.closePending()
        val entry = parser.takeEntries().single()
        assertTrue(entry.params.isEmpty())
        assertEquals(entry.rawSql, entry.restoredSql)
        assertEquals(2, entry.actualSql!!.lines().size)
        parser.feed(real552[0])
        parser.feed("18:32:08.914 INFO  [main] ShardingSphere-SQL - Actual SQL: ds_0 ::: SELECT ? ::: [100, BOOK,100]")
        parser.closePending()
        assertTrue(parser.takeEntries().single().params.isEmpty())
    }

    @Test
    fun `真实格式桥分派开关和其余三族互不干扰`() {
        for (enabled in listOf(true, false)) {
            val ctx = newCtx().apply { shardingEnabled = enabled }
            val entries = real552.flatMap { processConsoleLine(it, ctx, null, {}) }.toMutableList()
            ctx.shardingParser.closePending()
            entries += ctx.shardingParser.takeEntries()
            assertEquals(if (enabled) 3 else 0, entries.size)
            val otherLines = listOf(
                "Preparing: SELECT * FROM mb WHERE id = ?", "Parameters: 7(Integer)",
                "Hibernate: SELECT * FROM hb WHERE id = ?",
                "binding parameter [1] as [INTEGER] - [8]",
                "Executing prepared SQL statement [SELECT * FROM jd WHERE id = ?]",
                "Setting SQL statement parameter value: column index 1, parameter value [9], value class [java.lang.Integer], SQL type 4",
            )
            val others = otherLines.flatMap { processConsoleLine(it, ctx, null, {}) }.toMutableList()
            ctx.hibernateParser.closePending()
            ctx.jdbcParser.closePending()
            others += ctx.hibernateParser.takeEntries()
            others += ctx.jdbcParser.takeEntries()
            assertEquals(setOf(SqlSource.MYBATIS, SqlSource.HIBERNATE, SqlSource.JDBC), others.map { it.source }.toSet())
            assertTrue(others.any { it.restoredSql.contains("id = 7") })
            assertTrue(others.any { it.restoredSql.contains("id = 8") })
            assertTrue(others.any { it.restoredSql.contains("id = 9") })
        }
    }

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
        val actual = entries[0].actualSql!!
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
        assertEquals(entries[0].rawSql, entries[0].restoredSql)
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
        assertEquals("SELECT * FROM t_user WHERE id = ?", entries[0].rawSql)
        parser.feed(
            "[INFO] 2026-09-30 17:00:00.011 [main] org.apache.shardingsphere.infra.script - " +
                "Actual SQL: ds_0 ::: UPDATE t_user_0 SET name = ? WHERE id = ? ::: Parameters: [abc, 1]",
        )
        parser.closePending()
        entries = parser.takeEntries()
        assertEquals(1, entries.size)
        assertEquals("UPDATE t_user SET name = ? WHERE id = ?", entries[0].rawSql)
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
        val parser = ShardingSphereLogParser(clock = { 0L })
        parser.feed(logicLine)
        parser.feed(actualLine)
        assertTrue(parser.takeEntries().isEmpty())
        // 静默阈值 1s 后冲刷
        val produced = parser.flushSettled(nowMs = 2_000L)
        assertEquals(1, produced)
        assertEquals(1, parser.takeEntries().size)
    }

    // ---------- 注册链判别（第四通道接入 processConsoleLine，摘注册即红） ----------

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
            shardingParser = ShardingSphereLogParser(),
            configProvider = { cfg },
            shardingEnabled = cfg.shardingEnabled,
        )
    }

    @Test
    fun `注册链_processConsoleLine产出ShardingSphere条目`() {
        val ctx = newCtx()
        assertTrue(processConsoleLine(logicLine, ctx, null, {}).isEmpty())
        assertTrue(
            "Actual 行只折叠不产出",
            processConsoleLine(actualLine, ctx, null, {}).isEmpty(),
        )
        // 下一条 ShardingSphere Logic 行触发上一条闭合并随本行产出
        val entries = processConsoleLine(
            "[INFO] 2026-09-30 17:00:00.100 [main] org.apache.shardingsphere.infra.script - " +
                "Logic SQL: SELECT * FROM t_order WHERE id = ?",
            ctx,
            null,
            {},
        )
        assertEquals(1, entries.size)
        assertEquals(SqlSource.SHARDINGSPHERE, entries[0].source)
        assertEquals("SELECT * FROM t_user WHERE id = ?", entries[0].rawSql)
        assertTrue("Actual 附注随条目透传", entries[0].actualSql!!.contains("ds_0"))
    }

    @Test
    fun `注册链_开关关闭零产出`() {
        val cfg = ParserConfig("Preparing:", "Parameters:", true, 20000, shardingEnabled = false)
        val ctx = BridgeLineContext(
            myBatisParser = MyBatisLogParser(),
            hibernateParser = HibernateLogParser(),
            jdbcParser = JdbcTemplateLogParser(enabled = true),
            shardingParser = ShardingSphereLogParser(),
            configProvider = { cfg },
            shardingEnabled = cfg.shardingEnabled,
        )
        assertTrue(processConsoleLine(logicLine, ctx, null, {}).isEmpty())
        assertTrue(
            processConsoleLine(
                "[INFO] 2026-09-30 17:00:00.100 [main] org.apache.shardingsphere.infra.script - " +
                    "Logic SQL: SELECT 1",
                ctx,
                null,
                {},
            ).isEmpty(),
        )
    }

    // ---------- F66（T-PerfSweep18）：判长走累积长度计数器（行为等价件） ----------

    /**
     * maxParseLength 边界等价锚：Actual 折叠判长与旧「joinToString 整串长度」口径一致——
     * 追加前按已累积长度（含 `\n` 分隔符）判界，越界行不再折叠。
     * F66 为纯性能件（每 Actual 行 O(已累积)→O(1)），无行为判别面：本用例与全类复跑
     * 即「行为等价＋既有守卫」读数；反向摘除计数器回退 joinToString 判长，本用例仍绿（等价即设计目标）。
     */
    @Test
    fun `F66_判长计数器_边界口径与joinToString等价`() {
        // 段长 19/20：第 1 行累积 19（<40 收）；第 2 行追加前累积 19（<40）→ 收后 40；第 3 行追加前 40 ≥40 → 拒收
        // （夹具统一短前缀——Logic/Actual 同为 `[main]` 短前缀形态，threadTokenOf 两边都提得 token、同桶配对；
        //  长日期前缀会把 `[main]` 推出 35 字符窗口、Logic 行提不到 token 落 fallback 桶致跨桶失配）
        val parser = ShardingSphereLogParser(maxParseLength = 40)
        parser.feed("[INFO] 17:00:00.000 [main] s - Logic SQL: SELECT 1")
        parser.feed("[INFO] 17:00:00.001 [main] s - Actual SQL: 0123456789012345678")
        parser.feed("[INFO] 17:00:00.002 [main] s - Actual SQL: abcdefghijabcdefghij")
        parser.feed("[INFO] 17:00:00.003 [main] s - Actual SQL: rejected-line-!!")
        parser.closePending()
        val entries = parser.takeEntries()
        assertEquals(1, entries.size)
        val lines = entries[0].actualSql!!.split("\n")
        assertEquals("边界口径等价：恰收前两行（第三行追加前累积 40≥40 拒收）", 2, lines.size)
        assertEquals("0123456789012345678", lines[0])
        assertEquals("abcdefghijabcdefghij", lines[1])
    }

    /** 闭合后计数器复位：跨语句第二条的 Actual 折叠重新起算（不复用上一条的累积值）。 */
    @Test
    fun `F66_计数器闭合复位_下一条语句重新起算`() {
        val parser = ShardingSphereLogParser(maxParseLength = 40)
        parser.feed("[INFO] 17:00:00.000 [main] s - Logic SQL: SELECT 1")
        parser.feed("[INFO] 17:00:00.001 [main] s - Actual SQL: 0123456789012345678")
        parser.feed("[INFO] 17:00:00.002 [main] s - Actual SQL: abcdefghijabcdefghij")
        // 下一条 Logic 闭合上一条并开启新收集
        parser.feed("[INFO] 17:00:00.010 [main] s - Logic SQL: SELECT 2")
        parser.feed("[INFO] 17:00:00.011 [main] s - Actual SQL: fresh-line-ok")
        parser.closePending()
        val entries = parser.takeEntries()
        assertEquals(2, entries.size)
        assertEquals("fresh-line-ok", entries[1].actualSql)
    }
}
