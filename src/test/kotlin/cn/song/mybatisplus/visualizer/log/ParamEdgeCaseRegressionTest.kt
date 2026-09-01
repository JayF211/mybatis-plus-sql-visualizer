package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 参数拼接边界回归集（M37.0 / P-立项-06 O-2）。
 *
 * 场景来源：竞品评论区普遍翻车点（值含逗号/空格/单引号、日期时间边界、IN 引号、null、值含问号），
 * 沉淀为显式回归用例，防止解析器演进时回归。三族解析器各跑适用子集 + Restorer 层矩阵。
 *
 * 断言方式：restore 后 SQL 字面量精确匹配（非 contains）。
 */
class ParamEdgeCaseRegressionTest {

    private val restorer = LogSqlRestorer()

    private fun render(value: String, type: String? = null): String =
        restorer.renderParam(LogSqlParam(value, type))

    private fun feedMyBatis(vararg lines: String): List<LogSqlEntry> {
        val p = MyBatisLogParser()
        lines.forEach { p.feed(it) }
        p.closePending()
        return p.takeEntries()
    }

    private fun feedHibernate(vararg lines: String): List<LogSqlEntry> {
        val p = HibernateLogParser()
        lines.forEach { p.feed(it) }
        p.closePending()
        return p.takeEntries()
    }

    private fun feedJdbc(vararg lines: String): List<LogSqlEntry> {
        val p = JdbcTemplateLogParser()
        lines.forEach { p.feed(it) }
        p.closePending()
        return p.takeEntries()
    }

    private fun jdbcLine(text: String, level: String = "DEBUG"): String =
        "2026-08-24 15:46:42.444 [TID: null] [worker-1] $level org.springframework.jdbc.core.JdbcTemplate Line:643 - $text"

    private fun jdbcTrace(text: String): String =
        "2026-08-24 15:46:42.444 [TID: null] [worker-1] TRACE org.springframework.jdbc.core.StatementCreatorUtils Line:227 - $text"

    // ========== Restorer 层矩阵 ==========

    @Test
    fun `无标注值含空格启发式引号`() {
        assertEquals("'hello world'", render("hello world"))
        assertEquals("'2025-01-01 12:00:00'", render("2025-01-01 12:00:00"))
    }

    @Test
    fun `无标注值含逗号不切碎`() {
        assertEquals("'a, b'", render("a, b"))
    }

    @Test
    fun `无标注值含问号`() {
        assertEquals("'what?'", render("what?"))
    }

    @Test
    fun `时间类型带空格分隔加引号`() {
        assertEquals("'2025-01-01 12:00:00'", render("2025-01-01 12:00:00", "Timestamp"))
        assertEquals("'2025-01-01 12:00:00'", render("2025-01-01 12:00:00", "java.sql.Timestamp"))
        assertEquals("'2025-01-01'", render("2025-01-01", "LocalDate"))
    }

    @Test
    fun `IN 列表多值逐个加引号`() {
        val restored = restorer.restore(
            "SELECT * FROM t WHERE id IN (?, ?, ?)",
            listOf(LogSqlParam("1", "Long"), LogSqlParam("a", "String"), LogSqlParam(null, null)),
        )
        assertEquals("SELECT * FROM t WHERE id IN (1, 'a', NULL)", restored)
    }

    // ========== MyBatis 解析器端到端 ==========

    @Test
    fun `MP 无类型标注值含空格`() {
        val entries = feedMyBatis(
            "==>  Preparing: SELECT * FROM t WHERE remark = ?",
            "==> Parameters: hello world",
        )
        assertEquals("SELECT * FROM t WHERE remark = 'hello world'", entries[0].restoredSql)
    }

    @Test
    fun `MP 多个无标注值含空格按逗号切分`() {
        val entries = feedMyBatis(
            "==>  Preparing: SELECT * FROM t WHERE a = ? AND b = ?",
            "==> Parameters: hello world, foo bar",
        )
        assertEquals("SELECT * FROM t WHERE a = 'hello world' AND b = 'foo bar'", entries[0].restoredSql)
    }

    @Test
    fun `MP 无类型标注日期时间含空格`() {
        val entries = feedMyBatis(
            "==>  Preparing: SELECT * FROM t WHERE created > ?",
            "==> Parameters: 2025-01-01 12:00:00",
        )
        assertEquals("SELECT * FROM t WHERE created > '2025-01-01 12:00:00'", entries[0].restoredSql)
    }

    @Test
    fun `MP Timestamp 类型值含空格`() {
        val entries = feedMyBatis(
            "==>  Preparing: SELECT * FROM t WHERE created > ?",
            "==> Parameters: 2025-01-01 12:00:00.0(Timestamp)",
        )
        assertEquals("SELECT * FROM t WHERE created > '2025-01-01 12:00:00.0'", entries[0].restoredSql)
    }

