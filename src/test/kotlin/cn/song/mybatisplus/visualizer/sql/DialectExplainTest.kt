package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方言 EXPLAIN 语句渲染单元测试（M37.2 / P-立项-06 N-1）。
 *
 * 支持矩阵（DBX 真机实证 2026-09-02；T-ExplainFirstNative 对齐 IDEA 原生形态）：
 * MySQL 5.7/8.4、MariaDB（EXPLAIN 表格行——原生 MysqlBaseRawPlanData 同款，FORMAT=JSON
 * 为自创形态无渲染器消费）、PostgreSQL 16（FORMAT JSON, VERBOSE）、KingbaseES V8
 * （FORMAT JSON）、DM8（原生 EXPLAIN）、H2（PLAN FOR）、SQLite（QUERY PLAN）；
 * Oracle（两步链）/ SQL Server（会话 SET）null 降级。
 *
 * T-ExplainChainHardening：① T-ECH-01 被包语句首关键字词法边界（白名单外拒 / 查询形态放行 /
 * 前导注释与左括号 / 多语句任一非查询即拒 / 已包装文本不受门影响）；② T-ECH-02 支持格式表
 * 与逐方言实现的逐字节一致性（模板字面量在测试侧冻结成期望值——表与实现同源比较是同义反复）。
 */
class DialectExplainTest {

    private val sample = "SELECT * FROM users WHERE id > 1"

    @Test
    fun `MySQL 形态`() {
        assertEquals("EXPLAIN $sample", MySqlDialect.renderExplainStatement(sample))
    }

    @Test
    fun `MariaDB 同 MySQL 形态`() {
        assertEquals("EXPLAIN $sample", MariaDbDialect.renderExplainStatement(sample))
    }

    @Test
    fun `PostgreSQL 形态`() {
        assertEquals("EXPLAIN (FORMAT JSON, VERBOSE) $sample", PostgreSQLDialect.renderExplainStatement(sample))
    }

    @Test
    fun `H2 形态`() {
        assertEquals("EXPLAIN PLAN FOR $sample", H2Dialect.renderExplainStatement(sample))
    }

    @Test
    fun `SQLite 形态`() {
        assertEquals("EXPLAIN QUERY PLAN $sample", SqliteDialect.renderExplainStatement(sample))
    }

    @Test
    fun `金仓 PG 兼容形态（V8 真机实证）`() {
        assertEquals("EXPLAIN (FORMAT JSON) $sample", KingBaseDialect.renderExplainStatement(sample))
    }

    @Test
    fun `达梦原生形态（DM8 真机实证）`() {
        assertEquals("EXPLAIN $sample", DmDialect.renderExplainStatement(sample))
    }

    @Test
    fun `Oracle SQLServer 降级 null`() {
        assertNull(OracleDialect.renderExplainStatement(sample))
        assertNull(SqlServerDialect.renderExplainStatement(sample))
    }

    @Test
    fun `全部方言均可调用不抛异常`() {
        DialectRegistry.dialects.forEach { d ->
            @Suppress("UNUSED_EXPRESSION")
            d.renderExplainStatement(sample)
        }
    }

    // ---------- T-ExplainDbl：幂等包装（重复点击执行计划不二次加前缀） ----------

    @Test
    fun `幂等-已包装文本原样返回`() {
        val wrapped = "EXPLAIN FORMAT=JSON $sample"
        assertEquals(wrapped, MySqlDialect.renderExplainStatementIdempotent(wrapped))
        // 首次点击后该文本写回控制台文档，二次点击读取到的即此文本——不再产生双前缀
        assertEquals(wrapped, MySqlDialect.renderExplainStatementIdempotent(wrapped))
    }

    @Test
    fun `幂等-大小写与首尾空白结尾分号`() {
        assertEquals("explain $sample", MySqlDialect.renderExplainStatementIdempotent("  explain $sample;  "))
        assertEquals("EXPLAIN $sample", DmDialect.renderExplainStatementIdempotent("EXPLAIN $sample"))
    }

    @Test
    fun `幂等-词边界不误判标识符（T-ECH-01 起转由首关键字门拒）`() {
        // EXPLAINING_... 是标识符不是 EXPLAIN 关键字（故不被当已包装文本）；其首词 EXPLAINING
        // 也不在白名单 → T-ECH-01 门拒绝（D1=A 收紧：原「加前缀包装」行为不再成立）
        assertNull(MySqlDialect.renderExplainStatementIdempotent("EXPLAINING_TABLE"))
    }

