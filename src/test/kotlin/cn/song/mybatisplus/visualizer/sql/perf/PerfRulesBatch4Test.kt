package cn.song.mybatisplus.visualizer.sql.perf

import cn.song.mybatisplus.visualizer.execute.SqlAstParser
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.DmDialect
import cn.song.mybatisplus.visualizer.sql.H2Dialect
import cn.song.mybatisplus.visualizer.sql.KingBaseDialect
import cn.song.mybatisplus.visualizer.sql.MariaDbDialect
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.OceanBaseDialect
import cn.song.mybatisplus.visualizer.sql.OpenGaussDialect
import cn.song.mybatisplus.visualizer.sql.OracleDialect
import cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect
import cn.song.mybatisplus.visualizer.sql.SqlServerDialect
import cn.song.mybatisplus.visualizer.sql.SqliteDialect
import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch4
import net.sf.jsqlparser.statement.Statement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第四批性能规则测试（T-DialectQuoteWarn）：`dialect_quote_mismatch` 的方言判定与两种实现路径。
 *
 * 覆盖：
 *  - [Dialect.identifierQuotes] 各方言集合正确、基类空集（不判）；
 *  - AST 路径（[SqlAstParser.parseSingle] 解析成功）与正则降级路径（ast == null）**两条都要有测试**；
 *  - 正例含**真实形态**（A11 现场让 MySQL 报 1064 的原文）；反例覆盖各方言宽容度与字符串字面量误伤。
 */
class PerfRulesBatch4Test {

    // ------------------------------------------------------------------
    // Dialect.identifierQuotes（Step 1）
    // ------------------------------------------------------------------

    @Test
    fun `方言引号集合_反引号族`() {
        assertEquals(setOf('`'), MySqlDialect.identifierQuotes)
        assertEquals(setOf('`'), MariaDbDialect.identifierQuotes)
        assertEquals(setOf('`'), OceanBaseDialect.identifierQuotes)
    }

    @Test
    fun `方言引号集合_双引号族`() {
        assertEquals(setOf('"'), PostgreSQLDialect.identifierQuotes)
        assertEquals(setOf('"'), OracleDialect.identifierQuotes)
        assertEquals(setOf('"'), DmDialect.identifierQuotes)
        assertEquals(setOf('"'), KingBaseDialect.identifierQuotes)
        assertEquals(setOf('"'), OpenGaussDialect.identifierQuotes)
    }

    @Test
    fun `方言引号集合_SQLServer双引号合法`() {
        assertEquals(setOf('[', '"'), SqlServerDialect.identifierQuotes)
    }

    @Test
    fun `方言引号集合_SQLite与H2收多种引号`() {
        assertEquals(setOf('"', '`', '['), SqliteDialect.identifierQuotes)
        assertEquals(setOf('"', '`', '['), H2Dialect.identifierQuotes)
    }

    @Test
    fun `方言引号集合_基类默认空集不判`() {
        val base = object : Dialect {
            override val name = "Base"
            override fun renderLimit(limit: LimitClause): String = ""
            override val reservedWords: Set<String> = emptySet()
        }
        assertTrue("基类兜底应为空集（不判）", base.identifierQuotes.isEmpty())
    }

    // ------------------------------------------------------------------
    // 正例（真实形态）
    // ------------------------------------------------------------------

    /** A11 现场原文：MySQL 下 `FROM "user"` 报 1064。 */
    private val realSample =
        "SELECT t.id, t.station_id, t.share_scheme_id, t.device_name, " +
            "stationInfoEntity.station_name AS deviceName FROM \"user\" t " +
            "LEFT JOIN user_address addressDO ON addressDO.user_id = t.id"

    @Test
    fun `正例_MySQL遇双引号表名报错_真实形态_AST路径`() {
        val sql = realSample
        val warnings = checkWithDialect(sql, MySqlDialect, astOf(sql))
        assertEquals("应命中一条: $warnings", 1, warnings.size)
        assertEquals("dialect_quote_mismatch", warnings[0].ruleId)
        assertEquals(PerfSeverity.ERROR, warnings[0].severity)
        assertEquals("perf.dialect_quote_mismatch", warnings[0].messageKey)
        assertEquals("\"", warnings[0].messageArgs[0])
        assertEquals("MySQL", warnings[0].messageArgs[1])
        assertEquals("`", warnings[0].messageArgs[2])
    }

