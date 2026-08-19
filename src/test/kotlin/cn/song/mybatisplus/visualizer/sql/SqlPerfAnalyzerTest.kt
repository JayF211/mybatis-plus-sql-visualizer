package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SQL 性能分析器测试（T-SqlPerf）：普通 JUnit 测试类（零 IDE fixture），
 * 直接覆盖 [SqlPerfAnalyzer] 的 4 类检测函数与 [SqlPerfAnalyzer.analyze] 入口。
 */
class SqlPerfAnalyzerTest {

    // ---------- detectSelectStar ----------

    @Test
    fun `detectSelectStar_纯星号`() {
        val result = SqlPerfAnalyzer.analyze("SELECT * FROM user")
        // SELECT * 触发 select_star；无 WHERE 同时触发 no_where → 共 2 条
        assertTrue(result.suggestions.any { it.category == "select_star" })
        assertEquals("select_star", result.suggestions.first { it.category == "select_star" }.category)
    }

    @Test
    fun `detectSelectStar_表别名星号`() {
        val result = SqlPerfAnalyzer.analyze("SELECT t.* FROM user t")
        assertTrue(result.suggestions.any { it.category == "select_star" })
        assertEquals("select_star", result.suggestions.first { it.category == "select_star" }.category)
    }

    @Test
    fun `detectSelectStar_DISTINCT星号`() {
        val result = SqlPerfAnalyzer.analyze("SELECT DISTINCT * FROM user")
        assertTrue(result.suggestions.any { it.category == "select_star" })
    }

    @Test
    fun `detectSelectStar_指定列不触发`() {
        val result = SqlPerfAnalyzer.analyze("SELECT id, name FROM user")
        assertTrue(result.suggestions.none { it.category == "select_star" })
    }

    // ---------- detectNoWhereSelect ----------

    @Test
    fun `detectNoWhereSelect_无WHERE无LIMIT`() {
        val result = SqlPerfAnalyzer.analyze("SELECT id FROM user")
        val suggestion = result.suggestions.firstOrNull { it.category == "no_where" }
        assertNotNull(suggestion)
        assertEquals(PerfLevel.WARNING, suggestion!!.level)
    }

    @Test
    fun `detectNoWhereSelect_无WHERE有LIMIT`() {
        val result = SqlPerfAnalyzer.analyze("SELECT id FROM user LIMIT 10")
        val suggestion = result.suggestions.firstOrNull { it.category == "no_where" }
        assertNotNull(suggestion)
        assertEquals(PerfLevel.INFO, suggestion!!.level)
    }

    @Test
    fun `detectNoWhereSelect_有WHERE不触发`() {
        val result = SqlPerfAnalyzer.analyze("SELECT id FROM user WHERE id = 1")
        assertTrue(result.suggestions.none { it.category == "no_where" })
    }

    @Test
    fun `detectNoWhereSelect_子查询WHERE不影响外层`() {
        // 外层有 WHERE，不触发
        val sql = "SELECT id FROM user WHERE id IN (SELECT user_id FROM order WHERE status = 1)"
        val result = SqlPerfAnalyzer.analyze(sql)
        assertTrue(result.suggestions.none { it.category == "no_where" })
    }

    // ---------- detectLeadingWildcardLike ----------

    @Test
    fun `detectLeadingWildcardLike_前导百分号`() {
        val result = SqlPerfAnalyzer.analyze("SELECT id FROM user WHERE name LIKE '%abc'")
        val suggestion = result.suggestions.firstOrNull { it.category == "leading_wildcard" }
        assertNotNull(suggestion)
    }

    @Test
    fun `detectLeadingWildcardLike_后缀通配不触发`() {
        val result = SqlPerfAnalyzer.analyze("SELECT id FROM user WHERE name LIKE 'abc%'")
        assertTrue(result.suggestions.none { it.category == "leading_wildcard" })
    }

    @Test
    fun `detectLeadingWildcardLike_NOT_LIKE前导`() {
        val result = SqlPerfAnalyzer.analyze("SELECT id FROM user WHERE name NOT LIKE '%abc'")
        val suggestion = result.suggestions.firstOrNull { it.category == "leading_wildcard" }
        assertNotNull(suggestion)
    }

