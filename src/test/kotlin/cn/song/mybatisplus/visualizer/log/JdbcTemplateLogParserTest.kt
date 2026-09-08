package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spring JdbcTemplate 控制台日志解析器单元测试（M47）。
 *
 * 测试用例贴近真实 JdbcTemplate 日志输出（DEBUG SQL 行 + TRACE 参数行）。
 */
class JdbcTemplateLogParserTest {

    private fun parser() = JdbcTemplateLogParser()

    private fun feedAll(p: JdbcTemplateLogParser, vararg lines: String): List<LogSqlEntry> {
        lines.forEach { p.feed(it) }
        p.closePending()
        return p.takeEntries()
    }

    /** 完整日志框架前缀行（时间戳+线程+类名+行号）。 */
    private fun withPrefix(text: String): String =
        "2026-08-24 15:46:42.444 [TID: null] [cache-preheat-1] DEBUG org.springframework.jdbc.core.JdbcTemplate Line:643 - $text"

    private fun withTracePrefix(text: String): String =
        "2026-08-24 15:46:42.444 [TID: null] [cache-preheat-1] TRACE org.springframework.jdbc.core.StatementCreatorUtils Line:227 - $text"

    // ========== 基础 SQL 行识别 ==========

    @Test
    fun `prepared statement + 2 参数（真实日志片段）`() {
        val entries = feedAll(
            parser(),
            withPrefix("Executing prepared SQL query"),
            withPrefix("""Executing prepared SQL statement [SELECT "owner", "modifiedTime" FROM ipm_RunPlanProject WHERE (("deleted" = ?) AND ("runPlanId" = ?))]"""),
            withTracePrefix("Setting SQL statement parameter value: column index 1, parameter value [0], value class [java.lang.Integer], SQL type unknown"),
            withTracePrefix("Setting SQL statement parameter value: column index 2, parameter value [7d0fb6b845074c4a89f56480077fbf51], value class [java.lang.String], SQL type unknown"),
        )
        assertEquals(1, entries.size)
        assertTrue(entries[0].rawSql.contains("SELECT"))
        assertTrue(entries[0].rawSql.contains("ipm_RunPlanProject"))
        assertEquals(SqlSource.JDBC, entries[0].source)
        assertEquals(2, entries[0].params.size)
        // 参数按 column index 排序：index 1 → Integer 0（裸），index 2 → String（引号）
        assertEquals("0", entries[0].params[0].value)
        assertEquals("Integer", entries[0].params[0].type)
        assertEquals("7d0fb6b845074c4a89f56480077fbf51", entries[0].params[1].value)
        assertEquals("String", entries[0].params[1].type)
        // 还原 SQL 验证
        assertTrue(entries[0].restoredSql.contains("0"))
        assertTrue(entries[0].restoredSql.contains("'7d0fb6b845074c4a89f56480077fbf51'"))
    }

    @Test
    fun `无参 prepared statement`() {
        val entries = feedAll(
            parser(),
            withPrefix("Executing prepared SQL statement [SELECT 1]"),
        )
        assertEquals(1, entries.size)
        assertEquals("SELECT 1", entries[0].rawSql)
        assertTrue(entries[0].params.isEmpty())
        assertEquals(SqlSource.JDBC, entries[0].source)
    }

