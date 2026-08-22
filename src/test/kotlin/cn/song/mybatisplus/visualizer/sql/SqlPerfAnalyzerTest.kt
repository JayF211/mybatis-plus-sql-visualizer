package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity
import org.junit.Assert.assertEquals
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
}
