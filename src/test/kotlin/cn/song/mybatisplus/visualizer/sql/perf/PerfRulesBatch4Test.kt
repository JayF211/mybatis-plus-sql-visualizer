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
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
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
 *  - 正例含**真实形态**（A11 现场让 MySQL 报 1064 的原文）；反例覆盖各方言宽容度与字符串字面量误伤；
 *  - **§十二 三态**：「MySQL 家族 + 双引号」档按 [PerfContext.ansiQuotes] 分流——`true` 不报、
 *    `false`/`null` 降级 WARN（带前提）。
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
    // §十二 三态：「MySQL 家族 + 双引号」
    // ------------------------------------------------------------------

    /** A11 现场原文：MySQL 默认 sql_mode 下 `FROM "user"` 报 1064（URL 未声明 ANSI_QUOTES 时如此）。 */
    private val realSample =
        "SELECT t.id, t.station_id, t.share_scheme_id, t.device_name, " +
            "stationInfoEntity.station_name AS deviceName FROM \"user\" t " +
            "LEFT JOIN user_address addressDO ON addressDO.user_id = t.id"

    @Test
    fun `三态_URL声明ANSI_QUOTES_不报_AST路径`() {
        // 已确知合法（连接 sql_mode 含 ANSI_QUOTES）→ 不报
        assertTrue(
            "URL 声明 ANSI_QUOTES 时应视为合法、不报: ${check(realSample, MySqlDialect, astOf(realSample), ansiQuotes = true)}",
            check(realSample, MySqlDialect, astOf(realSample), ansiQuotes = true).isEmpty(),
        )
    }

    @Test
    fun `三态_URL声明ANSI_QUOTES_不报_正则降级路径`() {
        assertTrue(check(realSample, MySqlDialect, ast = null, ansiQuotes = true).isEmpty())
    }

    @Test
    fun `三态_已知未声明ANSI_QUOTES_降级WARN_真实形态_AST路径`() {
        // T-DialectQuoteWarn §十二 降级原因（勿静默改断言）：原判定为 ERROR，现降为 WARN。
        // 数据源 URL 只能证明「合法」、不能证明「非法」——生效 sql_mode = 服务端全局 + 会话覆盖，
        // URL 里的 sessionVariables 只是会话覆盖；URL 未声明 ANSI_QUOTES 时服务端全局仍可能开着。
        // 故「MySQL 家族 + 双引号」不能用 ERROR，只能用 WARN + 前提说明。
        val warnings = check(realSample, MySqlDialect, astOf(realSample), ansiQuotes = false)
        assertEquals("应命中一条: $warnings", 1, warnings.size)
        assertEquals("dialect_quote_mismatch", warnings[0].ruleId)
        assertEquals(PerfSeverity.WARN, warnings[0].severity)
        assertEquals("perf.dialect_quote_mismatch_ansi_quotes", warnings[0].messageKey)
        assertEquals("\"", warnings[0].messageArgs[0])
        assertEquals("MySQL", warnings[0].messageArgs[1])
        assertEquals("`", warnings[0].messageArgs[2])
    }

    @Test
    fun `三态_未知ANSI_QUOTES_降级WARN_AST路径`() {
        // null = 无绑定数据源 / 探测失败 / 无预览面板 → 未知，同样只能 WARN（不假装知道连接选项）
        val warnings = check(realSample, MySqlDialect, astOf(realSample), ansiQuotes = null)
        assertEquals("应命中一条: $warnings", 1, warnings.size)
        assertEquals(PerfSeverity.WARN, warnings[0].severity)
        assertEquals("perf.dialect_quote_mismatch_ansi_quotes", warnings[0].messageKey)
    }

    @Test
    fun `三态_未知ANSI_QUOTES_降级WARN_正则降级路径`() {
        val warnings = check(realSample, MySqlDialect, ast = null, ansiQuotes = null)
        assertEquals("降级路径也应命中 WARN: $warnings", 1, warnings.size)
        assertEquals(PerfSeverity.WARN, warnings[0].severity)
        assertEquals("perf.dialect_quote_mismatch_ansi_quotes", warnings[0].messageKey)
    }

    @Test
    fun `三态_WARN文案含前提_ANSI_QUOTES与sql_mode措辞在场`() {
        val warnings = check(realSample, MySqlDialect, astOf(realSample), ansiQuotes = null)
        val text = MpSqlVisualizerBundle.messageEn(
            warnings[0].messageKey,
            *warnings[0].messageArgs.toTypedArray(),
        )
        assertTrue("前提措辞须点名 ANSI_QUOTES: $text", text.contains("ANSI_QUOTES"))
        assertTrue("前提措辞须点名 sql_mode: $text", text.contains("sql_mode"))
    }

    @Test
    fun `三态_MariaDB与OceanBase同属MySQL家族_双引号也降级WARN`() {
        for (dialect in listOf(MariaDbDialect, OceanBaseDialect)) {
            val warnings = check(realSample, dialect, astOf(realSample), ansiQuotes = null)
            assertEquals("${dialect.name} 应降级 WARN: $warnings", 1, warnings.size)
            assertEquals(PerfSeverity.WARN, warnings[0].severity)
            assertEquals("perf.dialect_quote_mismatch_ansi_quotes", warnings[0].messageKey)
        }
    }

    @Test
    fun `三态_MySQL遇双引号列名_降级WARN`() {
        val sql = "SELECT t.\"station_id\" FROM t_user t WHERE t.id = 1"
        val warnings = check(sql, MySqlDialect, astOf(sql), ansiQuotes = null)
        assertEquals("列名双引号也应降级 WARN: $warnings", 1, warnings.size)
        assertEquals(PerfSeverity.WARN, warnings[0].severity)
    }

    // ------------------------------------------------------------------
    // 无歧义档：仍 ERROR（§十二「其它档不变」）
    // ------------------------------------------------------------------

    @Test
    fun `无歧义_MySQL遇方括号表名仍报ERROR`() {
        val sql = "SELECT id FROM [user] WHERE id = 1"
        val warnings = check(sql, MySqlDialect, astOf(sql), ansiQuotes = null)
        assertEquals("方括号在 MySQL 一定不合法: $warnings", 1, warnings.size)
        assertEquals(PerfSeverity.ERROR, warnings[0].severity)
        assertEquals("perf.dialect_quote_mismatch", warnings[0].messageKey)
    }

    @Test
    fun `无歧义_PostgreSQL遇反引号仍报ERROR`() {
        val sql = "SELECT id FROM `user` WHERE id = 1"
        val warnings = check(sql, PostgreSQLDialect, astOf(sql), ansiQuotes = null)
        assertEquals("反引号在 PG 一定不合法: $warnings", 1, warnings.size)
        assertEquals(PerfSeverity.ERROR, warnings[0].severity)
        assertEquals("PostgreSQL", warnings[0].messageArgs[1])
    }

    @Test
    fun `无歧义_MySQL同时出现双引号与方括号_优先报方括号ERROR不被WARN掩盖`() {
        // 双引号（有歧义档）不得把方括号（无歧义档）的 ERROR 掩盖掉
        val sql = "SELECT \"a\" FROM [user] WHERE id = 1"
        val warnings = check(sql, MySqlDialect, astOf(sql), ansiQuotes = null)
        assertEquals("应命中无歧义 ERROR: $warnings", 1, warnings.size)
        assertEquals(PerfSeverity.ERROR, warnings[0].severity)
        assertEquals("[", warnings[0].messageArgs[0])
    }

    // ------------------------------------------------------------------
    // 反例（方言宽容度，宁可不报也不误报）
    // ------------------------------------------------------------------

    @Test
    fun `反例_PostgreSQL遇双引号不报`() {
        assertTrue(check(realSample, PostgreSQLDialect, astOf(realSample)).isEmpty())
    }

    @Test
    fun `反例_MySQL遇反引号不报`() {
        val sql = "SELECT id FROM `user` WHERE id = 1"
        assertTrue(check(sql, MySqlDialect, astOf(sql)).isEmpty())
    }

    @Test
    fun `反例_SQLServer遇双引号不报`() {
        // QUOTED_IDENTIFIER 默认 ON → 双引号合法，判错即误报
        assertTrue(check(realSample, SqlServerDialect, astOf(realSample)).isEmpty())
    }

    @Test
    fun `反例_SQLite遇反引号与双引号不报`() {
        assertTrue(check("SELECT id FROM `user`", SqliteDialect, astOf("SELECT id FROM `user`")).isEmpty())
        assertTrue(check(realSample, SqliteDialect, astOf(realSample)).isEmpty())
    }

    @Test
    fun `反例_字符串字面量里的双引号不报_AST路径`() {
        val sql = "SELECT id FROM t_user WHERE name = 'say \"hi\"'"
        assertTrue(check(sql, MySqlDialect, astOf(sql)).isEmpty())
    }

    @Test
    fun `反例_字符串字面量里的双引号不报_正则降级路径`() {
        val sql = "SELECT id FROM t_user WHERE name = 'say \"hi\"'"
        assertTrue(check(sql, MySqlDialect, ast = null).isEmpty())
    }

    @Test
    fun `反例_无方言上下文不报`() {
        assertTrue(check(realSample, dialect = null, ast = astOf(realSample)).isEmpty())
    }

    @Test
    fun `反例_基类空集不报`() {
        val base = object : Dialect {
            override val name = "Base"
            override fun renderLimit(limit: LimitClause): String = ""
            override val reservedWords: Set<String> = emptySet()
        }
        assertTrue(check(realSample, base, astOf(realSample)).isEmpty())
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun astOf(sql: String): Statement = SqlAstParser.parseSingle(sql)

    private fun check(
        sql: String,
        dialect: Dialect?,
        ast: Statement?,
        ansiQuotes: Boolean? = null,
    ): List<PerfWarning> =
        PerfRulesBatch4.DialectQuoteMismatchRule.check(PerfContext(sql, ast, dialect, ansiQuotes))
}