    @Test
    fun `Executing prepared SQL query 无 SQL 文本 → 忽略`() {
        val entries = feedAll(
            parser(),
            withPrefix("Executing prepared SQL query"),
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `Executing SQL query 字符串重载`() {
        val entries = feedAll(
            parser(),
            "2026-08-24 ... DEBUG ... Executing SQL query [SELECT id, name FROM users WHERE status = ?]",
            "2026-08-24 ... TRACE ... Setting SQL statement parameter value: column index 1, parameter value [active], value class [java.lang.String], SQL type unknown",
        )
        assertEquals(1, entries.size)
        assertTrue(entries[0].rawSql.startsWith("SELECT id, name FROM users"))
        assertEquals(1, entries[0].params.size)
        assertEquals(SqlSource.JDBC, entries[0].source)
    }

    @Test
    fun `Executing SQL update 字符串重载`() {
        val entries = feedAll(
            parser(),
            "Executing SQL update [UPDATE users SET name = ? WHERE id = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [Alice], value class [java.lang.String], SQL type unknown",
            "Setting SQL statement parameter value: column index 2, parameter value [42], value class [java.lang.Long], SQL type unknown",
        )
        assertEquals(1, entries.size)
        assertTrue(entries[0].rawSql.startsWith("UPDATE users"))
        assertEquals(2, entries[0].params.size)
        assertEquals(SqlSource.JDBC, entries[0].source)
    }

    @Test
    fun `Executing SQL batch update`() {
        val entries = feedAll(
            parser(),
            "Executing SQL batch update [INSERT INTO logs (msg) VALUES (?)]",
            "Setting SQL statement parameter value: column index 1, parameter value [hello], value class [java.lang.String], SQL type unknown",
        )
        assertEquals(1, entries.size)
        assertTrue(entries[0].rawSql.contains("INSERT INTO logs"))
        assertEquals(SqlSource.JDBC, entries[0].source)
    }

    @Test
    fun `Calling stored procedure`() {
        val entries = feedAll(
            parser(),
            "Calling stored procedure [{call sp_get_user(?)}]",
            "Setting SQL statement parameter value: column index 1, parameter value [100], value class [java.lang.Integer], SQL type unknown",
        )
        assertEquals(1, entries.size)
        assertTrue(entries[0].rawSql.contains("sp_get_user"))
        assertEquals(SqlSource.JDBC, entries[0].source)
    }

    // ========== 参数类型映射 ==========

    @Test
    fun `null 参数值`() {
        val entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM users WHERE deleted = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [null], value class [null], SQL type unknown",
        )
        assertEquals(1, entries.size)
        assertEquals(1, entries[0].params.size)
        assertNull(entries[0].params[0].value)
        assertNull(entries[0].params[0].type)
        assertTrue(entries[0].restoredSql.contains("NULL"))
    }

    @Test
    fun `全限定类型名映射 java lang Integer`() {
        val entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t WHERE id = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [42], value class [java.lang.Integer], SQL type unknown",
        )
        assertEquals("Integer", entries[0].params[0].type)
        assertTrue(entries[0].restoredSql.contains("42"))
        assertTrue(!entries[0].restoredSql.contains("'42'"))
    }

