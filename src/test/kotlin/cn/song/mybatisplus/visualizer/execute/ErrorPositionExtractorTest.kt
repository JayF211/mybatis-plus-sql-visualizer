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
    fun test非SQL异常零SQLState不虚报() {
        val info = ErrorPositionExtractor.buildInfo(IllegalStateException("console disposed"), "console disposed")
        assertNull(info.sqlState)
        assertNull(info.errorCode)
        assertNull(info.positionChar)
        assertNull(info.lineInSql)
    }
}