    @Test
    fun `正例_MySQL遇双引号表名报错_真实形态_正则降级路径`() {
        val warnings = checkWithDialect(realSample, MySqlDialect, ast = null)
        assertEquals("降级路径也应命中: $warnings", 1, warnings.size)
        assertEquals("dialect_quote_mismatch", warnings[0].ruleId)
    }

    @Test
    fun `正例_MySQL遇双引号列名报错`() {
        val sql = "SELECT t.\"station_id\" FROM t_user t WHERE t.id = 1"
        val warnings = checkWithDialect(sql, MySqlDialect, astOf(sql))
        assertEquals("列名双引号也应命中: $warnings", 1, warnings.size)
    }

    @Test
    fun `正例_MySQL遇方括号表名报错`() {
        val sql = "SELECT id FROM [user] WHERE id = 1"
        val warnings = checkWithDialect(sql, MySqlDialect, astOf(sql))
        assertEquals("方括号在 MySQL 不合法: $warnings", 1, warnings.size)
    }

    @Test
    fun `正例_PostgreSQL遇反引号报错`() {
        val sql = "SELECT id FROM `user` WHERE id = 1"
        val warnings = checkWithDialect(sql, PostgreSQLDialect, astOf(sql))
        assertEquals("反引号在 PG 不合法: $warnings", 1, warnings.size)
        assertEquals("PostgreSQL", warnings[0].messageArgs[1])
    }

    // ------------------------------------------------------------------
    // 反例（方言宽容度，宁可不报也不误报）
    // ------------------------------------------------------------------

    @Test
    fun `反例_PostgreSQL遇双引号不报`() {
        assertTrue(checkWithDialect(realSample, PostgreSQLDialect, astOf(realSample)).isEmpty())
    }

    @Test
    fun `反例_MySQL遇反引号不报`() {
        val sql = "SELECT id FROM `user` WHERE id = 1"
        assertTrue(checkWithDialect(sql, MySqlDialect, astOf(sql)).isEmpty())
    }

    @Test
    fun `反例_SQLServer遇双引号不报`() {
        // QUOTED_IDENTIFIER 默认 ON → 双引号合法，判错即误报
        assertTrue(checkWithDialect(realSample, SqlServerDialect, astOf(realSample)).isEmpty())
    }

    @Test
    fun `反例_SQLite遇反引号与双引号不报`() {
        assertTrue(checkWithDialect("SELECT id FROM `user`", SqliteDialect, astOf("SELECT id FROM `user`")).isEmpty())
        assertTrue(checkWithDialect(realSample, SqliteDialect, astOf(realSample)).isEmpty())
    }

    @Test
    fun `反例_字符串字面量里的双引号不报_AST路径`() {
        val sql = "SELECT id FROM t_user WHERE name = 'say \"hi\"'"
        assertTrue(checkWithDialect(sql, MySqlDialect, astOf(sql)).isEmpty())
    }

    @Test
    fun `反例_字符串字面量里的双引号不报_正则降级路径`() {
        val sql = "SELECT id FROM t_user WHERE name = 'say \"hi\"'"
        assertTrue(checkWithDialect(sql, MySqlDialect, ast = null).isEmpty())
    }

    @Test
    fun `反例_无方言上下文不报`() {
        assertTrue(checkWithDialect(realSample, dialect = null, ast = astOf(realSample)).isEmpty())
    }

    @Test
    fun `反例_基类空集不报`() {
        val base = object : Dialect {
            override val name = "Base"
            override fun renderLimit(limit: LimitClause): String = ""
            override val reservedWords: Set<String> = emptySet()
        }
        assertTrue(checkWithDialect(realSample, base, astOf(realSample)).isEmpty())
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun astOf(sql: String): Statement = SqlAstParser.parseSingle(sql)

    private fun checkWithDialect(
        sql: String,
        dialect: Dialect?,
        ast: Statement?,
    ): List<PerfWarning> =
        PerfRulesBatch4.DialectQuoteMismatchRule.checkWithDialect(sql, ast, dialect)
}