    @Test
    fun `幂等-未包装文本正常加前缀`() {
        assertEquals("EXPLAIN $sample", MySqlDialect.renderExplainStatementIdempotent(sample))
        assertEquals(
            "EXPLAIN (FORMAT JSON, VERBOSE) $sample",
            PostgreSQLDialect.renderExplainStatementIdempotent(sample),
        )
    }

    @Test
    fun `幂等-不支持方言未包装降级null 已包装原样`() {
        assertNull(OracleDialect.renderExplainStatementIdempotent(sample))
        assertEquals("EXPLAIN $sample", OracleDialect.renderExplainStatementIdempotent("EXPLAIN $sample"))
    }

    // ---------- T-ExplainChainHardening T-ECH-01：被包语句首关键字词法边界（D1=A 严格判定） ----------

    @Test
    fun `T-ECH-01 边界-非查询形态拒绝`() {
        // 白名单外：标识符前缀形态（不得被 takeWhile 式取词截成 SELECT 放行）+ DML + DDL
        listOf(
            "SELECTED x FROM t",
            "SELECT_foo FROM t",
            "UPDATE users SET name = 'x' WHERE id = 1",
            "DELETE FROM users",
            "INSERT INTO users(id) VALUES (1)",
            "DROP TABLE users",
            "CREATE TABLE t (id INT)",
        ).forEach { sql ->
            assertNull("非查询形态不得包装：$sql", MySqlDialect.renderExplainStatementIdempotent(sql))
        }
    }

    @Test
    fun `T-ECH-01 边界-查询形态放行`() {
        assertEquals("EXPLAIN SELECT* FROM users", MySqlDialect.renderExplainStatementIdempotent("SELECT* FROM users"))
        assertEquals("EXPLAIN SELECT(1)", MySqlDialect.renderExplainStatementIdempotent("SELECT(1)"))
        assertEquals("EXPLAIN VALUES(1)", MySqlDialect.renderExplainStatementIdempotent("VALUES(1)"))
        assertEquals("EXPLAIN TABLE users", MySqlDialect.renderExplainStatementIdempotent("TABLE users"))
        assertEquals(
            "EXPLAIN WITH x AS (SELECT 1) SELECT * FROM x",
            MySqlDialect.renderExplainStatementIdempotent("WITH x AS (SELECT 1) SELECT * FROM x"),
        )
        // 首词小写亦放行（关键字大小写不敏感）
        assertEquals("EXPLAIN select 1", MySqlDialect.renderExplainStatementIdempotent("select 1"))
    }

    @Test
    fun `T-ECH-01 边界-前导空白注释与左括号`() {
        // 前导空白/注释/左括号先跳过再取词——直接 startsWith("SELECT") 会把以注释开头的合法文本判拒
        assertEquals("EXPLAIN SELECT 1", MySqlDialect.renderExplainStatementIdempotent("  \nSELECT 1"))
        assertEquals("EXPLAIN (SELECT 1)", MySqlDialect.renderExplainStatementIdempotent("(SELECT 1)"))
        // 注释形态放行；注释**原样保留**在包装产物里（`EXPLAIN -- note\nSELECT 1` 是合法 SQL——
        // 取词只跳过注释用于判定，不改写用户文本，与「包装=加前缀」的既有语义一致）
        assertEquals("EXPLAIN -- note\nSELECT 1", MySqlDialect.renderExplainStatementIdempotent("-- note\nSELECT 1"))
        assertEquals("EXPLAIN /* note */ SELECT 1", MySqlDialect.renderExplainStatementIdempotent("/* note */ SELECT 1"))
        // 注释里的 DML 字不参与判定（注释被跳过；判定与产物都不断言注释内容）
        assertEquals(
            "EXPLAIN -- DROP TABLE t\nSELECT 1",
            MySqlDialect.renderExplainStatementIdempotent("-- DROP TABLE t\nSELECT 1"),
        )
    }

    @Test
    fun `T-ECH-01 边界-多语句任一非查询即拒`() {
        // 只判首条会漏掉夹带：包装成 `EXPLAIN SELECT 1; DROP TABLE t` 后第二条 DDL 仍会真实执行
        assertNull(MySqlDialect.renderExplainStatementIdempotent("SELECT 1; DROP TABLE t"))
        assertNull(MySqlDialect.renderExplainStatementIdempotent("SELECT 1; UPDATE t SET a = 1"))
        // 同一条判据的另一面：全为查询形态的多语句维持放行
        assertEquals("EXPLAIN SELECT 1; SELECT 2", MySqlDialect.renderExplainStatementIdempotent("SELECT 1; SELECT 2"))
    }

