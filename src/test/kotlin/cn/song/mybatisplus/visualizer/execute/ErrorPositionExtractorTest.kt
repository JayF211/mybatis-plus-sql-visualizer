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
 *
 * T-ELU-02/03/04（2026-09-22）追加：Oracle PL/SQL `ORA-06550: line N, column M:` 中英两种
 * locale 形态（真库原文钉住）、Oracle 纯 SQL 错误无位置不虚报、跨行号放弃归属；
 * `DataSourceSelector.deliverError` 投递收口（富/旧通道互斥、越界钳到提交文本长度、
 * 富通道异常回退旧通道）——与生产同函数，非另写一份断言。
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

    // ---------- extractPosition：Oracle PL/SQL 形态（T-ELU-02，真库取证） ----------

    /**
     * 真库原文（Oracle Free 23 / FREEPDB1 @114.66.55.245:20095，**只读**，ojdbc11 23.3.0.23.09
     * 直连）：`BEGIN nonexistent_proc_xyz(); END;`。同一次取证换 `-Duser.language=en` 得英文形态，
     * 默认（中文 locale）得 [ORACLE_PLSQL_ZH]。两条形态都来自真库，非臆造。
     */
    private val ORACLE_PLSQL_EN =
        "ORA-06550: line 1, column 7:\n" +
            "PLS-00201: identifier 'NONEXISTENT_PROC_XYZ' must be declared\n" +
            "ORA-06550: line 1, column 7:\n" +
            "PL/SQL: Statement ignored"

    /** 真库原文（中文 locale）：`BEGIN UPDATE dbx_test.issue_new SET no_col_zzz = 1; END;`。 */
    private val ORACLE_PLSQL_ZH =
        "ORA-06550: 第 2 行, 第 19 列: \nPL/SQL: ORA-00942: 表或视图  不存在\n" +
            "ORA-06550: 第 2 行, 第 3 列: \nPL/SQL: SQL Statement ignored"

    @Test
    fun testOraclePLSQL位置形态_英文locale() {
        val pos = ErrorPositionExtractor.extractPosition(ORACLE_PLSQL_EN)!!
        assertEquals(1, pos.first)
        // 行号不是字符位置（column 是行内列号，不塞进 positionChar——坐标系诚实）
        assertEquals(false, pos.second)
        assertEquals("oracle-plsql", pos.third)
    }

    @Test
    fun testOraclePLSQL位置形态_中文locale() {
        val pos = ErrorPositionExtractor.extractPosition(ORACLE_PLSQL_ZH)!!
        assertEquals(2, pos.first)
        assertEquals(false, pos.second)
        assertEquals("oracle-plsql", pos.third)
    }

    @Test
    fun testOracle纯SQL错误无位置_不虚报() {
        // 同库同驱动实测：ORA-00942 / ORA-03049 全文不含任何位置标记 ⇒ 提取不出即无位置（诚实降级）
        assertNull(
            ErrorPositionExtractor.extractPosition(
                "ORA-00942: table or view \"DBX_TEST\".\"NO_SUCH_TABLE_XYZ\" does not exist",
            ),
        )
        assertNull(
            ErrorPositionExtractor.extractPosition(
                "ORA-03049: SQL keyword 'X' is not syntactically valid following 'SELECT * FROM dual WHER '",
            ),
        )
    }

    @Test
    fun testOracle跨行号放弃归属() {
        // 同一消息里两个不同行号（多块/多语句）——宁缺毋滥，不归属
        assertNull(
            ErrorPositionExtractor.extractPosition(
                "ORA-06550: line 1, column 7:\nPLS-00201: x\nORA-06550: line 4, column 3:\nPL/SQL: y",
            ),
        )
    }

    // ---------- T-ELU-03/04：投递收口与钳制接线（与生产同函数，非另写一份） ----------

    @Test
    fun test投递收口_越界钳到提交文本长度并记录上界() {
        var got: ExecutionErrorInfo? = null
        DataSourceSelector.deliverError(
            onErrorRich = { got = it },
            onError = { error("富通道在场时不得再投旧通道（会抹掉定位按钮）") },
            cause = java.sql.SQLException("boom", "42000", 1),
            message = "syntax error\n  Position: 5000",
            sqlMaxLength = 120,
        )
        val info = got!!
        assertEquals("越界位置应钳到提交文本长度", 120, info.positionChar)
        // T-ELU-04：投递点传入的上界如实带出（tooltip/MCP 据此标注坐标系）
        assertEquals(120, info.executedSqlLength)
    }

    @Test
    fun test投递收口_无富通道时回退旧通道() {
        var plain: String? = null
        DataSourceSelector.deliverError(null, { plain = it }, null, "boom")
        assertEquals("boom", plain)
    }

    @Test
    fun test投递收口_富通道抛异常时回退旧通道不吞错误() {
        var plain: String? = null
        DataSourceSelector.deliverError({ throw IllegalStateException("rich failed") }, { plain = it }, null, "boom")
        assertEquals("富通道失败必须回退旧通道，不让错误呈现被吞", "boom", plain)
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
