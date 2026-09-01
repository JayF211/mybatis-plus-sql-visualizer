package cn.song.mybatisplus.visualizer.log;

import org.junit.Assert.assertEquals;
import org.junit.Assert.assertNull;
import org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;

/**
 * Spring JdbcTemplate 控制台日志解析器单元测试（M47）。
 *
 * 测试用例贴近真实 JdbcTemplate 日志输出（DEBUG SQL 行 + TRACE 参数行）。
 */
public class JdbcTemplateLogParserTest {

    private static JdbcTemplateLogParser parser() {
        return new JdbcTemplateLogParser();
    }

    private static List<LogSqlEntry> feedAll(JdbcTemplateLogParser p, String... lines) {
        for (String line : lines) {
            p.feed(line);
        }
        p.closePending();
        return p.takeEntries();
    }

    /** 完整日志框架前缀行（时间戳+线程+类名+行号）。 */
    private static String withPrefix(String text) {
        return "2026-08-24 15:46:42.444 [TID: null] [cache-preheat-1] DEBUG org.springframework.jdbc.core.JdbcTemplate Line:643 - " + text;
    }

    private static String withTracePrefix(String text) {
        return "2026-08-24 15:46:42.444 [TID: null] [cache-preheat-1] TRACE org.springframework.jdbc.core.StatementCreatorUtils Line:227 - " + text;
    }

    // ========== 基础 SQL 行识别 ==========