    @Test
    fun `全限定类型名映射 java lang String`() {
        val entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t WHERE name = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [Alice], value class [java.lang.String], SQL type unknown",
        )
        assertEquals("String", entries[0].params[0].type)
        assertTrue(entries[0].restoredSql.contains("'Alice'"))
    }

    @Test
    fun `全限定类型名映射 java math BigDecimal`() {
        val entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t WHERE amount > ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [99.99], value class [java.math.BigDecimal], SQL type unknown",
        )
        assertEquals("BigDecimal", entries[0].params[0].type)
        assertTrue(entries[0].restoredSql.contains("99.99"))
        assertTrue(!entries[0].restoredSql.contains("'99.99'"))
    }

    @Test
    fun `全限定类型名映射 java sql Timestamp`() {
        val entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t WHERE created > ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [2025-01-01 12:00:00.0], value class [java.sql.Timestamp], SQL type unknown",
        )
        assertEquals("Timestamp", entries[0].params[0].type)
        // Timestamp 不在 PLAIN_TYPES → 引号包裹
        assertTrue(entries[0].restoredSql.contains("'2025-01-01 12:00:00.0'"))
    }

    // ========== 参数排序 ==========

    @Test
    fun `参数 column index 乱序`() {
        val entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t WHERE a = ? AND b = ?]",
            "Setting SQL statement parameter value: column index 2, parameter value [second], value class [java.lang.String], SQL type unknown",
            "Setting SQL statement parameter value: column index 1, parameter value [first], value class [java.lang.String], SQL type unknown",
        )
        assertEquals(1, entries.size)
        assertEquals(2, entries[0].params.size)
        // 按 column index 排序：index 1 → first，index 2 → second
        assertEquals("first", entries[0].params[0].value)
        assertEquals("second", entries[0].params[1].value)
        assertTrue(entries[0].restoredSql.contains("'first'"))
        assertTrue(entries[0].restoredSql.contains("'second'"))
    }

    // ========== 多条 SQL ==========

    @Test
    fun `多条 SQL 连续`() {
        val entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t1 WHERE id = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [1], value class [java.lang.Integer], SQL type unknown",
            "Executing prepared SQL statement [SELECT * FROM t2 WHERE name = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [test], value class [java.lang.String], SQL type unknown",
        )
        assertEquals(2, entries.size)
        assertTrue(entries[0].rawSql.contains("t1"))
        assertTrue(entries[1].rawSql.contains("t2"))
    }

    // ========== 噪声隔离 ==========

    @Test
    fun `MyBatis 行混入 → 忽略`() {
        val entries = feedAll(
            parser(),
            "==>  Preparing: SELECT * FROM users WHERE id = ?",
            "==> Parameters: 1(Integer)",
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `Hibernate 行混入 → 忽略`() {
        val entries = feedAll(
            parser(),
            "Hibernate: select user0_.id from users user0_ where user0_.id=?",
            "binding parameter [1] as [BIGINT] - [1]",
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `纯噪声行 → 忽略`() {
        val entries = feedAll(
            parser(),
            "2026-08-24 10:00:00.000 INFO  [main] com.example.Service - Some business log",
        )
        assertTrue(entries.isEmpty())
    }

    // ========== 边界场景 ==========

    @Test
    fun `disabled 时不产出`() {
        val p = JdbcTemplateLogParser(enabled = false)
        feedAll(
            p,
            "Executing prepared SQL statement [SELECT * FROM t]",
        )
        assertTrue(p.takeEntries().isEmpty())
    }

    @Test
    fun `参数值含单引号 → 转义`() {
        val entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t WHERE name = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [O'Brien], value class [java.lang.String], SQL type unknown",
        )
        assertEquals(1, entries.size)
        assertTrue(entries[0].restoredSql.contains("O''Brien"))
    }

    @Test
    fun `参数值含方括号 → 贪婪匹配`() {
        val entries = feedAll(
            parser(),
            "Executing prepared SQL statement [SELECT * FROM t WHERE data = ?]",
            "Setting SQL statement parameter value: column index 1, parameter value [abc[def]], value class [java.lang.String], SQL type unknown",
        )
        assertEquals(1, entries.size)
        assertEquals("abc[def]", entries[0].params[0].value)
    }

    @Test
    fun `超长 SQL 截断`() {
        val longSql = "SELECT " + "x".repeat(25000) + " FROM t"
        val p = JdbcTemplateLogParser(maxParseLength = 100)
        val entries = feedAll(
            p,
            "Executing prepared SQL statement [$longSql]",
        )
        assertEquals(1, entries.size)
        assertTrue(entries[0].rawSql.length <= 100)
    }

    @Test
    fun `无 SQL 上下文的参数行 → 忽略`() {
        val entries = feedAll(
            parser(),
            "Setting SQL statement parameter value: column index 1, parameter value [test], value class [java.lang.String], SQL type unknown",
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `Executing SQL statement 字符串重载`() {
        val entries = feedAll(
            parser(),
            "Executing SQL statement [SELECT id FROM users]",
        )
        assertEquals(1, entries.size)
        assertEquals("SELECT id FROM users", entries[0].rawSql)
        assertEquals(SqlSource.JDBC, entries[0].source)
    }

    // ========== NamedParameterJdbcTemplate 单行格式 ==========

    @Test
    fun `NamedParameter 基本格式（用户真实日志）`() {
        val entries = feedAll(
            parser(),
            """sql = SELECT "owner", "modifiedTime" FROM ipm_RunPlanProject WHERE ( ("deleted" = :param1) AND ("runPlanId" = :param2) )  , parameters = {param1=0, param2=b9bd4c306fee44bebc3dd6fe1b716357}""",
        )
        assertEquals(1, entries.size)
        assertEquals(SqlSource.JDBC, entries[0].source)
        assertTrue(entries[0].rawSql.contains("ipm_RunPlanProject"))
        assertTrue(entries[0].rawSql.contains(":param1"))
        // 还原：param1=0（数值裸渲染），param2=UUID（字符串引号）
        assertTrue(entries[0].restoredSql.contains("\"deleted\" = 0"))
        assertTrue(entries[0].restoredSql.contains("\"runPlanId\" = 'b9bd4c306fee44bebc3dd6fe1b716357'"))
    }

    @Test
    fun `NamedParameter 带日志前缀`() {
        val entries = feedAll(
            parser(),
            "2026-08-24 15:46:42.444 Line:243 - sql = SELECT * FROM users WHERE id = :id AND name = :name , parameters = {id=42, name=Alice}",
        )
        assertEquals(1, entries.size)
        assertTrue(entries[0].restoredSql.contains("id = 42"))
        assertTrue(entries[0].restoredSql.contains("name = 'Alice'"))
    }

    @Test
    fun `NamedParameter null 值`() {
        val entries = feedAll(
            parser(),
            "sql = SELECT * FROM t WHERE a = :p1 , parameters = {p1=null}",
        )
        assertEquals(1, entries.size)
        assertTrue(entries[0].restoredSql.contains("a = NULL"))
    }

    @Test
    fun `NamedParameter 值含等号`() {
        val entries = feedAll(
            parser(),
            "sql = SELECT * FROM t WHERE a = :p1 , parameters = {p1=a=b=c}",
        )
        assertEquals(1, entries.size)
        assertTrue(entries[0].restoredSql.contains("a = 'a=b=c'"))
    }

    @Test
    fun `NamedParameter 值含逗号`() {
        val entries = feedAll(
            parser(),
            "sql = SELECT * FROM t WHERE a = :p1 AND b = :p2 , parameters = {p1=a,b,c, p2=ok}",
        )
        assertEquals(1, entries.size)
        assertTrue(entries[0].restoredSql.contains("a = 'a,b,c'"))
        assertTrue(entries[0].restoredSql.contains("b = 'ok'"))
    }

    @Test
    fun `NamedParameter 空参数`() {
        val entries = feedAll(
            parser(),
            "sql = SELECT 1 , parameters = {}",
        )
        assertEquals(1, entries.size)
        assertEquals("SELECT 1", entries[0].rawSql)
        assertTrue(entries[0].params.isEmpty())
    }

    @Test
    fun `NamedParameter 多条连续`() {
        val entries = feedAll(
            parser(),
            "sql = SELECT * FROM t1 WHERE id = :id , parameters = {id=1}",
            "sql = SELECT * FROM t2 WHERE name = :name , parameters = {name=test}",
        )
        assertEquals(2, entries.size)
        assertTrue(entries[0].rawSql.contains("t1"))
        assertTrue(entries[1].rawSql.contains("t2"))
    }

    // ========== T-LogDedupeMpMark：双路日志同语句副本（用户控制台序列回放） ==========

    @Test
    fun `named 单行与 prepared 双路同语句_产出仅差首尾空白的副本`() {
        val entries = feedAll(
            parser(),
            "2026-09-08 09:38:03.587 [TID: null] [cache-preheat-1] TRACE com.a.b.impl.dml.CCCRepositoryJdbcImpl Line:243 - sql = SELECT \"name\" FROM ipm_RunPlanProject  WHERE ( (\"deleted\" = :param1) AND (\"runPlanId\" = :param2) )  , parameters = {param1=0, param2=cc82baedas4a41a993388a5b009f81a8}",
            withPrefix("Executing prepared SQL query"),
            withPrefix("""Executing prepared SQL statement [SELECT "name" FROM ipm_RunPlanProject  WHERE ( ("deleted" = ?) AND ("runPlanId" = ?) )  ]"""),
            withTracePrefix("Setting SQL statement parameter value: column index 1, parameter value [0], value class [java.lang.Integer], SQL type unknown"),
            withTracePrefix("Setting SQL statement parameter value: column index 2, parameter value [cc82baedas4a41a993388a5b009f81a8], value class [java.lang.String], SQL type unknown"),
            // 下一条语句的 named 行触发 prepared 副本闭合（真实批序：一行产出两条）
            "2026-09-08 09:38:03.600 [TID: null] [cache-preheat-1] TRACE com.a.b.impl.dml.CCCRepositoryJdbcImpl Line:243 - sql = SELECT COUNT(*) as count FROM ipm_RunPlanProject  WHERE ( (\"deleted\" = :param1) )  , parameters = {param1=0}",
        )
        assertEquals(3, entries.size)
        // entries[0] named 数据副本（trim 过）/ entries[1] prepared 数据副本（尾随空白保留）：
        // 还原值一致、原文仅差首尾空白——正是跨格式去重失效的文本现场
        assertTrue(entries[0].restoredSql.contains("'cc82baedas4a41a993388a5b009f81a8'"))
        assertTrue(entries[1].restoredSql.contains("'cc82baedas4a41a993388a5b009f81a8'"))
        assertEquals(
            normalizeSqlWhitespace(entries[0].restoredSql),
            normalizeSqlWhitespace(entries[1].restoredSql),
        )
        assertFalse("两副本原文不相等（空白差），规范化后才相等", entries[0].restoredSql == entries[1].restoredSql)
        // 第三条为下一语句的 named COUNT
        assertTrue(entries[2].restoredSql.trimStart().startsWith("SELECT COUNT(", ignoreCase = true))
    }

    // ========== T-JdbcMultiline：多行 `[...]` 语句收集 ==========

    /** 用户反馈样本（2026-09-08 原样回放）：TLog TID 前缀 + SQL 跨行 + `]` 独占末行。 */
    private val multilineUserSample = listOf(
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

    @Test
    fun `多行 prepared statement 用户样本回放`() {
        val p = parser()
        multilineUserSample.forEach { p.feed(it) }
        // 闭合后条目仍在 pending（与单行无参语句一致，由下一条语句/刷新冲刷）
        assertTrue("闭合时不立即产出", p.takeEntries().isEmpty())
        p.closePending()
        val entries = p.takeEntries()
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals(SqlSource.JDBC, e.source)
        assertTrue("rawSql 以 SELECT COUNT(1) 开头", e.rawSql.startsWith("SELECT COUNT(1)"))
        assertFalse("终结方括号不残留", e.rawSql.contains('[') || e.rawSql.contains(']'))
        assertTrue("跨行结构保留", e.rawSql.contains("\n        FROM ipm_ImplementTask t"))
        assertTrue("字符串字面量保留", e.rawSql.contains("'80a9971836664x3091xaa53b78ae6837'"))
        assertTrue("以 ) 收尾（削首尾空白）", e.rawSql.endsWith(")"))
        assertEquals("无参数时还原 = 原文", e.rawSql, e.restoredSql)
        assertTrue("无参数", e.params.isEmpty())
    }

    @Test
    fun `多行闭合由下一条语句行冲刷`() {
        val p = parser()
        multilineUserSample.forEach { p.feed(it) }
        // 无 SQL 文本的头行不冲刷（与单行语义一致）
        p.feed(withPrefix("Executing prepared SQL query"))
        assertTrue("头行不产出", p.takeEntries().isEmpty())
        // 下一条 statement 行触发上一条闭合
        p.feed(withPrefix("Executing prepared SQL statement [SELECT 1 FROM dual]"))
        val entries = p.takeEntries()
        assertEquals("statement 行冲刷出多行 COUNT 条目", 1, entries.size)
        assertTrue(entries[0].rawSql.startsWith("SELECT COUNT(1)"))
        p.closePending()
        val all = p.takeEntries()
        assertEquals("第二次取仅剩新语句（COUNT 已随首次 takeEntries 取走）", 1, all.size)
        assertEquals("SELECT 1 FROM dual", all[0].rawSql)
    }

    @Test
    fun `多行收集 字符串内闭括号不构成闭合`() {
        val entries = feedAll(
            parser(),
            withPrefix("Executing prepared SQL statement [SELECT 'a]b' AS v"),
            // 续行为控制台裸文本（同一消息的后续行，无日志前缀）
            "FROM t WHERE x = 1]",
        )
        assertEquals(1, entries.size)
        assertEquals("SELECT 'a]b' AS v\nFROM t WHERE x = 1", entries[0].rawSql)
    }

    @Test
    fun `多行收集 转义引号与字符串态跨行延续`() {
        val entries = feedAll(
            parser(),
            withPrefix("Executing prepared SQL statement [SELECT 'it''s] fine'"),
            "FROM t WHERE y = 'z']",
        )
        assertEquals(1, entries.size)
        assertEquals("SELECT 'it''s] fine'\nFROM t WHERE y = 'z'", entries[0].rawSql)
    }

    @Test
    fun `多行收集 标识符平衡括号不构成闭合`() {
        val entries = feedAll(
            parser(),
            withPrefix("Executing prepared SQL statement [SELECT arr[1] FROM"),
            "t WHERE x = 2]",
        )
        assertEquals(1, entries.size)
        assertEquals("SELECT arr[1] FROM\nt WHERE x = 2", entries[0].rawSql)
    }

    @Test
    fun `多行收集 参数行边界强制闭合残句且不误挂参数`() {
        val p = parser()
        p.feed(withPrefix("Executing prepared SQL statement [SELECT a"))
        p.feed("        FROM t")
        // 参数行到达 → 边界：残句闭合产出，参数行因无 pending 上下文被忽略
        p.feed(
            withTracePrefix(
                "Setting SQL statement parameter value: column index 1, parameter value [5], value class [java.lang.Integer], SQL type unknown",
            ),
        )
        val entries = p.takeEntries()
        assertEquals(1, entries.size)
        assertEquals("SELECT a\n        FROM t", entries[0].rawSql)
        assertTrue("残句不得误挂参数", entries[0].params.isEmpty())
        p.closePending()
        assertTrue(p.takeEntries().isEmpty())
    }

    @Test
    fun `多行收集 NamedParameter 行边界同理`() {
        val p = parser()
        p.feed(withPrefix("Executing prepared SQL statement [SELECT a"))
        p.feed("2026-09-08 09:38:03.600 [TID: null] [x] TRACE c.C Repo Line:1 - sql = SELECT COUNT(*) FROM t , parameters = {p1=0}")
        val entries = p.takeEntries()
        assertEquals("残句 + named 各一条", 2, entries.size)
        assertEquals("SELECT a", entries[0].rawSql)
        assertEquals(SqlSource.JDBC, entries[1].source)
    }

    @Test
    fun `多行收集 超限强制闭合截断`() {
        val p = JdbcTemplateLogParser(maxParseLength = 60)
        p.feed(withPrefix("Executing prepared SQL statement [SELECT aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        p.feed("        FROM tttttttttttttttttttttttttttttttttttttttttt WHERE x = 1]")
        p.closePending()
        val entries = p.takeEntries()
        assertEquals(1, entries.size)
        assertTrue("截断在 maxParseLength 内", entries[0].rawSql.length <= 60)
    }
}
