package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-HibDualCapture：show-sql 与 org.hibernate.SQL DEBUG 双路打印的「同语句双副本」场景测试。
 *
 * 真实控制台时序（2026-09-10 demo 字节级抓包）：logger 副本（时间戳前缀行 + 多行块注释 SQL）→
 * `Hibernate: ` 独占行 + 相同多行 SQL（stdout 副本）→ `binding parameter` 绑定行。
 * 修复前每条查询产出「零参数 + 全参数」两条条目（`?` 残留误报的根源）；修复后隔离槽合并为一条。
 *
 * 喂行方式对齐 SqlLogConsoleBridge：逐行喂入并逐行 takeEntries（验证隔离槽在桥接节奏下不漏不并错），
 * 末尾 closePending + takeEntries 收尾（对齐 LogToSqlPanel 粘贴转换的收尾顺序）。
 *
 * k2j 适配：LogSqlEntry/LogSqlParam 为 Java record，访问器用方法调用（params()/restoredSql()/value()），
 * 不合成 Kotlin 属性。
 */
class HibernateLogParserDualCopyTest {

    private val sqlBody = listOf(
        "            count(case when wmsdockboo0_.status='F' then 0 end) as col_0_0_",
        "        from",
        "            wms_dock_booking wmsdockboo0_",
        "        where",
        "            wmsdockboo0_.warehouse_id=? ",
        "            and wmsdockboo0_.created_time>=? ",
        "            and wmsdockboo0_.created_time<? ",
    )

    /** 一次查询执行的完整控制台行：logger 副本 + `Hibernate: ` stdout 副本 + 绑定行。 */
    private fun dualCopyLines(bindings: List<String>): List<String> {
        val loggerPrefix = "2026-09-10 15:26:45.936 DEBUG 66892 --- [nio-8085-exec-1] org.hibernate.SQL                        : "
        val tracePrefix = "2026-09-10 15:26:45.956 TRACE 66892 --- [nio-8085-exec-1] o.h.type.descriptor.sql.BasicBinder      : "
        // format_sql + use_sql_comments 实测形态：注释同行闭合、`select` 跟在 `*/` 之后
        val block = listOf("    /* select count(case ...) */ select") + sqlBody
        return listOf(loggerPrefix) + block +
            listOf("Hibernate: ") + block +
            bindings.map { tracePrefix + it }
    }

    /** 逐行喂入 + 逐行取件（桥接节奏），返回「逐行取件累计」与「收尾后终态」。 */
    private fun feedAllBridgePaced(lines: List<String>): Pair<List<LogSqlEntry>, List<LogSqlEntry>> {
        val parser = HibernateLogParser()
        val incremental = mutableListOf<LogSqlEntry>()
        lines.forEach { line ->
            parser.feed(line)
            incremental += parser.takeEntries()
        }
        parser.closePending()
        return incremental to parser.takeEntries()
    }

    // ------------------------------------------------------------------
    // 双副本合并
    // ------------------------------------------------------------------

    @Test
    fun `双副本带参查询合并为一条全参条目`() {
        val bindings = listOf(
            "binding parameter [1] as [VARCHAR] - [WH01]",
            "binding parameter [2] as [TIMESTAMP] - [2026-01-01T00:00]",
            "binding parameter [3] as [TIMESTAMP] - [2026-12-31T00:00]",
        )
        val (incremental, final) = feedAllBridgePaced(dualCopyLines(bindings))
        assertTrue(
            "stdout 副本到达前零参影子不得经 takeEntries 泄出（逐行取件累计应为空）",
            incremental.isEmpty(),
        )
        assertEquals("同语句双副本应合并为一条", 1, final.size)
        val entry = final.single()
        assertEquals("合并条目应带全部参数", 3, entry.params().size)
        assertEquals("参数值年份应完整", "2026-01-01T00:00", entry.params()[1].value())
        assertTrue("restoredSql 不应残留 ?", '?' !in entry.restoredSql())
        assertTrue("restoredSql 应含 ANSI 时间戳全值（'T' 归一+秒补 00）", "TIMESTAMP '2026-01-01 00:00:00'" in entry.restoredSql())
        val expectedNormalized = "select count(case when wmsdockboo0_.status='F' then 0 end) as col_0_0_ " +
            "from wms_dock_booking wmsdockboo0_ where wmsdockboo0_.warehouse_id=? " +
            "and wmsdockboo0_.created_time>=? and wmsdockboo0_.created_time<?"
        assertEquals(
            "合并条目 rawSql 应为去注释后的完整 SQL（注释剥离 + 空白归一）",
            expectedNormalized,
            entry.rawSql().replace(Regex("\\s+"), " "),
        )
    }

