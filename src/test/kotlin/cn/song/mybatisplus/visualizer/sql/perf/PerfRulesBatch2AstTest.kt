package cn.song.mybatisplus.visualizer.sql.perf

import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch2
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二批性能规则测试（AST 规则，需要 JSqlParser）。
 */
class PerfRulesBatch2AstTest {

    // ---------- join_without_on ----------

    @Test
    fun `join_without_on_JOIN无ON`() {
        val sql = "SELECT u.id FROM user u INNER JOIN order o"
        val ast = CCJSqlParserUtil.parse(sql)
        val warnings = PerfRulesBatch2.JoinWithoutOnRule.check(sql, ast)
        assertEquals(1, warnings.size)
        assertEquals("join_without_on", warnings[0].ruleId)
        assertEquals(PerfSeverity.ERROR, warnings[0].severity)
    }

    @Test
    fun `join_without_on_JOIN有ON不触发`() {
        val sql = "SELECT u.id FROM user u INNER JOIN order o ON o.user_id = u.id"
        val ast = CCJSqlParserUtil.parse(sql)
        val warnings = PerfRulesBatch2.JoinWithoutOnRule.check(sql, ast)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `join_without_on_无JOIN不触发`() {
        val sql = "SELECT id FROM user"
        val ast = CCJSqlParserUtil.parse(sql)
        val warnings = PerfRulesBatch2.JoinWithoutOnRule.check(sql, ast)
        assertTrue(warnings.isEmpty())
    }

    // ---------- subquery_depth ----------

    @Test
    fun `subquery_depth_1层不触发`() {
        val sql = "SELECT id FROM user WHERE id IN (SELECT user_id FROM order)"
        val ast = CCJSqlParserUtil.parse(sql)
        val warnings = PerfRulesBatch2.SubqueryDepthRule.check(sql, ast)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `subquery_depth_3层触发`() {
        val sql = "SELECT id FROM user WHERE id IN " +
            "(SELECT user_id FROM order WHERE product_id IN " +
            "(SELECT id FROM product WHERE category_id IN " +
            "(SELECT id FROM category)))"
        val ast = CCJSqlParserUtil.parse(sql)
        val warnings = PerfRulesBatch2.SubqueryDepthRule.check(sql, ast)
        assertTrue("深度 3 层应触发", warnings.isNotEmpty())
        assertEquals("subquery_depth", warnings[0].ruleId)
    }

    // ---------- in_clause_overflow ----------

    @Test
    fun `in_clause_overflow_50项不触发`() {
        val items = (1..50).joinToString(", ")
        val sql = "SELECT id FROM user WHERE id IN ($items)"
        val ast = CCJSqlParserUtil.parse(sql)
        val warnings = PerfRulesBatch2.InClauseOverflowRule.check(sql, ast)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `in_clause_overflow_150项触发`() {
        val items = (1..150).joinToString(", ")
        val sql = "SELECT id FROM user WHERE id IN ($items)"
        val ast = CCJSqlParserUtil.parse(sql)
        val warnings = PerfRulesBatch2.InClauseOverflowRule.check(sql, ast)
        assertEquals(1, warnings.size)
        assertEquals("in_clause_overflow", warnings[0].ruleId)
    }

    // ---------- function_on_column ----------

    @Test
    fun `function_on_column_UPPER触发`() {
        val sql = "SELECT id FROM user WHERE UPPER(name) = 'ABC'"
        val ast = CCJSqlParserUtil.parse(sql)
        val warnings = PerfRulesBatch2.FunctionOnColumnRule.check(sql, ast)
        assertTrue("应检测到函数包裹列", warnings.isNotEmpty())
        assertEquals("function_on_column", warnings[0].ruleId)
    }

    @Test
    fun `function_on_column_无函数不触发`() {
        val sql = "SELECT id FROM user WHERE name = 'ABC'"
        val ast = CCJSqlParserUtil.parse(sql)
        val warnings = PerfRulesBatch2.FunctionOnColumnRule.check(sql, ast)
        assertTrue(warnings.isEmpty())
    }

    // ---------- or_condition_count ----------

    @Test
    fun `or_condition_count_2个OR不触发`() {
        val sql = "SELECT id FROM user WHERE a = 1 OR b = 2"
        val ast = CCJSqlParserUtil.parse(sql)
        val warnings = PerfRulesBatch2.OrConditionCountRule.check(sql, ast)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `or_condition_count_5个OR触发`() {
        val sql = "SELECT id FROM user WHERE a = 1 OR b = 2 OR c = 3 OR d = 4 OR e = 5"
        val ast = CCJSqlParserUtil.parse(sql)
        val warnings = PerfRulesBatch2.OrConditionCountRule.check(sql, ast)
        assertEquals(1, warnings.size)
        assertEquals("or_condition_count", warnings[0].ruleId)
    }

    // ---------- distinct_with_join ----------

    @Test
    fun `distinct_with_join_触发`() {
        val sql = "SELECT DISTINCT u.id FROM user u INNER JOIN order o ON o.user_id = u.id"
        val ast = CCJSqlParserUtil.parse(sql)
        val warnings = PerfRulesBatch2.DistinctWithJoinRule.check(sql, ast)
        assertEquals(1, warnings.size)
        assertEquals("distinct_with_join", warnings[0].ruleId)
    }

    @Test
    fun `distinct_with_join_无JOIN不触发`() {
        val sql = "SELECT DISTINCT id FROM user"
        val ast = CCJSqlParserUtil.parse(sql)
        val warnings = PerfRulesBatch2.DistinctWithJoinRule.check(sql, ast)
        assertTrue(warnings.isEmpty())
    }

    // ---------- group_by_without_aggregate ----------

    @Test
    fun `group_by_without_aggregate_触发`() {
        val sql = "SELECT id, name FROM user GROUP BY name"
        val ast = CCJSqlParserUtil.parse(sql)
        val warnings = PerfRulesBatch2.GroupByWithoutAggregateRule.check(sql, ast)
        assertEquals(1, warnings.size)
        assertEquals("group_by_without_aggregate", warnings[0].ruleId)
    }

    @Test
    fun `group_by_without_aggregate_有聚合不触发`() {
        val sql = "SELECT name, COUNT(*) FROM user GROUP BY name"
        val ast = CCJSqlParserUtil.parse(sql)
        val warnings = PerfRulesBatch2.GroupByWithoutAggregateRule.check(sql, ast)
        assertTrue(warnings.isEmpty())
    }

    // ---------- no_limit_large_result ----------

    @Test
    fun `no_limit_large_result_触发`() {
        val sql = "SELECT u.id FROM user u " +
            "INNER JOIN order o ON o.user_id = u.id " +
            "INNER JOIN product p ON p.id = o.product_id"
        val ast = CCJSqlParserUtil.parse(sql)
        val warnings = PerfRulesBatch2.NoLimitLargeResultRule.check(sql, ast)
        assertEquals(1, warnings.size)
        assertEquals("no_limit_large_result", warnings[0].ruleId)
    }

    @Test
    fun `no_limit_large_result_有LIMIT不触发`() {
        val sql = "SELECT u.id FROM user u " +
            "INNER JOIN order o ON o.user_id = u.id " +
            "INNER JOIN product p ON p.id = o.product_id LIMIT 10"
        val ast = CCJSqlParserUtil.parse(sql)
        val warnings = PerfRulesBatch2.NoLimitLargeResultRule.check(sql, ast)
        assertTrue(warnings.isEmpty())
    }

    // ---------- AST 为 null 时降级 ----------

    @Test
    fun `batch2_AST为null时安全降级`() {
        val sql = "SELECT id FROM user"
        assertTrue(PerfRulesBatch2.JoinWithoutOnRule.check(sql, null).isEmpty())
        assertTrue(PerfRulesBatch2.SubqueryDepthRule.check(sql, null).isEmpty())
        assertTrue(PerfRulesBatch2.InClauseOverflowRule.check(sql, null).isEmpty())
        assertTrue(PerfRulesBatch2.FunctionOnColumnRule.check(sql, null).isEmpty())
        assertTrue(PerfRulesBatch2.OrConditionCountRule.check(sql, null).isEmpty())
        assertTrue(PerfRulesBatch2.DistinctWithJoinRule.check(sql, null).isEmpty())
        assertTrue(PerfRulesBatch2.GroupByWithoutAggregateRule.check(sql, null).isEmpty())
        assertTrue(PerfRulesBatch2.NoLimitLargeResultRule.check(sql, null).isEmpty())
    }

    // ---------- 综合 ----------

    @Test
    fun `batch2_全部规则注册`() {
        assertEquals(8, PerfRulesBatch2.all.size)
    }
}
