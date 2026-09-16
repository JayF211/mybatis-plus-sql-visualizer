package cn.song.mybatisplus.visualizer.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-HibConc：Hibernate 链路并发配对守卫（用户报「Hibernate 也有这个问题」后独立立项）。
 *
 * 与 T-LogParseConc 同源缺陷：HibernateLogParser 原为单槽状态机，多线程日志按到达序混排时
 * 另一线程的语句行会提前闭合本线程的 SQL 块、绑定行被追加进共享 `pendingParams` → **参数跨线程错挂**。
 *
 * 本轮特殊之处：Hibernate 多了 T-HibDualCapture 双副本合并——多行 SQL 正文与 `Hibernate: `
 * stdout 标志行**都不带日志前缀**（提取不到线程 token）。故分桶需配 MRU 归属策略（无 token 行跟随
 * 最近一次出现 token 的桶），且零参影子隔离槽按桶隔离（并发下不同线程的影子不再互相顶掉）。
 *
 * 时序取自 `HibernateLogParserDualCopyTest` 的字节级抓包形态。
 */
class HibernateLogParseConcurrencyTest {

    private val stampA = "2026-09-16 10:00:00.1"
    private fun loggerLine(seq: String, token: String) =
        "2026-09-16 10:00:00.$seq DEBUG 1 --- [$token] org.hibernate.SQL                        : "

    private fun bindLine(seq: String, token: String, index: Int, value: String) =
        "2026-09-16 10:00:00.$seq TRACE 1 --- [$token] o.h.t.d.s.BasicBinder      : " +
            "binding parameter [$index] as [BIGINT] - [$value]"

    @After
    fun tearDown() {
        LogParseDiag.reset()
    }

    /** 两线程各跑一条双副本查询（交错）→ 两条条目、参数各归其主、双副本各合并为一条。 */
    @Test
    fun `两线程交错双副本_参数各归其主且各合并一条`() {
        val parser = HibernateLogParser()
        listOf(
            loggerLine("100", "nio-exec-A"),
            "    /* select */ select * from table_a where id=?",
            "Hibernate: ",
            "    /* select */ select * from table_a where id=?",
            loggerLine("101", "nio-exec-B"),
            "    /* select */ select * from table_b where id=?",
            "Hibernate: ",
            "    /* select */ select * from table_b where id=?",
            bindLine("102", "nio-exec-A", 1, "7"),
            bindLine("103", "nio-exec-B", 1, "99"),
        ).forEach { parser.feed(it) }
        parser.closePending()
        val entries = parser.takeEntries()

        assertEquals("两线程各一条（双副本各合并为一条，不重复不丢失）", 2, entries.size)
        val a = entries.first { it.rawSql.contains("table_a") }
        val b = entries.first { it.rawSql.contains("table_b") }
        assertEquals("A 的参数是 A 的绑定值", listOf("7"), a.params.map { it.value })
        assertEquals("B 的参数是 B 的绑定值", listOf("99"), b.params.map { it.value })
        assertEquals("A 的 SQL 用 A 的参数还原", "select * from table_a where id=7", a.restoredSql)
        assertEquals("B 的 SQL 用 B 的参数还原", "select * from table_b where id=99", b.restoredSql)
    }