    @Test
    fun `MP IN 多值带类型标注`() {
        val entries = feedMyBatis(
            "==>  Preparing: SELECT * FROM t WHERE name IN (?, ?)",
            "==> Parameters: a(String), b(String)",
        )
        assertEquals("SELECT * FROM t WHERE name IN ('a', 'b')", entries[0].restoredSql)
    }

    @Test
    fun `MP 值含问号`() {
        val entries = feedMyBatis(
            "==>  Preparing: SELECT * FROM t WHERE title = ?",
            "==> Parameters: what?(String)",
        )
        assertEquals("SELECT * FROM t WHERE title = 'what?'", entries[0].restoredSql)
    }

    @Test
    fun `MP 值含逗号与空格混合`() {
        val entries = feedMyBatis(
            "==>  Preparing: SELECT * FROM t WHERE addr = ?",
            "==> Parameters: No.1, Main St(String)",
        )
        assertEquals("SELECT * FROM t WHERE addr = 'No.1, Main St'", entries[0].restoredSql)
    }

    // ========== Hibernate 解析器端到端 ==========

    @Test
    fun `Hibernate TIMESTAMP 值含空格`() {
        val entries = feedHibernate(
            "Hibernate: select u0_.id from users u0_ where u0_.created>?",
            "binding parameter [1] as [TIMESTAMP] - [2025-01-01 12:00:00.0]",
        )
        assertEquals(
            "select u0_.id from users u0_ where u0_.created>'2025-01-01 12:00:00.0'",
            entries[0].restoredSql,
        )
    }

    @Test
    fun `Hibernate VARCHAR 值含逗号`() {
        val entries = feedHibernate(
            "Hibernate: select u0_.id from users u0_ where u0_.tags=?",
            "binding parameter [1] as [VARCHAR] - [a,b]",
        )
        assertEquals("select u0_.id from users u0_ where u0_.tags='a,b'", entries[0].restoredSql)
    }

    @Test
    fun `Hibernate VARCHAR 值含空格`() {
        val entries = feedHibernate(
            "Hibernate: select u0_.id from users u0_ where u0_.name=?",
            "binding parameter [1] as [VARCHAR] - [hello world]",
        )
        assertEquals("select u0_.id from users u0_ where u0_.name='hello world'", entries[0].restoredSql)
    }

    @Test
    fun `Hibernate 值含单引号转义`() {
        val entries = feedHibernate(
            "Hibernate: select u0_.id from users u0_ where u0_.name=?",
            "binding parameter [1] as [VARCHAR] - [O'Brien]",
        )
        assertEquals("select u0_.id from users u0_ where u0_.name='O''Brien'", entries[0].restoredSql)
    }

    // ========== JdbcTemplate 解析器端到端 ==========

    @Test
    fun `JDBC String 值含空格`() {
        val entries = feedJdbc(
            jdbcLine("Executing prepared SQL statement [SELECT * FROM t WHERE remark = ?]"),
            jdbcTrace("Setting SQL statement parameter value: column index 1, parameter value [hello world], value class [java.lang.String], SQL type unknown"),
        )
        assertEquals("SELECT * FROM t WHERE remark = 'hello world'", entries[0].restoredSql)
    }

    @Test
    fun `JDBC Timestamp 值含空格`() {
        val entries = feedJdbc(
            jdbcLine("Executing prepared SQL statement [SELECT * FROM t WHERE created > ?]"),
            jdbcTrace("Setting SQL statement parameter value: column index 1, parameter value [2025-01-01 12:00:00.0], value class [java.sql.Timestamp], SQL type unknown"),
        )
        assertEquals("SELECT * FROM t WHERE created > '2025-01-01 12:00:00.0'", entries[0].restoredSql)
    }

    @Test
    fun `JDBC null 参数渲染 NULL`() {
        val entries = feedJdbc(
            jdbcLine("Executing prepared SQL statement [SELECT * FROM t WHERE remark = ?]"),
            jdbcTrace("Setting SQL statement parameter value: column index 1, parameter value [null], value class [java.lang.String], SQL type unknown"),
        )
        assertEquals("SELECT * FROM t WHERE remark = NULL", entries[0].restoredSql)
    }

    @Test
    fun `JDBC IN 多参数按列序还原`() {
        val entries = feedJdbc(
            jdbcLine("Executing prepared SQL statement [SELECT * FROM t WHERE id IN (?, ?)]"),
            jdbcTrace("Setting SQL statement parameter value: column index 1, parameter value [a], value class [java.lang.String], SQL type unknown"),
            jdbcTrace("Setting SQL statement parameter value: column index 2, parameter value [b c], value class [java.lang.String], SQL type unknown"),
        )
        assertEquals("SELECT * FROM t WHERE id IN ('a', 'b c')", entries[0].restoredSql)
    }
}