    @Test
    fun `双副本零参查询合并为一条`() {
        val (incremental, final) = feedAllBridgePaced(dualCopyLines(emptyList()))
        assertTrue("零参影子同样不得提前泄出", incremental.isEmpty())
        assertEquals("零参双副本也应合并为一条（今日靠面板去重兜底，现在源头消灭）", 1, final.size)
        assertEquals(0, final.single().params().size)
    }

    // ------------------------------------------------------------------
    // 单副本各形态不回归
    // ------------------------------------------------------------------

    @Test
    fun `仅 stdout 副本两条不同语句不误并`() {
        val lines = listOf(
            "Hibernate: select a from t1 where x=?",
            "Hibernate: select b from t2 where y=?",
        )
        val (incremental, final) = feedAllBridgePaced(lines + emptyList())
        // 两条均零参：第一条在第二条到达时闭合并入列，第二条收尾冲刷
        assertEquals(2, final.size)
        assertEquals("select a from t1 where x=?", final[0].rawSql())
        assertEquals("select b from t2 where y=?", final[1].rawSql())
        assertTrue(incremental.size <= 1) // 第二条至多在收尾才可见，第一条不得提前泄两次
    }

    @Test
    fun `仅 logger 副本带绑定产出单条全参`() {
        val lines = listOf(
            "2026-09-10 15:26:45.936 DEBUG 66892 --- [exec-1] org.hibernate.SQL                        : ",
            "    /* select */ select * from user where id=?",
            "2026-09-10 15:26:45.956 TRACE 66892 --- [exec-1] o.h.t.d.s.BasicBinder : binding parameter [1] as [BIGINT] - [7]",
        )
        val (_, final) = feedAllBridgePaced(lines)
        assertEquals(1, final.size)
        assertEquals(1, final.single().params().size)
        assertEquals("7", final.single().params()[0].value())
    }

    @Test
    fun `双副本但绑定缺失产出单条零参`() {
        val lines = dualCopyLines(emptyList())
        val parser = HibernateLogParser()
        lines.forEach { parser.feed(it) }
        parser.closePending()
        val final = parser.takeEntries()
        assertEquals(1, final.size)
        assertEquals(0, final.single().params().size)
    }

    // ------------------------------------------------------------------
    // 隔离槽边界
    // ------------------------------------------------------------------

    @Test
    fun `不同语句的零参影子不误并且保持产出顺序`() {
        val parser = HibernateLogParser()
        // Q1：logger 副本 + stdout 副本（零参，靠后继语句闭合）
        dualCopyLines(emptyList()).forEach { parser.feed(it) }
        // Q2：另一条不同 SQL 的 stdout 副本（闭合 Q1 双副本）
        parser.feed("Hibernate: select z from t3 where w=?")
        parser.closePending()
        val final = parser.takeEntries()
        assertEquals("Q1 双副本合一条 + Q2 单条", 2, final.size)
        assertTrue("Q1 条目在前（产出顺序保持）", "wms_dock_booking" in final[0].rawSql())
        assertEquals("select z from t3 where w=?", final[1].rawSql())
    }

    @Test
    fun `流末尾无后继语句时收尾冲刷不丢末条目`() {
        val lines = dualCopyLines(
            listOf("binding parameter [1] as [VARCHAR] - [WH01]"),
        )
        val parser = HibernateLogParser()
        lines.forEach { line ->
            parser.feed(line)
            parser.takeEntries()
        }
        // 用户停下（无下一条语句）：closePending + takeEntries（LogToSqlPanel 收尾顺序）
        parser.closePending()
        val final = parser.takeEntries()
        assertEquals("末条目经收尾可见", 1, final.size)
        assertEquals(1, final.single().params().size)
    }
}