    @Test
    fun `detectLeadingWildcardLike_字符串字面量中LIKE不误检`() {
        val result = SqlPerfAnalyzer.analyze("SELECT id FROM user WHERE type = 'LIKE %abc'")
        assertTrue(result.suggestions.none { it.category == "leading_wildcard" })
    }

    // ---------- detectOrderByNotInSelect ----------

    @Test
    fun `detectOrderByNotInSelect_列缺失`() {
        val result = SqlPerfAnalyzer.analyze("SELECT id, name FROM user ORDER BY create_time ASC")
        val suggestion = result.suggestions.firstOrNull { it.category == "order_by_missing" }
        assertNotNull(suggestion)
        assertTrue(suggestion!!.messageArgs.contains("create_time"))
    }

    @Test
    fun `detectOrderByNotInSelect_列存在不触发`() {
        val result = SqlPerfAnalyzer.analyze("SELECT id, name FROM user ORDER BY id")
        assertTrue(result.suggestions.none { it.category == "order_by_missing" })
    }

    @Test
    fun `detectOrderByNotInSelect_带表别名`() {
        val result = SqlPerfAnalyzer.analyze("SELECT t.id, t.name FROM user t ORDER BY t.create_time")
        val suggestion = result.suggestions.firstOrNull { it.category == "order_by_missing" }
        assertNotNull(suggestion)
    }

    @Test
    fun `detectOrderByNotInSelect_星号SELECT不触发`() {
        // SELECT * 已单独检测，ORDER BY 列缺失不重复报
        val result = SqlPerfAnalyzer.analyze("SELECT * FROM user ORDER BY id")
        assertTrue(result.suggestions.none { it.category == "order_by_missing" })
    }

    // ---------- 综合 ----------

    @Test
    fun `analyze_多建议合并`() {
        val sql = "SELECT id FROM user WHERE name LIKE '%abc' ORDER BY create_time"
        val result = SqlPerfAnalyzer.analyze(sql)
        // no_where(有WHERE不触发) + leading_wildcard + order_by_missing = 2 条
        // 注：有 WHERE 时不触发 no_where；此处验证 leading_wildcard + order_by_missing 合并
        assertEquals(2, result.suggestions.size)
        assertTrue(result.suggestions.any { it.category == "leading_wildcard" })
        assertTrue(result.suggestions.any { it.category == "order_by_missing" })
    }

    @Test
    fun `analyze_空SQL返回空列表`() {
        val result = SqlPerfAnalyzer.analyze("")
        assertTrue(result.suggestions.isEmpty())
    }

    @Test
    fun `analyze_正常SQL无建议`() {
        val sql = "SELECT id, name FROM user WHERE id = 1 ORDER BY id"
        val result = SqlPerfAnalyzer.analyze(sql)
        assertTrue(result.suggestions.isEmpty())
    }

    @Test
    fun `analyze_hasWarning_hasInfo`() {
        // WARNING 场景：无 WHERE 无 LIMIT
        val warningResult = SqlPerfAnalyzer.analyze("SELECT * FROM user")
        assertTrue("应含 WARNING", warningResult.hasWarning)
        assertTrue("应含 INFO", warningResult.hasInfo)

        // 纯 INFO 场景
        val infoResult = SqlPerfAnalyzer.analyze("SELECT * FROM user WHERE id = 1")
        assertFalse("不应含 WARNING", infoResult.hasWarning)
        assertTrue("应含 INFO", infoResult.hasInfo)

        // 无建议场景
        val cleanResult = SqlPerfAnalyzer.analyze("SELECT id FROM user WHERE id = 1")
        assertFalse(cleanResult.hasWarning)
        assertFalse(cleanResult.hasInfo)
    }

    @Test
    fun `detectSelectStar_无FROM不触发`() {
        val result = SqlPerfAnalyzer.analyze("SELECT 1")
        assertTrue(result.suggestions.none { it.category == "select_star" })
    }

    @Test
    fun `detectOrderByNotInSelect_带LIMIT截断`() {
        val sql = "SELECT id FROM user ORDER BY create_time LIMIT 10"
        val result = SqlPerfAnalyzer.analyze(sql)
        val suggestion = result.suggestions.firstOrNull { it.category == "order_by_missing" }
        assertNotNull(suggestion)
        assertTrue(suggestion!!.messageArgs.contains("create_time"))
    }
}