    /**
     * 反向交错：A 的 SQL 块先起头、B 的完整流程插在中间、A 的绑定行最后到。
     * 断言 A 的参数不落到 B 的条目上（旧单槽实现对这一形态必错挂）。
     */
    @Test
    fun `A块起头_B整轮插入_A绑定最后到_不跨线程错挂`() {
        val parser = HibernateLogParser()
        listOf(
            loggerLine("100", "exec-A"),
            "    /* select */ select * from table_a where id=?",
            "Hibernate: ",
            "    /* select */ select * from table_a where id=?",
            loggerLine("101", "exec-B"),
            "    /* select */ select * from table_b where id=?",
            "Hibernate: ",
            "    /* select */ select * from table_b where id=?",
            bindLine("102", "exec-B", 1, "99"),
            bindLine("103", "exec-A", 1, "7"),
        ).forEach { parser.feed(it) }
        parser.closePending()
        val entries = parser.takeEntries()

        assertEquals(2, entries.size)
        assertEquals(
            "B 的参数 99 不得落到 A 的条目上",
            listOf("99"),
            entries.first { it.rawSql.contains("table_b") }.params.map { it.value },
        )
        assertEquals(
            "A 的参数 7 不得落到 B 的条目上",
            listOf("7"),
            entries.first { it.rawSql.contains("table_a") }.params.map { it.value },
        )
    }

    /** 单线程双副本（分桶开启后）仍合并为一条全参条目——T-HibDualCapture 契约不得回归。 */
    @Test
    fun `单线程双副本_分桶下仍合并一条全参`() {
        val parser = HibernateLogParser()
        listOf(
            loggerLine("100", "exec-A"),
            "    /* select */ select * from table_a where id=?",
            "Hibernate: ",
            "    /* select */ select * from table_a where id=?",
            bindLine("101", "exec-A", 1, "7"),
        ).forEach { parser.feed(it) }
        parser.closePending()
        val entries = parser.takeEntries()

        assertEquals(1, entries.size)
        assertEquals(listOf("7"), entries[0].params.map { it.value })
        assertTrue("restoredSql 不应残留 ?", '?' !in entries[0].restoredSql)
    }

    /** 仅 stdout 副本（全程无 token 行）→ 回退共享桶，行为与既有语义一致（不劣化）。 */
    @Test
    fun `仅stdout副本无token历史_回退共享桶`() {
        val parser = HibernateLogParser()
        listOf(
            "Hibernate: select a from t1 where x=?",
            "Hibernate: select b from t2 where y=?",
        ).forEach { parser.feed(it) }
        parser.closePending()
        val entries = parser.takeEntries()

        assertEquals("两条零参语句各自产出，不误并", 2, entries.size)
        assertEquals("select a from t1 where x=?", entries[0].rawSql)
        assertEquals("select b from t2 where y=?", entries[1].rawSql)
    }

    /** 无上下文绑定行计数（T-HC-04：IDLE 态绑定行丢弃不再静默）。 */
    @Test
    fun `无上下文绑定行_计数留痕`() {
        val parser = HibernateLogParser()
        parser.feed(bindLine("100", "exec-A", 1, "7"))
        assertTrue("IDLE 态无上下文绑定行计数递增", LogParseDiag.paramWithoutContext.get() >= 1L)
        assertEquals("不产出条目", 0, parser.takeEntries().size)
    }

    /** 多线程交错但 SQL 文本相同（同一语句被两线程执行）：两条独立条目，参数各自归属。 */
    @Test
    fun `同SQL两线程_各自独立条目`() {
        val parser = HibernateLogParser()
        listOf(
            loggerLine("100", "exec-A"),
            "    /* select */ select * from t where id=?",
            "Hibernate: ",
            "    /* select */ select * from t where id=?",
            bindLine("101", "exec-A", 1, "1"),
            loggerLine("102", "exec-B"),
            "    /* select */ select * from t where id=?",
            "Hibernate: ",
            "    /* select */ select * from t where id=?",
            bindLine("103", "exec-B", 1, "2"),
        ).forEach { parser.feed(it) }
        parser.closePending()
        val entries = parser.takeEntries()

        assertEquals("两线程各一条（影子槽按桶隔离，不互相顶掉）", 2, entries.size)
        assertEquals("A 的值", listOf("1"), entries[0].params.map { it.value })
        assertEquals("B 的值", listOf("2"), entries[1].params.map { it.value })
        assertEquals("select * from t where id=1", entries[0].restoredSql)
        assertEquals("select * from t where id=2", entries[1].restoredSql)
    }
}
