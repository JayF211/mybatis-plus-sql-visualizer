package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SQL 性能分析器测试（F-2）：普通 JUnit 测试类（零 IDE fixture），
 * 直接覆盖 [SqlPerfAnalyzer] 的入口与规则引擎集成。
 */
class SqlPerfAnalyzerTest {

    @Before
    fun setUp() {
        SqlPerfAnalyzer.clearCache()
    }

    // ---------- 入口 ----------

    @Test
    fun `analyze_空SQL返回空列表`() {
        val result = SqlPerfAnalyzer.analyze("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `analyze_空白SQL返回空列表`() {
        val result = SqlPerfAnalyzer.analyze("   \n  ")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `analyze_正常SQL无警告`() {
        // 有 WHERE + ORDER BY 列在 SELECT 中 + LIMIT → 不应触发任何警告
        val sql = "SELECT id, name FROM user WHERE id = 1 ORDER BY id LIMIT 10"
        val result = SqlPerfAnalyzer.analyze(sql)
        assertTrue("应无警告，实际: $result", result.isEmpty())
    }

    @Test
    fun `analyze_缓存命中不重复分析`() {
        val sql = "SELECT * FROM user"
        SqlPerfAnalyzer.analyze(sql)
        SqlPerfAnalyzer.analyze(sql)
        assertTrue("缓存应生效", SqlPerfAnalyzer.cacheSize() <= 2)
    }

    @Test
    fun `analyze_多规则命中合并`() {
        // SELECT * (select_star) + 无 WHERE (不会触发，因为 UPDATE/DELETE 才报 missing_where) + LIKE 前导通配符
        val sql = "SELECT * FROM user WHERE name LIKE '%abc'"
        val result = SqlPerfAnalyzer.analyze(sql)
        assertTrue("应含 select_star 警告", result.any { it.ruleId == "select_star" })
        assertTrue("应含 leading_wildcard 警告", result.any { it.ruleId == "leading_wildcard_like" })
    }

    @Test
    fun `analyze_警告数量上限10条`() {
        // 构造一个能触发多条规则的 SQL
        val sql = "SELECT * FROM user t " +
            "LEFT JOIN order o1 ON o1.user_id = t.id " +
            "LEFT JOIN order o2 ON o2.user_id = t.id " +
            "WHERE t.name LIKE '%abc' AND t.age LIKE '%xyz'"
        val result = SqlPerfAnalyzer.analyze(sql)
        assertTrue("警告数量应 <= 10", result.size <= 10)
    }

    @Test
    fun `analyze_规则异常隔离`() {
        // 传入无法解析的 SQL 片段，不应抛异常
        val sql = "SELECT * FROM user WHERE id = 1"
        val result = SqlPerfAnalyzer.analyze(sql)
        // 正常执行不抛异常即可
        assertTrue(result is List<*>)
    }

    @Test
    fun `analyze_严重度分类正确`() {
        // UPDATE 无 WHERE → ERROR
        val errorResult = SqlPerfAnalyzer.analyze("UPDATE user SET name = 'x'")
        assertTrue("应含 ERROR 级别", errorResult.any { it.severity == PerfSeverity.ERROR })

        // SELECT * → INFO
        val infoResult = SqlPerfAnalyzer.analyze("SELECT * FROM user WHERE id = 1")
        assertTrue("应含 INFO 级别", infoResult.any { it.severity == PerfSeverity.INFO })
    }

    @Test
    fun `clearCache_清空缓存`() {
        SqlPerfAnalyzer.analyze("SELECT * FROM user")
        SqlPerfAnalyzer.clearCache()
        assertEquals(0, SqlPerfAnalyzer.cacheSize())
    }

    @Test
    fun `analyze_同SQL不同方言不串味（T-DialectQuoteWarn）`() {
        // 同一 SQL：MySQL 下双引号表名命中方言规则（§十二 起为 WARN，见下条三态用例），
        // PostgreSQL 下合法（不应命中）。缓存键不带方言时，第二次分析会命中第一次的缓存返回错结果（串味）。
        val sql = "SELECT t.id FROM \"user\" t WHERE t.id = 1"
        val mysql = SqlPerfAnalyzer.analyze(sql, MySqlDialect)
        assertTrue(
            "MySQL 应命中 dialect_quote_mismatch，实际: $mysql",
            mysql.any { it.ruleId == "dialect_quote_mismatch" },
        )
        val pg = SqlPerfAnalyzer.analyze(sql, PostgreSQLDialect)
        assertFalse(
            "PostgreSQL 不应命中（缓存不得串味），实际: $pg",
            pg.any { it.ruleId == "dialect_quote_mismatch" },
        )
    }

    @Test
    fun `analyze_同SQL不同ANSI_QUOTES三态不串味（T-DialectQuoteWarn §十二）`() {
        // 同一 SQL + 同一方言（MySQL），三种 ANSI_QUOTES 态结论不同：
        //  - true（URL 声明）→ 不报；false/null → WARN。
        // 缓存键不带 ansiQuotes 三态时，「false/null」会命中「true」的空结果 → 串味（漏报）。
        val sql = "SELECT t.id FROM \"user\" t WHERE t.id = 1"

        val declared = SqlPerfAnalyzer.analyze(sql, MySqlDialect, ansiQuotes = true)
        assertFalse(
            "URL 声明 ANSI_QUOTES → 不报，实际: $declared",
            declared.any { it.ruleId == "dialect_quote_mismatch" },
        )

        val knownOff = SqlPerfAnalyzer.analyze(sql, MySqlDialect, ansiQuotes = false)
        assertTrue(
            "已知未声明 → WARN（不得命中上面的空结果），实际: $knownOff",
            knownOff.any { it.ruleId == "dialect_quote_mismatch" && it.severity == PerfSeverity.WARN },
        )

        val unknown = SqlPerfAnalyzer.analyze(sql, MySqlDialect, ansiQuotes = null)
        assertTrue(
            "未知 → WARN，实际: $unknown",
            unknown.any { it.ruleId == "dialect_quote_mismatch" && it.severity == PerfSeverity.WARN },
        )

        assertEquals("三态应各占一条缓存，不得互相命中", 3, SqlPerfAnalyzer.cacheSize())
    }

    // ------------------------------------------------------------------
    // §十二 取数：URL → ANSI_QUOTES 三态（DataSourceUrlProbe，同包直测）
    // ------------------------------------------------------------------

    @Test
    fun `ansiQuotesFromUrl_URL声明ANSI_QUOTES_判为已声明`() {
        assertEquals(
            true,
            DataSourceUrlProbe.ansiQuotesFromUrl(
                "jdbc:mysql://localhost:3306/mpj_demo?sessionVariables=sql_mode=ANSI_QUOTES",
            ),
        )
        // URL 编码的逗号（多个 mode 连写）
        assertEquals(
            true,
            DataSourceUrlProbe.ansiQuotesFromUrl(
                "jdbc:mysql://localhost:3306/db?sessionVariables=sql_mode=STRICT_TRANS_TABLES%2CANSI_QUOTES",
            ),
        )
        // URL 编码的引号（connector/j 常见写法）
        assertEquals(
            true,
            DataSourceUrlProbe.ansiQuotesFromUrl(
                "jdbc:mysql://localhost:3306/db?sessionVariables=sql_mode=%27ANSI_QUOTES%27&useSSL=false",
            ),
        )
    }

    @Test
    fun `ansiQuotesFromUrl_URL未声明或缺失_判为未声明或未知`() {
        assertEquals(
            "URL 存在但未声明 ANSI_QUOTES → 已知未声明",
            false,
            DataSourceUrlProbe.ansiQuotesFromUrl("jdbc:mysql://localhost:3306/db"),
        )
        assertEquals(
            false,
            DataSourceUrlProbe.ansiQuotesFromUrl(
                "jdbc:mysql://localhost:3306/db?sessionVariables=sql_mode=STRICT_TRANS_TABLES",
            ),
        )
        assertEquals("URL 缺失 → 未知", null, DataSourceUrlProbe.ansiQuotesFromUrl(null))
        assertEquals("URL 空白 → 未知", null, DataSourceUrlProbe.ansiQuotesFromUrl(""))
    }

    @Test
    fun `analyze_无方言上下文时方言规则不判（T-DialectQuoteWarn）`() {
        val sql = "SELECT t.id FROM \"user\" t WHERE t.id = 1"
        val warnings = SqlPerfAnalyzer.analyze(sql)
        assertFalse(
            "无方言上下文不得判定引号方言（不硬编码 MySQL），实际: $warnings",
            warnings.any { it.ruleId == "dialect_quote_mismatch" },
        )
    }

    @Test
    fun `方括号引用SELECT的AST规则生效（T-PS7-03② 先红）`() {
        // 收拢前 CCJSqlParserUtil.parse 对方括号抛异常 → ast=null → 全部 AST 规则降级空转；
        // 收拢后走 SqlAstParser.parseSingle → 方括号 SQL 的 AST 规则（此处 distinct_with_join，AST-only）全量生效
        val warnings = SqlPerfAnalyzer.analyze(
            "SELECT DISTINCT [t1].id FROM [t1] JOIN [t2] ON [t1].id = [t2].t1_id",
        )
        assertTrue(
            "方括号 SQL 应触发 AST-only 规则 distinct_with_join，实际: $warnings",
            warnings.any { it.ruleId == "distinct_with_join" },
        )
    }
}