    @Test
    fun `T-ECH-01 边界-已包装文本不受门影响（门不早于幂等短路）`() {
        // 门若排在幂等短路之前：已包装文本首词是 EXPLAIN（不在白名单）→ 被自己的门拒掉，
        // T-ExplainDbl 的防双前缀价值归零。故此处断言已包装文本（含首尾空白/结尾分号形态）原样返回。
        assertEquals("EXPLAIN SELECT 1", MySqlDialect.renderExplainStatementIdempotent("EXPLAIN SELECT 1"))
        assertEquals("EXPLAIN  SELECT 1", MySqlDialect.renderExplainStatementIdempotent("  EXPLAIN  SELECT 1;  "))
    }

    @Test
    fun `T-ECH-01 门-取词与白名单直测`() {
        assertEquals("SELECT", ExplainGate.firstKeyword("  -- c\nselect * from t"))
        assertEquals("UPDATE", ExplainGate.firstKeyword("UPDATE t SET a = 1"))
        assertEquals("SELECT", ExplainGate.firstKeyword("(SELECT 1)"))
        assertNull("纯注释文本无首关键字", ExplainGate.firstKeyword("-- only comment"))
        assertTrue(ExplainGate.isExplainableSource("VALUES (1)"))
        assertTrue(ExplainGate.isExplainableSource("TABLE users"))
        assertFalse("SELECT_foo 不得被截成 SELECT", ExplainGate.isExplainableSource("SELECT_foo FROM t"))
        assertFalse("空文本 fail closed", ExplainGate.isExplainableSource("   "))
    }

    // ---------- T-ExplainChainHardening T-ECH-02：支持/格式表（单一事实源） ----------

    /**
     * 7 方言模板的**冻结期望值**（测试侧字面量）——表与实现同源比较是同义反复（永远相等、
     * 测不出抄错模板），故模板字面量在此独立冻结，逐字节比对。
     */
    private val frozenTemplates = mapOf(
        "MySQL" to "EXPLAIN ",
        "PostgreSQL" to "EXPLAIN (FORMAT JSON, VERBOSE) ",
        "H2" to "EXPLAIN PLAN FOR ",
        "SQLite" to "EXPLAIN QUERY PLAN ",
        "MariaDB" to "EXPLAIN ",
        "DM（达梦）" to "EXPLAIN ",
        "KingBase（人大金仓）" to "EXPLAIN (FORMAT JSON) ",
    )

    @Test
    fun `T-ECH-02 表模板与冻结期望逐字节一致`() {
        assertEquals("表键集须与冻结清单一致", frozenTemplates.keys, ExplainSupportTable.byDialectName.keys)
        frozenTemplates.forEach { (name, template) ->
            assertEquals("模板漂移：$name", template, ExplainSupportTable.byDialectName[name]?.template)
            assertEquals(
                "渲染产物漂移：$name",
                template + sample,
                DialectRegistry.byName(name).renderExplainStatement(sample),
            )
        }
    }

    @Test
    fun `T-ECH-02 表格式取值与各方言输出形态一致`() {
        val expected = mapOf(
            "MySQL" to ExplainSupportTable.OutputFormat.TABLE,
            "MariaDB" to ExplainSupportTable.OutputFormat.TABLE,
            "PostgreSQL" to ExplainSupportTable.OutputFormat.JSON,
            "KingBase（人大金仓）" to ExplainSupportTable.OutputFormat.JSON,
            "H2" to ExplainSupportTable.OutputFormat.TEXT,
            "SQLite" to ExplainSupportTable.OutputFormat.TEXT,
            "DM（达梦）" to ExplainSupportTable.OutputFormat.TEXT,
        )
        expected.forEach { (name, format) ->
            assertEquals("格式漂移：$name", format, ExplainSupportTable.formatOf(name))
        }
        // 未接入方言（Oracle 两步链 / SQL Server 会话级 SET SHOWPLAN_XML 是非目标）：无表项 + 渲染 null
        listOf("Oracle", "SQL Server").forEach { name ->
            assertNull("未接入方言不得入表：$name", ExplainSupportTable.formatOf(name))
            assertNull("未接入方言渲染须降级：$name", DialectRegistry.byName(name).renderExplainStatement(sample))
        }
    }

    @Test
    fun `T-ECH-02 表覆盖面等于实际可渲染方言集`() {
        // 防漂移：表新增条目却没人读表（或方言覆写没进表）时，两集合立即不等
        val renderable = DialectRegistry.dialects
            .filter { it.renderExplainStatement(sample) != null }
            .map { it.name }
            .toSet()
        assertEquals(ExplainSupportTable.byDialectName.keys, renderable)
    }
}
