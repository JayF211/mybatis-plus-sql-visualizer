package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-ErrorLocate（N-18）T-EL-01：驱动错误位置提取表驱动。
 *
 * 覆盖：PG `Position: N`（pgjdbc 拼 message 形态）/ MySQL `at line N` / `Line: N` 形态、
 * marker 单一性门控（多信号放弃归属）、越界钳制、无信号（连接类错误）降级 null、
 * SQLException 链 SQLState/errorCode 提取（cause 链 + nextException 链）。
 */
class ErrorPositionExtractorTest {

    // ---------- extractPosition：PG Position 形态 ----------

    @Test
    fun testPGPosition提取() {
        val msg = "ERROR: column \"naem\" does not exist\n  Position: 42"
        val pos = ErrorPositionExtractor.extractPosition(msg)!!
        assertEquals(42, pos.first)
        assertEquals(true, pos.second)
        assertEquals("pg-position", pos.third)
    }

    // ---------- extractPosition：MySQL at line 形态 ----------

    @Test
    fun testMySQLAtLine提取() {
        val msg = "You have an error in your SQL syntax; check the manual... near 'FORM t' at line 1"
        val pos = ErrorPositionExtractor.extractPosition(msg)!!
        assertEquals(1, pos.first)
        assertEquals(false, pos.second)
        assertEquals("mysql-at-line", pos.third)
    }

    // ---------- extractPosition：Line: 形态 ----------

    @Test
    fun testLineColon提取() {
        val pos = ErrorPositionExtractor.extractPosition("syntax error\nLine: 7")!!
        assertEquals(7, pos.first)
        assertEquals(false, pos.second)
        assertEquals("line", pos.third)
    }

    // ---------- marker 单一性门控：多信号放弃 ----------

    @Test
    fun test多Marker放弃归属() {
        // Position 与 at line 并存（跨驱动混排/日志拼接场景）——宁缺毋滥
        assertNull(ErrorPositionExtractor.extractPosition("Position: 5 ... at line 2"))
        // 同形态两处不同值
        assertNull(ErrorPositionExtractor.extractPosition("Position: 5 and Position: 9"))
    }

    @Test
    fun test无Marker返回null() {
        // 连接类错误（基建语句不带位置——DBX postgres.rs:1501 同款防误指）
        assertNull(ErrorPositionExtractor.extractPosition("Connection refused: no further information"))
        assertNull(ErrorPositionExtractor.extractPosition("Communications link failure"))
    }

    // ---------- 越界钳制 ----------

    @Test
    fun test越界钳制到SQL长度() {
        val pos = ErrorPositionExtractor.extractPosition("error at Position: 5000", sqlMaxLength = 120)!!
        assertEquals(120, pos.first)
        // 钳后 <=0（SQL 长度非法）→ null
        assertNull(ErrorPositionExtractor.extractPosition("Position: 3", sqlMaxLength = 0))
    }

    // ---------- T-ErrorLocateUsability T-ELU-02（O-49）：Oracle 位置形态 ----------

    /**
     * 真库实测原文钉住（Oracle Free 23 / FREEPDB1，DBX 通道 2026-09-22，只读）。
     *
     * 实测三例证明 N 是**出错记号起点减一**（0-based 偏移），本提取器契约是 1-based
     * → 归属值 = N+1：`SELECT * FROM t` 里表名在 1-based 第 15 位 → N=14 → 15。
     */
    @Test
    fun testOracle位置提取_真库原文() {
        val msg = "ORA-00942: table or view \"DBX_TEST\".\"NO_SUCH_TABLE_XYZ\" does not exist\n" +
            " error occur at position: 23"
        val pos = ErrorPositionExtractor.extractPosition(msg)!!
        assertEquals("N=23 是 0-based 记号偏移 → 归属 1-based 24", 24, pos.first)
        assertEquals(true, pos.second)
        assertEquals("oracle-position", pos.third)
    }

    @Test
    fun testOracle位置提取_记号在语句首位() {
        // 实测：`FROM dbx_test.issue_new` → `ORA-00900: invalid SQL statement` + position: 0
        val msg = "ORA-00900: invalid SQL statement\n error occur at position: 0"
        val pos = ErrorPositionExtractor.extractPosition(msg)!!
        assertEquals("N=0 → 1-based 第 1 位", 1, pos.first)
        assertEquals("oracle-position", pos.third)
    }

    /**
     * **标签与取值都不再落到 PG 分支**（改前缺陷留证）：Oracle 形态里含 `position: N`，
     * 而 PG 的 `\bPosition:` 会顺带命中它——改前 Oracle 位置被标成 `pg-position` 且按
     * 1-based 直接报出（恒差一位）。本用例把「遮蔽 Oracle 段」这条修复分支钉住。
     */
    @Test
    fun testOracle形态不被PG分支误吞() {
        val msg = "ORA-00936: missing expression\n error occur at position: 7"
        val pos = ErrorPositionExtractor.extractPosition(msg)!!
        assertEquals("oracle-position", pos.third)
        assertEquals("N=7 → 8（不是 7）", 8, pos.first)
    }

