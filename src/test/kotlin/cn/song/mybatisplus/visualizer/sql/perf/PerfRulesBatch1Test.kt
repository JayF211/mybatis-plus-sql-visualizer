package cn.song.mybatisplus.visualizer.sql.perf

import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第一批性能规则测试（正则规则，无需 AST）。
 */
class PerfRulesBatch1Test {

    // ---------- missing_where ----------

    @Test
    fun `missing_where_UPDATE无WHERE`() {
        val warnings = PerfRulesBatch1.MissingWhereRule.check("UPDATE user SET name = 'x'", null)
        assertEquals(1, warnings.size)
        assertEquals("missing_where", warnings[0].ruleId)
        assertEquals(PerfSeverity.ERROR, warnings[0].severity)
    }

    @Test
    fun `missing_where_DELETE无WHERE`() {
        val warnings = PerfRulesBatch1.MissingWhereRule.check("DELETE FROM user", null)
        assertEquals(1, warnings.size)
        assertEquals("missing_where", warnings[0].ruleId)
        assertTrue(warnings[0].messageArgs.contains("DELETE"))
    }

    @Test
    fun `missing_where_UPDATE有WHERE不触发`() {
        val warnings = PerfRulesBatch1.MissingWhereRule.check("UPDATE user SET name = 'x' WHERE id = 1", null)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `missing_where_DELETE有WHERE不触发`() {
        val warnings = PerfRulesBatch1.MissingWhereRule.check("DELETE FROM user WHERE id = 1", null)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `missing_where_SELECT不触发`() {
        val warnings = PerfRulesBatch1.MissingWhereRule.check("SELECT * FROM user", null)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `missing_where_前导注释UPDATE无WHERE`() {
        val sql = "-- 这是一条更新\nUPDATE user SET name = 'x'"
        val warnings = PerfRulesBatch1.MissingWhereRule.check(sql, null)
        assertEquals(1, warnings.size)
    }

    // ---------- leading_wildcard_like ----------

    @Test
    fun `leading_wildcard_like_前导百分号`() {
        val warnings = PerfRulesBatch1.LeadingWildcardLikeRule.check(
            "SELECT id FROM user WHERE name LIKE '%abc'", null,
        )
        assertEquals(1, warnings.size)
        assertEquals("leading_wildcard_like", warnings[0].ruleId)
    }

    @Test
    fun `leading_wildcard_like_后缀通配不触发`() {
        val warnings = PerfRulesBatch1.LeadingWildcardLikeRule.check(
            "SELECT id FROM user WHERE name LIKE 'abc%'", null,
        )
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `leading_wildcard_like_NOT_LIKE前导`() {
        val warnings = PerfRulesBatch1.LeadingWildcardLikeRule.check(
            "SELECT id FROM user WHERE name NOT LIKE '%abc'", null,
        )
        assertEquals(1, warnings.size)
    }

    @Test
    fun `leading_wildcard_like_字符串字面量中LIKE不误检`() {
        val warnings = PerfRulesBatch1.LeadingWildcardLikeRule.check(
            "SELECT id FROM user WHERE type = 'LIKE %abc'", null,
        )
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `leading_wildcard_like_中间通配触发`() {
        val warnings = PerfRulesBatch1.LeadingWildcardLikeRule.check(
            "SELECT id FROM user WHERE name LIKE '%ab%'", null,
        )
        assertEquals(1, warnings.size)
    }

    // ---------- select_star ----------

    @Test
    fun `select_star_纯星号无JOIN`() {
        val warnings = PerfRulesBatch1.SelectStarRule.check("SELECT * FROM user", null)
        assertEquals(1, warnings.size)
        assertEquals("select_star", warnings[0].ruleId)
        assertEquals(PerfSeverity.INFO, warnings[0].severity)
    }

    @Test
    fun `select_star_纯星号有JOIN`() {
        val sql = "SELECT * FROM user u INNER JOIN order o ON o.user_id = u.id"
        val warnings = PerfRulesBatch1.SelectStarRule.check(sql, null)
        assertEquals(1, warnings.size)
        assertEquals(PerfSeverity.WARN, warnings[0].severity)
    }

    @Test
    fun `select_star_表别名星号`() {
        val warnings = PerfRulesBatch1.SelectStarRule.check("SELECT t.* FROM user t", null)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `select_star_DISTINCT星号`() {
        val warnings = PerfRulesBatch1.SelectStarRule.check("SELECT DISTINCT * FROM user", null)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `select_star_指定列不触发`() {
        val warnings = PerfRulesBatch1.SelectStarRule.check("SELECT id, name FROM user", null)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `select_star_无FROM不触发`() {
        val warnings = PerfRulesBatch1.SelectStarRule.check("SELECT 1", null)
        assertTrue(warnings.isEmpty())
    }

    // ---------- order_by_without_limit ----------

    @Test
    fun `order_by_without_limit_无LIMIT`() {
        val warnings = PerfRulesBatch1.OrderByWithoutLimitRule.check(
            "SELECT id FROM user ORDER BY id", null,
        )
        assertEquals(1, warnings.size)
        assertEquals("order_by_without_limit", warnings[0].ruleId)
    }

    @Test
    fun `order_by_without_limit_有LIMIT不触发`() {
        val warnings = PerfRulesBatch1.OrderByWithoutLimitRule.check(
            "SELECT id FROM user ORDER BY id LIMIT 10", null,
        )
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `order_by_without_limit_无ORDER_BY不触发`() {
        val warnings = PerfRulesBatch1.OrderByWithoutLimitRule.check("SELECT id FROM user", null)
        assertTrue(warnings.isEmpty())
    }

    // ---------- order_by_rand ----------

    @Test
    fun `order_by_rand_触发`() {
        val warnings = PerfRulesBatch1.OrderByRandRule.check(
            "SELECT id FROM user ORDER BY RAND()", null,
        )
        assertEquals(1, warnings.size)
        assertEquals("order_by_rand", warnings[0].ruleId)
        assertEquals(PerfSeverity.WARN, warnings[0].severity)
    }

    @Test
    fun `order_by_rand_正常排序不触发`() {
        val warnings = PerfRulesBatch1.OrderByRandRule.check(
            "SELECT id FROM user ORDER BY id", null,
        )
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `order_by_rand_小写触发`() {
        val warnings = PerfRulesBatch1.OrderByRandRule.check(
            "SELECT id FROM user ORDER BY rand()", null,
        )
        assertEquals(1, warnings.size)
    }

    // ---------- union_instead_of_union_all ----------

    @Test
    fun `union_instead_of_union_all_UNION触发`() {
        val sql = "SELECT id FROM user UNION SELECT id FROM admin"
        val warnings = PerfRulesBatch1.UnionInsteadOfUnionAllRule.check(sql, null)
        assertEquals(1, warnings.size)
        assertEquals("union_instead_of_union_all", warnings[0].ruleId)
    }

    @Test
    fun `union_instead_of_union_all_UNION_ALL不触发`() {
        val sql = "SELECT id FROM user UNION ALL SELECT id FROM admin"
        val warnings = PerfRulesBatch1.UnionInsteadOfUnionAllRule.check(sql, null)
        assertTrue(warnings.isEmpty())
    }

    // ---------- 综合 ----------

    @Test
    fun `batch1_全部规则注册到引擎`() {
        assertEquals(6, PerfRulesBatch1.all.size)
    }
}
