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
        assertEquals(SourceMarkerData("com/x.java", 5), ctx.pendingSource)
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

    // ---------- T-IntrUpg-01：拦截器自位污染标记防线 ----------

    @Test
    fun `自位污染标记不暂存_条目无位置`() {
        val ctx = newCtx()
        // 旧拷贝件上报的自位标记（真机截图形态：SqlSourceInterceptor.java:62）
        assertTrue(processConsoleLine("SQLVIS-SOURCE|cn/song/mpjdemo/config/SqlSourceInterceptor.java|62", ctx, null, {}).isEmpty())
        assertNull("防线：自位标记不得暂存", ctx.pendingSource)
        processConsoleLine("==>  Preparing: SELECT * FROM t", ctx, null, {})
        val entries = processConsoleLine("==>  Parameters: 1(Long)", ctx, null, {})
        assertEquals(1, entries.size)
        assertNull("自位污染位置不落条目（落无位置引导链路）", entries[0].sourceFile)
    }

    @Test
    fun `防线不粘滞_自位标记后正常标记照常生效`() {
        val ctx = newCtx()
        processConsoleLine("SQLVIS-SOURCE|cn/song/config/SqlSourceInterceptor.java|62", ctx, null, {})
        processConsoleLine("SQLVIS-SOURCE|cn/song/service/UserService.java|87", ctx, null, {})
        processConsoleLine("==>  Preparing: SELECT * FROM t", ctx, null, {})
        val entries = processConsoleLine("==>  Parameters: 1(Long)", ctx, null, {})
        assertEquals("cn/song/service/UserService.java", entries[0].sourceFile)
        assertEquals(87, entries[0].sourceLine)
    }

    // ---------- M56 T-M56-00：标记第 4/5 段 → mapperStatement/requestTag ----------

    @Test
    fun 四段标记_mapper语句id写入条目() {
        val ctx = newCtx()
        processConsoleLine("SQLVIS-SOURCE|a/A.java|1|com.a.mapper.AMapper.selectById", ctx, null, {})
        processConsoleLine("==>  Preparing: SELECT * FROM t1", ctx, null, {})
        val entries = processConsoleLine("==>  Parameters: 1(Long)", ctx, null, {})
        assertEquals(1, entries.size)
        assertEquals("com.a.mapper.AMapper.selectById", entries[0].mapperStatement)
        assertNull(entries[0].requestTag)
    }

    @Test
    fun 五段标记_mapper语句id与请求标签写入条目() {
        val ctx = newCtx()
        processConsoleLine("SQLVIS-SOURCE|a/A.java|1|com.a.mapper.AMapper.selectById|req-7", ctx, null, {})
        processConsoleLine("==>  Preparing: SELECT * FROM t1", ctx, null, {})
        val entries = processConsoleLine("==>  Parameters: 1(Long)", ctx, null, {})
        assertEquals(1, entries.size)
        assertEquals("com.a.mapper.AMapper.selectById", entries[0].mapperStatement)
        assertEquals("req-7", entries[0].requestTag)
    }

    // ---------- T-LogDedupeMpMark：MP 分页 COUNT 标记逐条判定 ----------

    /** JdbcTemplate named 单行（repository 层 TRACE 直打）。 */
    private fun namedLine(sql: String, params: String) =
        "2026-09-08 09:38:03.587 [TID: null] [cache-preheat-1] TRACE com.a.b.impl.dml.CCCRepositoryJdbcImpl Line:243 - sql = $sql , parameters = {$params}"

    @Test
    fun `COUNT副本与数据查询同批入列_仅COUNT标记MP分页`() {
        val ctx = newCtx()
        // prepared COUNT 进入 pending（双路日志第二路）
        processConsoleLine(
            "2026-09-08 09:38:03.587 [TID: null] [cache-preheat-1] DEBUG org.springframework.jdbc.core.JdbcTemplate Line:643 - Executing prepared SQL statement [SELECT COUNT(*) as count FROM ipm_RunPlanProject  WHERE ( (\"deleted\" = ?) )  ]",
            ctx, null, {},
        )
        processConsoleLine(
            "2026-09-08 09:38:03.588 [TID: null] [cache-preheat-1] TRACE org.springframework.jdbc.core.StatementCreatorUtils Line:227 - Setting SQL statement parameter value: column index 1, parameter value [0], value class [java.lang.Integer], SQL type unknown",
            ctx, null, {},
        )
        // named 数据行触发 COUNT 副本闭合 → 批内两条（COUNT prepared 副本 + named 数据副本）
        val entries = processConsoleLine(
            namedLine("SELECT \"owner\" FROM ipm_RunPlanProject  WHERE ( (\"deleted\" = :param1) )  ", "param1=0"),
            ctx, null, {},
        )
        assertEquals(2, entries.size)
        assertEquals(SourceMarkers.MP_PAGINATION_COUNT, entries[0].sourceFile)
        assertNull("与 COUNT 同批的数据查询不得误标 MP 分页计数", entries[1].sourceFile)
    }

    @Test
    fun `数据副本与COUNT同批闭合_数据侧不误标`() {
        val ctx = newCtx()
        // prepared 数据查询进入 pending
        processConsoleLine(
            "2026-09-08 09:38:03.587 [TID: null] [cache-preheat-1] DEBUG org.springframework.jdbc.core.JdbcTemplate Line:643 - Executing prepared SQL statement [SELECT \"owner\" FROM ipm_RunPlanProject  WHERE ( (\"deleted\" = ?) )  ]",
            ctx, null, {},
        )
        processConsoleLine(
            "2026-09-08 09:38:03.588 [TID: null] [cache-preheat-1] TRACE org.springframework.jdbc.core.StatementCreatorUtils Line:227 - Setting SQL statement parameter value: column index 1, parameter value [0], value class [java.lang.Integer], SQL type unknown",
            ctx, null, {},
        )
        // named COUNT 行闭合 prepared 数据副本 → 批内 [数据副本, COUNT named]
        val entries = processConsoleLine(
            namedLine("SELECT COUNT(*) as count FROM ipm_RunPlanProject  WHERE ( (\"deleted\" = :param1) )  ", "param1=0"),
            ctx, null, {},
        )
        assertEquals(2, entries.size)
        assertNull("与 COUNT 同批闭合的数据查询不得误标", entries[0].sourceFile)
        assertEquals(SourceMarkers.MP_PAGINATION_COUNT, entries[1].sourceFile)
    }

    @Test
    fun `纯COUNT批次照常标记MP分页`() {
        val ctx = newCtx()
        val entries = processConsoleLine(
            namedLine("SELECT COUNT(*) as count FROM ipm_RunPlanProject WHERE ( (\"deleted\" = :param1) )", "param1=0"),
            ctx, null, {},
        )
        assertEquals(1, entries.size)
        assertEquals(SourceMarkers.MP_PAGINATION_COUNT, entries[0].sourceFile)
    }

    @Test
    fun `多行JdbcTemplate语句端到端捕获并标记MP分页`() {
        val ctx = newCtx()
        // 用户反馈样本（2026-09-08）：TLog TID 前缀 + SQL 跨行 + `]` 独占末行
        val sample = listOf(
            "2026-09-08 11:21:11.066 [TID: AFFCDDCD81F34B929A55E587382D58E8] [http-nio-0.0.0.0-6608-exec-5] DEBUG org.springframework.jdbc.core.JdbcTemplate Line:711 - Executing prepared SQL query",
            "2026-09-08 11:21:11.066 [TID: AFFCDDCD81F34B929A55E587382D58E8] [http-nio-0.0.0.0-6608-exec-5] DEBUG org.springframework.jdbc.core.JdbcTemplate Line:643 - Executing prepared SQL statement [SELECT COUNT(1)",
            "        FROM ipm_ImplementTask t",
            "        WHERE t.deleted = 0",
            "        AND EXISTS (SELECT 1 FROM ipm_ProjectSubject p WHERE p.id = t.projectId AND p.deleted = 0)",
            "        AND (t.subjectId IS NULL OR t.subjectId = '' OR",
            "             EXISTS (SELECT 1 FROM ipm_ProjectSubject s WHERE s.id = t.subjectId AND s.deleted = 0))",
            "                    AND t.projectId IN (",
            "                SELECT pc.id FROM ipm_ProjectSubject pc",
            "                WHERE pc.deleted = 0 AND pc.projectClassification IN (",
            "                     '80a9971836664x3091xaa53b78ae6837'",
            "                )",
            "            )",
            "]",
        )
        // 收集期间逐行零产出
        sample.forEach { line -> assertTrue(processConsoleLine(line, ctx, null, {}).isEmpty()) }
        // 下一条语句行冲刷出多行 COUNT 条目；named 行自身也产出 → 批内两条（与双路日志批序一致）
        val entries = processConsoleLine(
            namedLine("SELECT \"owner\" FROM ipm_ProjectSubject WHERE ( (\"deleted\" = :param1) )  ", "param1=0"),
            ctx, null, {},
        )
        assertEquals(2, entries.size)
        assertEquals(SourceMarkers.MP_PAGINATION_COUNT, entries[0].sourceFile)
        assertNull("同批 named 数据查询不得误标 MP 分页计数", entries[1].sourceFile)
        assertTrue(entries[0].restoredSql.startsWith("SELECT COUNT(1)"))
        assertTrue("跨行结构在还原文本中保留", entries[0].restoredSql.contains("\n        FROM ipm_ImplementTask t"))
    }
}