    @Test
    public void testPreparedStatementWith2Params() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            withPrefix("Executing prepared SQL query"),
            withPrefix("Executing prepared SQL statement [SELECT \"owner\", \"modifiedTime\" FROM ipm_RunPlanProject WHERE ((\"deleted\" = ?) AND (\"runPlanId\" = ?))]"),
            withTracePrefix("Setting SQL statement parameter value: column index 1, parameter value [0], value class [java.lang.Integer], SQL type unknown"),
            withTracePrefix("Setting SQL statement parameter value: column index 2, parameter value [7d0fb6b845074c4a89f56480077fbf51], value class [java.lang.String], SQL type unknown"));
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).rawSql().contains("SELECT"));
        assertTrue(entries.get(0).rawSql().contains("ipm_RunPlanProject"));
        assertEquals(SqlSource.JDBC, entries.get(0).source());
        assertEquals(2, entries.get(0).params().size());
        // 参数按 column index 排序：index 1 → Integer 0（裸），index 2 → String（引号）
        assertEquals("0", entries.get(0).params().get(0).value());
        assertEquals("Integer", entries.get(0).params().get(0).type());
        assertEquals("7d0fb6b845074c4a89f56480077fbf51", entries.get(0).params().get(1).value());
        assertEquals("String", entries.get(0).params().get(1).type());
        // 还原 SQL 验证
        assertTrue(entries.get(0).restoredSql().contains("0"));
        assertTrue(entries.get(0).restoredSql().contains("'7d0fb6b845074c4a89f56480077fbf51'"));
    }

    @Test
    public void testNoParamPreparedStatement() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            withPrefix("Executing prepared SQL statement [SELECT 1]"));
        assertEquals(1, entries.size());
        assertEquals("SELECT 1", entries.get(0).rawSql());
        assertTrue(entries.get(0).params().isEmpty());
        assertEquals(SqlSource.JDBC, entries.get(0).source());
    }

    @Test
    public void testExecutingPreparedSqlQueryNoSqlIgnored() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            withPrefix("Executing prepared SQL query"));
        assertTrue(entries.isEmpty());
    }

    @Test
    public void testExecutingSqlQueryStringOverload() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "2026-08-24 ... DEBUG ... Executing SQL query [SELECT id, name FROM users WHERE status = ?]",
            "2026-08-24 ... TRACE ... Setting SQL statement parameter value: column index 1, parameter value [active], value class [java.lang.String], SQL type unknown");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).rawSql().startsWith("SELECT id, name FROM users"));
        assertEquals(1, entries.get(0).params().size());
        assertEquals(SqlSource.JDBC, entries.get(0).source());
    }

    @Test
    public void testExecutingSqlUpdateStringOverload() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Executing SQL update [UPDATE users SET name = ? WHERE id = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [Alice], value class [java.lang.String], SQL type unknown",
            "Setting SQL statement parameter value: column index 2, parameter value [42], value class [java.lang.Long], SQL type unknown");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).rawSql().startsWith("UPDATE users"));
        assertEquals(2, entries.get(0).params().size());
        assertEquals(SqlSource.JDBC, entries.get(0).source());
    }

    @Test
    public void testExecutingSqlBatchUpdate() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Executing SQL batch update [INSERT INTO logs (msg) VALUES (?)]",
            "Setting SQL statement parameter value: column index 1, parameter value [hello], value class [java.lang.String], SQL type unknown");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).rawSql().contains("INSERT INTO logs"));
        assertEquals(SqlSource.JDBC, entries.get(0).source());
    }

    @Test
    public void testCallingStoredProcedure() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Calling stored procedure [{call sp_get_user(?)}]",
            "Setting SQL statement parameter value: column index 1, parameter value [100], value class [java.lang.Integer], SQL type unknown");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).rawSql().contains("sp_get_user"));
        assertEquals(SqlSource.JDBC, entries.get(0).source());
    }

    // ========== 参数类型映射 ==========

    @Test
    public void testNullParamValue() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM users WHERE deleted = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [null], value class [null], SQL type unknown");
        assertEquals(1, entries.size());
        assertEquals(1, entries.get(0).params().size());
        assertNull(entries.get(0).params().get(0).value());
        assertNull(entries.get(0).params().get(0).type());
        assertTrue(entries.get(0).restoredSql().contains("NULL"));
    }

    @Test
    public void testQualifiedTypeInteger() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t WHERE id = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [42], value class [java.lang.Integer], SQL type unknown");
        assertEquals("Integer", entries.get(0).params().get(0).type());
        assertTrue(entries.get(0).restoredSql().contains("42"));
        assertTrue(!entries.get(0).restoredSql().contains("'42'"));
    }

    @Test
    public void testQualifiedTypeString() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t WHERE name = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [Alice], value class [java.lang.String], SQL type unknown");
        assertEquals("String", entries.get(0).params().get(0).type());
        assertTrue(entries.get(0).restoredSql().contains("'Alice'"));
    }

    @Test
    public void testQualifiedTypeBigDecimal() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t WHERE amount > ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [99.99], value class [java.math.BigDecimal], SQL type unknown");
        assertEquals("BigDecimal", entries.get(0).params().get(0).type());
        assertTrue(entries.get(0).restoredSql().contains("99.99"));
        assertTrue(!entries.get(0).restoredSql().contains("'99.99'"));
    }

    @Test
    public void testQualifiedTypeTimestamp() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t WHERE created > ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [2025-01-01 12:00:00.0], value class [java.sql.Timestamp], SQL type unknown");
        assertEquals("Timestamp", entries.get(0).params().get(0).type());
        // Timestamp 不在 PLAIN_TYPES → 引号包裹
        assertTrue(entries.get(0).restoredSql().contains("'2025-01-01 12:00:00.0'"));
    }

    // ========== 参数排序 ==========

    @Test
    public void testParamColumnIndexOutOfOrder() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t WHERE a = ? AND b = ?]",
            "Setting SQL statement parameter value: column index 2, parameter value [second], value class [java.lang.String], SQL type unknown",
            "Setting SQL statement parameter value: column index 1, parameter value [first], value class [java.lang.String], SQL type unknown");
        assertEquals(1, entries.size());
        assertEquals(2, entries.get(0).params().size());
        // 按 column index 排序：index 1 → first，index 2 → second
        assertEquals("first", entries.get(0).params().get(0).value());
        assertEquals("second", entries.get(0).params().get(1).value());
        assertTrue(entries.get(0).restoredSql().contains("'first'"));
        assertTrue(entries.get(0).restoredSql().contains("'second'"));
    }

    // ========== 多条 SQL ==========

    @Test
    public void testMultipleSqlConsecutive() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t1 WHERE id = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [1], value class [java.lang.Integer], SQL type unknown",
            "Executing prepared SQL statement [SELECT * FROM t2 WHERE name = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [test], value class [java.lang.String], SQL type unknown");
        assertEquals(2, entries.size());
        assertTrue(entries.get(0).rawSql().contains("t1"));
        assertTrue(entries.get(1).rawSql().contains("t2"));
    }

    // ========== 噪声隔离 ==========

    @Test
    public void testMyBatisLineMixedIgnored() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "==>  Preparing: SELECT * FROM users WHERE id = ?",
            "==> Parameters: 1(Integer)");
        assertTrue(entries.isEmpty());
    }

    @Test
    public void testHibernateLineMixedIgnored() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Hibernate: select user0_.id from users user0_ where user0_.id=?",
            "binding parameter [1] as [BIGINT] - [1]");
        assertTrue(entries.isEmpty());
    }

    @Test
    public void testPureNoiseLineIgnored() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "2026-08-24 10:00:00.000 INFO  [main] com.example.Service - Some business log");
        assertTrue(entries.isEmpty());
    }

    // ========== 边界场景 ==========

    @Test
    public void testDisabledNoOutput() {
        JdbcTemplateLogParser p = new JdbcTemplateLogParser();
        p.setEnabled(false);
        feedAll(p, "Executing prepared SQL statement [SELECT * FROM t]");
        assertTrue(p.takeEntries().isEmpty());
    }

    @Test
    public void testParamValueWithQuoteEscaped() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t WHERE name = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [O'Brien], value class [java.lang.String], SQL type unknown");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).restoredSql().contains("O''Brien"));
    }

    @Test
    public void testParamValueWithBracketGreedy() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t WHERE data = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [abc[def]], value class [java.lang.String], SQL type unknown");
        assertEquals(1, entries.size());
        assertEquals("abc[def]", entries.get(0).params().get(0).value());
    }

    @Test
    public void testLongSqlTruncated() {
        String longSql = "x".repeat(25000);
        JdbcTemplateLogParser p = new JdbcTemplateLogParser();
        p.setMaxParseLength(100);
        List<LogSqlEntry> entries = feedAll(
            p,
            "Executing prepared SQL statement [" + longSql + "]");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).rawSql().length() <= 100);
    }

    @Test
    public void testParamLineWithoutSqlContextIgnored() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Setting SQL statement parameter value: column index 1, parameter value [test], value class [java.lang.String], SQL type unknown");
        assertTrue(entries.isEmpty());
    }

    @Test
    public void testExecutingSqlStatementStringOverload() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Executing SQL statement [SELECT id FROM users]");
        assertEquals(1, entries.size());
        assertEquals("SELECT id FROM users", entries.get(0).rawSql());
        assertEquals(SqlSource.JDBC, entries.get(0).source());
    }

    // ========== NamedParameterJdbcTemplate 单行格式 ==========

    @Test
    public void testNamedParameterBasicFormat() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "sql = SELECT \"owner\", \"modifiedTime\" FROM ipm_RunPlanProject WHERE ( (\"deleted\" = :param1) AND (\"runPlanId\" = :param2) )  , parameters = {param1=0, param2=b9bd4c306fee44bebc3dd6fe1b716357}");
        assertEquals(1, entries.size());
        assertEquals(SqlSource.JDBC, entries.get(0).source());
        assertTrue(entries.get(0).rawSql().contains("ipm_RunPlanProject"));
        assertTrue(entries.get(0).rawSql().contains(":param1"));
        // 还原：param1=0（数值裸渲染），param2=UUID（字符串引号）
        assertTrue(entries.get(0).restoredSql().contains("\"deleted\" = 0"));
        assertTrue(entries.get(0).restoredSql().contains("\"runPlanId\" = 'b9bd4c306fee44bebc3dd6fe1b716357'"));
    }

    @Test
    public void testNamedParameterWithLogPrefix() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "2026-08-24 15:46:42.444 Line:243 - sql = SELECT * FROM users WHERE id = :id AND name = :name , parameters = {id=42, name=Alice}");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).restoredSql().contains("id = 42"));
        assertTrue(entries.get(0).restoredSql().contains("name = 'Alice'"));
    }

    @Test
    public void testNamedParameterNullValue() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "sql = SELECT * FROM t WHERE a = :p1 , parameters = {p1=null}");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).restoredSql().contains("a = NULL"));
    }

    @Test
    public void testNamedParameterValueWithEquals() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "sql = SELECT * FROM t WHERE a = :p1 , parameters = {p1=a=b=c}");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).restoredSql().contains("a = 'a=b=c'"));
    }

    @Test
    public void testNamedParameterValueWithComma() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "sql = SELECT * FROM t WHERE a = :p1 AND b = :p2 , parameters = {p1=a,b,c, p2=ok}");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).restoredSql().contains("a = 'a,b,c'"));
        assertTrue(entries.get(0).restoredSql().contains("b = 'ok'"));
    }

    @Test
    public void testNamedParameterEmptyParams() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "sql = SELECT 1 , parameters = {}");
        assertEquals(1, entries.size());
        assertEquals("SELECT 1", entries.get(0).rawSql());
        assertTrue(entries.get(0).params().isEmpty());
    }

    @Test
    public void testNamedParameterMultipleConsecutive() {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "sql = SELECT * FROM t1 WHERE id = :id , parameters = {id=1}",
            "sql = SELECT * FROM t2 WHERE name = :name , parameters = {name=test}");
        assertEquals(2, entries.size());
        assertTrue(entries.get(0).rawSql().contains("t1"));
        assertTrue(entries.get(1).rawSql().contains("t2"));
    }
}