    /** 既有三种 marker 行为不变（Oracle 形态不在场时逐字不变）。 */
    @Test
    fun testOracle形态不在场时既有marker不变() {
        val pg = ErrorPositionExtractor.extractPosition("ERROR: x\n  Position: 42")!!
        assertEquals(42, pg.first)
        assertEquals("pg-position", pg.third)
        val multi = ErrorPositionExtractor.extractPosition("Position: 5 ... at line 2")
        assertNull("多 marker 仍放弃归属", multi)
    }

    /** Oracle 位置同样受越界钳制（与其它 marker 同口径）。 */
    @Test
    fun testOracle位置越界钳制() {
        val msg = "ORA-00942: nope\n error occur at position: 900"
        assertEquals(50, ErrorPositionExtractor.extractPosition(msg, sqlMaxLength = 50)!!.first)
    }

    // ---------- T-ELU-03（O-49）：被执行文本长度接线（钳制生产生效的前提） ----------

    /**
     * `executedSqlLength` 是「调用方确实传了被执行文本长度」的结构化证据——非 null 即接线到位。
     * 改前四处投递点全都不传，钳制只在单测生效（本字段恒 null）。
     */
    @Test
    fun test被执行文本长度随富信息结构化落字段() {
        val withLen = ErrorPositionExtractor.buildInfo(null, "x\n  Position: 9999", sqlMaxLength = 120)
        assertEquals(120, withLen.executedSqlLength)
        assertEquals("位置被钳到被执行文本长度", 120, withLen.positionChar)
        val without = ErrorPositionExtractor.buildInfo(null, "x\n  Position: 9999")
        assertNull("不传长度 → 字段为 null（不钳制）", without.executedSqlLength)
        assertEquals(9999, without.positionChar)
    }

    // ---------- buildInfo：SQLException 链提取 ----------

    @Test
    fun testSQLState与错误码提取() {
        val se = java.sql.SQLException("dup key", "23000", 1062)
        val info = ErrorPositionExtractor.buildInfo(se, se.message ?: "")
        assertEquals("23000", info.sqlState)
        assertEquals(1062, info.errorCode)
    }

    @Test
    fun test包装异常的cause链穿透() {
        val se = java.sql.SQLException("deadlock detected", "40P01", 1213)
        val wrapped = RuntimeException("execution failed", se)
        val info = ErrorPositionExtractor.buildInfo(wrapped, "execution failed")
        assertEquals("40P01", info.sqlState)
        assertEquals(1213, info.errorCode)
    }

    @Test
    fun testNextException链提取() {
        val head = java.sql.SQLException("batch head failed", "HY000", 1)
        head.nextException = java.sql.SQLException("second", "23000", 1062)
        val info = ErrorPositionExtractor.buildInfo(head, "batch head failed")
        // head 自身 sqlState 非空优先；errorCode 非空优先——next 链只补空位
        assertEquals("HY000", info.sqlState)
        assertEquals(1, info.errorCode)
        val head2 = java.sql.SQLException("batch head failed", "", 0)
        head2.nextException = java.sql.SQLException("second", "23000", 1062)
        val info2 = ErrorPositionExtractor.buildInfo(head2, "batch head failed")
        assertEquals("23000", info2.sqlState)
        assertEquals(1062, info2.errorCode)
    }

    @Test
    fun test富信息位置字段() {
        val se = java.sql.SQLException("column \"x\" does not exist\n  Position: 18", "42703", 0)
        val info = ErrorPositionExtractor.buildInfo(se, se.message ?: "", sqlMaxLength = 60)
        assertEquals(18, info.positionChar)
        assertNull(info.lineInSql)
        assertEquals("pg-position", info.positionSource)
    }

    @Test
    fun test富信息Oracle位置字段() {
        val se = java.sql.SQLException(
            "ORA-00942: table or view \"DBX_TEST\".\"NO_SUCH_TABLE_XYZ\" does not exist\n error occur at position: 23",
            "42000",
            942,
        )
        val info = ErrorPositionExtractor.buildInfo(se, se.message ?: "", sqlMaxLength = 500)
        assertEquals(24, info.positionChar)
        assertNull(info.lineInSql)
        assertEquals("oracle-position", info.positionSource)
        assertEquals(500, info.executedSqlLength)
    }

    @Test
    fun test非SQL异常零SQLState不虚报() {
        val info = ErrorPositionExtractor.buildInfo(IllegalStateException("console disposed"), "console disposed")
        assertNull(info.sqlState)
        assertNull(info.errorCode)
        assertNull(info.positionChar)
        assertNull(info.lineInSql)
    }
}
