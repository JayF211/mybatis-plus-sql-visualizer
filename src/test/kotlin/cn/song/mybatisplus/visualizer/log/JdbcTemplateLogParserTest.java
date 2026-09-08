package cn.song.mybatisplus.visualizer.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
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
    public void testPreparedStatementWith2Params() throws Exception {
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
    public void testNoParamPreparedStatement() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            withPrefix("Executing prepared SQL statement [SELECT 1]"));
        assertEquals(1, entries.size());
        assertEquals("SELECT 1", entries.get(0).rawSql());
        assertTrue(entries.get(0).params().isEmpty());
        assertEquals(SqlSource.JDBC, entries.get(0).source());
    }

    @Test
    public void testExecutingPreparedSqlQueryNoSqlIgnored() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            withPrefix("Executing prepared SQL query"));
        assertTrue(entries.isEmpty());
    }

    @Test
    public void testExecutingSqlQueryStringOverload() throws Exception {
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
    public void testExecutingSqlUpdateStringOverload() throws Exception {
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
    public void testExecutingSqlBatchUpdate() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Executing SQL batch update [INSERT INTO logs (msg) VALUES (?)]",
            "Setting SQL statement parameter value: column index 1, parameter value [hello], value class [java.lang.String], SQL type unknown");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).rawSql().contains("INSERT INTO logs"));
        assertEquals(SqlSource.JDBC, entries.get(0).source());
    }

    @Test
    public void testCallingStoredProcedure() throws Exception {
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
    public void testNullParamValue() throws Exception {
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
    public void testQualifiedTypeInteger() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t WHERE id = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [42], value class [java.lang.Integer], SQL type unknown");
        assertEquals("Integer", entries.get(0).params().get(0).type());
        assertTrue(entries.get(0).restoredSql().contains("42"));
        assertTrue(!entries.get(0).restoredSql().contains("'42'"));
    }

    @Test
    public void testQualifiedTypeString() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t WHERE name = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [Alice], value class [java.lang.String], SQL type unknown");
        assertEquals("String", entries.get(0).params().get(0).type());
        assertTrue(entries.get(0).restoredSql().contains("'Alice'"));
    }

    @Test
    public void testQualifiedTypeBigDecimal() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t WHERE amount > ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [99.99], value class [java.math.BigDecimal], SQL type unknown");
        assertEquals("BigDecimal", entries.get(0).params().get(0).type());
        assertTrue(entries.get(0).restoredSql().contains("99.99"));
        assertTrue(!entries.get(0).restoredSql().contains("'99.99'"));
    }

    @Test
    public void testQualifiedTypeTimestamp() throws Exception {
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
    public void testParamColumnIndexOutOfOrder() throws Exception {
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
    public void testMultipleSqlConsecutive() throws Exception {
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
    public void testMyBatisLineMixedIgnored() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "==>  Preparing: SELECT * FROM users WHERE id = ?",
            "==> Parameters: 1(Integer)");
        assertTrue(entries.isEmpty());
    }

    @Test
    public void testHibernateLineMixedIgnored() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Hibernate: select user0_.id from users user0_ where user0_.id=?",
            "binding parameter [1] as [BIGINT] - [1]");
        assertTrue(entries.isEmpty());
    }

    @Test
    public void testPureNoiseLineIgnored() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "2026-08-24 10:00:00.000 INFO  [main] com.example.Service - Some business log");
        assertTrue(entries.isEmpty());
    }

    // ========== 边界场景 ==========

    @Test
    public void testDisabledNoOutput() throws Exception {
        JdbcTemplateLogParser p = new JdbcTemplateLogParser();
        p.setEnabled(false);
        feedAll(p, "Executing prepared SQL statement [SELECT * FROM t]");
        assertTrue(p.takeEntries().isEmpty());
    }

    @Test
    public void testParamValueWithQuoteEscaped() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t WHERE name = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [O'Brien], value class [java.lang.String], SQL type unknown");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).restoredSql().contains("O''Brien"));
    }

    @Test
    public void testParamValueWithBracketGreedy() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t WHERE data = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [abc[def]], value class [java.lang.String], SQL type unknown");
        assertEquals(1, entries.size());
        assertEquals("abc[def]", entries.get(0).params().get(0).value());
    }

    @Test
    public void testLongSqlTruncated() throws Exception {
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
    public void testParamLineWithoutSqlContextIgnored() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Setting SQL statement parameter value: column index 1, parameter value [test], value class [java.lang.String], SQL type unknown");
        assertTrue(entries.isEmpty());
    }

    @Test
    public void testExecutingSqlStatementStringOverload() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "Executing SQL statement [SELECT id FROM users]");
        assertEquals(1, entries.size());
        assertEquals("SELECT id FROM users", entries.get(0).rawSql());
        assertEquals(SqlSource.JDBC, entries.get(0).source());
    }

    // ========== NamedParameterJdbcTemplate 单行格式 ==========

    @Test
    public void testNamedParameterBasicFormat() throws Exception {
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
    public void testNamedParameterWithLogPrefix() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "2026-08-24 15:46:42.444 Line:243 - sql = SELECT * FROM users WHERE id = :id AND name = :name , parameters = {id=42, name=Alice}");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).restoredSql().contains("id = 42"));
        assertTrue(entries.get(0).restoredSql().contains("name = 'Alice'"));
    }

    @Test
    public void testNamedParameterNullValue() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "sql = SELECT * FROM t WHERE a = :p1 , parameters = {p1=null}");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).restoredSql().contains("a = NULL"));
    }

    @Test
    public void testNamedParameterValueWithEquals() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "sql = SELECT * FROM t WHERE a = :p1 , parameters = {p1=a=b=c}");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).restoredSql().contains("a = 'a=b=c'"));
    }

    @Test
    public void testNamedParameterValueWithComma() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "sql = SELECT * FROM t WHERE a = :p1 AND b = :p2 , parameters = {p1=a,b,c, p2=ok}");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).restoredSql().contains("a = 'a,b,c'"));
        assertTrue(entries.get(0).restoredSql().contains("b = 'ok'"));
    }

    @Test
    public void testNamedParameterEmptyParams() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "sql = SELECT 1 , parameters = {}");
        assertEquals(1, entries.size());
        assertEquals("SELECT 1", entries.get(0).rawSql());
        assertTrue(entries.get(0).params().isEmpty());
    }

    @Test
    public void testNamedParameterMultipleConsecutive() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "sql = SELECT * FROM t1 WHERE id = :id , parameters = {id=1}",
            "sql = SELECT * FROM t2 WHERE name = :name , parameters = {name=test}");
        assertEquals(2, entries.size());
        assertTrue(entries.get(0).rawSql().contains("t1"));
        assertTrue(entries.get(1).rawSql().contains("t2"));
    }

    // ========== T-LogDedupeMpMark：双路日志同语句副本（用户控制台序列回放） ==========

    @Test
    public void named单行与prepared双路同语句_产出仅差首尾空白的副本() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            "2026-09-08 09:38:03.587 [TID: null] [cache-preheat-1] TRACE com.a.b.impl.dml.CCCRepositoryJdbcImpl Line:243 - sql = SELECT \"name\" FROM ipm_RunPlanProject  WHERE ( (\"deleted\" = :param1) AND (\"runPlanId\" = :param2) )  , parameters = {param1=0, param2=cc82baedas4a41a993388a5b009f81a8}",
            withPrefix("Executing prepared SQL query"),
            withPrefix("Executing prepared SQL statement [SELECT \"name\" FROM ipm_RunPlanProject  WHERE ( (\"deleted\" = ?) AND (\"runPlanId\" = ?) )  ]"),
            withTracePrefix("Setting SQL statement parameter value: column index 1, parameter value [0], value class [java.lang.Integer], SQL type unknown"),
            withTracePrefix("Setting SQL statement parameter value: column index 2, parameter value [cc82baedas4a41a993388a5b009f81a8], value class [java.lang.String], SQL type unknown"),
            // 下一条语句的 named 行触发 prepared 副本闭合（真实批序：一行产出两条）
            "2026-09-08 09:38:03.600 [TID: null] [cache-preheat-1] TRACE com.a.b.impl.dml.CCCRepositoryJdbcImpl Line:243 - sql = SELECT COUNT(*) as count FROM ipm_RunPlanProject  WHERE ( (\"deleted\" = :param1) )  , parameters = {param1=0}");
        assertEquals(3, entries.size());
        // entries[0] named 数据副本（trim 过）/ entries[1] prepared 数据副本（尾随空白保留）：
        // 还原值一致、原文仅差首尾空白——正是跨格式去重失效的文本现场
        assertTrue(entries.get(0).restoredSql().contains("'cc82baedas4a41a993388a5b009f81a8'"));
        assertTrue(entries.get(1).restoredSql().contains("'cc82baedas4a41a993388a5b009f81a8'"));
        assertEquals(
            LogSqlModelsKt.normalizeSqlWhitespace(entries.get(0).restoredSql()),
            LogSqlModelsKt.normalizeSqlWhitespace(entries.get(1).restoredSql()));
        assertFalse("两副本原文不相等（空白差），规范化后才相等",
            entries.get(0).restoredSql().equals(entries.get(1).restoredSql()));
        // 第三条为下一语句的 named COUNT
        assertTrue(entries.get(2).restoredSql().stripLeading().toUpperCase(java.util.Locale.ROOT).startsWith("SELECT COUNT("));
    }

    // ========== T-JdbcMultiline：多行 `[...]` 语句收集 ==========

    /** 用户反馈样本（2026-09-08 原样回放）：TLog TID 前缀 + SQL 跨行 + `]` 独占末行。 */
    private static final List<String> MULTILINE_USER_SAMPLE = List.of(
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
        "]"
    );

    @Test
    public void 多行preparedStatement用户样本回放() throws Exception {
        JdbcTemplateLogParser p = parser();
        for (String line : MULTILINE_USER_SAMPLE) p.feed(line);
        // 闭合后条目仍在 pending（与单行无参语句一致，由下一条语句/刷新冲刷）
        assertTrue("闭合时不立即产出", p.takeEntries().isEmpty());
        p.closePending();
        List<LogSqlEntry> entries = p.takeEntries();
        assertEquals(1, entries.size());
        LogSqlEntry e = entries.get(0);
        assertEquals(SqlSource.JDBC, e.source());
        assertTrue("rawSql 以 SELECT COUNT(1) 开头", e.rawSql().startsWith("SELECT COUNT(1)"));
        assertFalse("终结方括号不残留", e.rawSql().contains("[") || e.rawSql().contains("]"));
        assertTrue("跨行结构保留", e.rawSql().contains("\n        FROM ipm_ImplementTask t"));
        assertTrue("字符串字面量保留", e.rawSql().contains("'80a9971836664x3091xaa53b78ae6837'"));
        assertTrue("以 ) 收尾（削首尾空白）", e.rawSql().endsWith(")"));
        assertEquals("无参数时还原 = 原文", e.rawSql(), e.restoredSql());
        assertTrue("无参数", e.params().isEmpty());
    }

    @Test
    public void 多行闭合由下一条语句行冲刷() throws Exception {
        JdbcTemplateLogParser p = parser();
        for (String line : MULTILINE_USER_SAMPLE) p.feed(line);
        // 无 SQL 文本的头行不冲刷（与单行语义一致）
        p.feed(withPrefix("Executing prepared SQL query"));
        assertTrue("头行不产出", p.takeEntries().isEmpty());
        // 下一条 statement 行触发上一条闭合
        p.feed(withPrefix("Executing prepared SQL statement [SELECT 1 FROM dual]"));
        List<LogSqlEntry> entries = p.takeEntries();
        assertEquals("statement 行冲刷出多行 COUNT 条目", 1, entries.size());
        assertTrue(entries.get(0).rawSql().startsWith("SELECT COUNT(1)"));
        p.closePending();
        List<LogSqlEntry> all = p.takeEntries();
        assertEquals("第二次取仅剩新语句（COUNT 已随首次 takeEntries 取走）", 1, all.size());
        assertEquals("SELECT 1 FROM dual", all.get(0).rawSql());
    }

    @Test
    public void 多行收集_字符串内闭括号不构成闭合() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            withPrefix("Executing prepared SQL statement [SELECT 'a]b' AS v"),
            "FROM t WHERE x = 1]");
        assertEquals(1, entries.size());
        assertEquals("SELECT 'a]b' AS v\nFROM t WHERE x = 1", entries.get(0).rawSql());
    }

    @Test
    public void 多行收集_转义引号与字符串态跨行延续() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            withPrefix("Executing prepared SQL statement [SELECT 'it''s] fine'"),
            "FROM t WHERE y = 'z']");
        assertEquals(1, entries.size());
        assertEquals("SELECT 'it''s] fine'\nFROM t WHERE y = 'z'", entries.get(0).rawSql());
    }

    @Test
    public void 多行收集_标识符平衡括号不构成闭合() throws Exception {
        List<LogSqlEntry> entries = feedAll(
            parser(),
            withPrefix("Executing prepared SQL statement [SELECT arr[1] FROM"),
            "t WHERE x = 2]");
        assertEquals(1, entries.size());
        assertEquals("SELECT arr[1] FROM\nt WHERE x = 2", entries.get(0).rawSql());
    }

    @Test
    public void 多行收集_参数行边界强制闭合残句且不误挂参数() throws Exception {
        JdbcTemplateLogParser p = parser();
        p.feed(withPrefix("Executing prepared SQL statement [SELECT a"));
        p.feed("        FROM t");
        // 参数行到达 → 边界：残句闭合产出，参数行因无 pending 上下文被忽略
        p.feed(withTracePrefix(
            "Setting SQL statement parameter value: column index 1, parameter value [5], value class [java.lang.Integer], SQL type unknown"));
        List<LogSqlEntry> entries = p.takeEntries();
        assertEquals(1, entries.size());
        assertEquals("SELECT a\n        FROM t", entries.get(0).rawSql());
        assertTrue("残句不得误挂参数", entries.get(0).params().isEmpty());
        p.closePending();
        assertTrue(p.takeEntries().isEmpty());
    }

    @Test
    public void 多行收集_NamedParameter行边界同理() throws Exception {
        JdbcTemplateLogParser p = parser();
        p.feed(withPrefix("Executing prepared SQL statement [SELECT a"));
        p.feed("2026-09-08 09:38:03.600 [TID: null] [x] TRACE c.C Repo Line:1 - sql = SELECT COUNT(*) FROM t , parameters = {p1=0}");
        List<LogSqlEntry> entries = p.takeEntries();
        assertEquals("残句 + named 各一条", 2, entries.size());
        assertEquals("SELECT a", entries.get(0).rawSql());
        assertEquals(SqlSource.JDBC, entries.get(1).source());
    }

    @Test
    public void 多行收集_超限强制闭合截断() throws Exception {
        JdbcTemplateLogParser p = new JdbcTemplateLogParser(new LogSqlRestorer(), true, 60);
        p.feed(withPrefix("Executing prepared SQL statement [SELECT aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        p.feed("        FROM tttttttttttttttttttttttttttttttttttttttttt WHERE x = 1]");
        p.closePending();
        List<LogSqlEntry> entries = p.takeEntries();
        assertEquals(1, entries.size());
        assertTrue("截断在 maxParseLength 内", entries.get(0).rawSql().length() <= 60);
    }
}
