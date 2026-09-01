package cn.song.mybatisplus.visualizer.sql.perf;

import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity;
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning;
import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch1;
import org.junit.Assert.assertEquals;
import org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;

/**
 * 第一批性能规则测试（正则规则，无需 AST）。
 */
public class PerfRulesBatch1Test {

    // ---------- missing_where ----------

    @Test
    public void missing_where_updateNoWhere() {
        List<PerfWarning> warnings = PerfRulesBatch1.MissingWhereRule.INSTANCE.check("UPDATE user SET name = 'x'", null);
        assertEquals(1, warnings.size());
        assertEquals("missing_where", warnings.get(0).getRuleId());
        assertEquals(PerfSeverity.ERROR, warnings.get(0).getSeverity());
    }

    @Test
    public void missing_where_deleteNoWhere() {
        List<PerfWarning> warnings = PerfRulesBatch1.MissingWhereRule.INSTANCE.check("DELETE FROM user", null);
        assertEquals(1, warnings.size());
        assertEquals("missing_where", warnings.get(0).getRuleId());
        assertTrue(warnings.get(0).getMessageArgs().contains("DELETE"));
    }

    @Test
    public void missing_where_updateWithWhereNoTrigger() {
        List<PerfWarning> warnings = PerfRulesBatch1.MissingWhereRule.INSTANCE.check("UPDATE user SET name = 'x' WHERE id = 1", null);
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void missing_where_deleteWithWhereNoTrigger() {
        List<PerfWarning> warnings = PerfRulesBatch1.MissingWhereRule.INSTANCE.check("DELETE FROM user WHERE id = 1", null);
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void missing_where_selectNoTrigger() {
        List<PerfWarning> warnings = PerfRulesBatch1.MissingWhereRule.INSTANCE.check("SELECT * FROM user", null);
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void missing_where_leadingCommentUpdateNoWhere() {
        String sql = "-- 这是一条更新\nUPDATE user SET name = 'x'";
        List<PerfWarning> warnings = PerfRulesBatch1.MissingWhereRule.INSTANCE.check(sql, null);
        assertEquals(1, warnings.size());
    }

    // ---------- leading_wildcard_like ----------

    @Test
    public void leading_wildcard_like_leadingPercent() {
        List<PerfWarning> warnings = PerfRulesBatch1.LeadingWildcardLikeRule.INSTANCE.check(
                "SELECT id FROM user WHERE name LIKE '%abc'", null);
        assertEquals(1, warnings.size());
        assertEquals("leading_wildcard_like", warnings.get(0).getRuleId());
    }

    @Test
    public void leading_wildcard_like_suffixWildcardNoTrigger() {
        List<PerfWarning> warnings = PerfRulesBatch1.LeadingWildcardLikeRule.INSTANCE.check(
                "SELECT id FROM user WHERE name LIKE 'abc%'", null);
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void leading_wildcard_like_notLikeLeading() {
        List<PerfWarning> warnings = PerfRulesBatch1.LeadingWildcardLikeRule.INSTANCE.check(
                "SELECT id FROM user WHERE name NOT LIKE '%abc'", null);
        assertEquals(1, warnings.size());
    }

    @Test
    public void leading_wildcard_like_literalNoFalsePositive() {
        List<PerfWarning> warnings = PerfRulesBatch1.LeadingWildcardLikeRule.INSTANCE.check(
                "SELECT id FROM user WHERE type = 'LIKE %abc'", null);
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void leading_wildcard_like_middleWildcardTrigger() {
        List<PerfWarning> warnings = PerfRulesBatch1.LeadingWildcardLikeRule.INSTANCE.check(
                "SELECT id FROM user WHERE name LIKE '%ab%'", null);
        assertEquals(1, warnings.size());
    }

    // ---------- select_star ----------

    @Test
    public void select_star_plainStarNoJoin() {
        List<PerfWarning> warnings = PerfRulesBatch1.SelectStarRule.INSTANCE.check("SELECT * FROM user", null);
        assertEquals(1, warnings.size());
        assertEquals("select_star", warnings.get(0).getRuleId());
        assertEquals(PerfSeverity.INFO, warnings.get(0).getSeverity());
    }

    @Test
    public void select_star_plainStarWithJoin() {
        String sql = "SELECT * FROM user u INNER JOIN order o ON o.user_id = u.id";
        List<PerfWarning> warnings = PerfRulesBatch1.SelectStarRule.INSTANCE.check(sql, null);
        assertEquals(1, warnings.size());
        assertEquals(PerfSeverity.WARN, warnings.get(0).getSeverity());
    }

    @Test
    public void select_star_tableAliasStar() {
        List<PerfWarning> warnings = PerfRulesBatch1.SelectStarRule.INSTANCE.check("SELECT t.* FROM user t", null);
        assertEquals(1, warnings.size());
    }

    @Test
    public void select_star_distinctStar() {
        List<PerfWarning> warnings = PerfRulesBatch1.SelectStarRule.INSTANCE.check("SELECT DISTINCT * FROM user", null);
        assertEquals(1, warnings.size());
    }

    @Test
    public void select_star_explicitColumnsNoTrigger() {
        List<PerfWarning> warnings = PerfRulesBatch1.SelectStarRule.INSTANCE.check("SELECT id, name FROM user", null);
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void select_star_noFromNoTrigger() {
        List<PerfWarning> warnings = PerfRulesBatch1.SelectStarRule.INSTANCE.check("SELECT 1", null);
        assertTrue(warnings.isEmpty());
    }

    // ---------- order_by_without_limit ----------

    @Test
    public void order_by_without_limit_noLimit() {
        List<PerfWarning> warnings = PerfRulesBatch1.OrderByWithoutLimitRule.INSTANCE.check(
                "SELECT id FROM user ORDER BY id", null);
        assertEquals(1, warnings.size());
        assertEquals("order_by_without_limit", warnings.get(0).getRuleId());
    }

    @Test
    public void order_by_without_limit_withLimitNoTrigger() {
        List<PerfWarning> warnings = PerfRulesBatch1.OrderByWithoutLimitRule.INSTANCE.check(
                "SELECT id FROM user ORDER BY id LIMIT 10", null);
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void order_by_without_limit_noOrderByNoTrigger() {
        List<PerfWarning> warnings = PerfRulesBatch1.OrderByWithoutLimitRule.INSTANCE.check("SELECT id FROM user", null);
        assertTrue(warnings.isEmpty());
    }

    // ---------- order_by_rand ----------

    @Test
    public void order_by_rand_trigger() {
        List<PerfWarning> warnings = PerfRulesBatch1.OrderByRandRule.INSTANCE.check(
                "SELECT id FROM user ORDER BY RAND()", null);
        assertEquals(1, warnings.size());
        assertEquals("order_by_rand", warnings.get(0).getRuleId());
        assertEquals(PerfSeverity.WARN, warnings.get(0).getSeverity());
    }

    @Test
    public void order_by_rand_normalSortNoTrigger() {
        List<PerfWarning> warnings = PerfRulesBatch1.OrderByRandRule.INSTANCE.check(
                "SELECT id FROM user ORDER BY id", null);
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void order_by_rand_lowercaseTrigger() {
        List<PerfWarning> warnings = PerfRulesBatch1.OrderByRandRule.INSTANCE.check(
                "SELECT id FROM user ORDER BY rand()", null);
        assertEquals(1, warnings.size());
    }

    // ---------- union_instead_of_union_all ----------

    @Test
    public void union_instead_of_union_all_unionTrigger() {
        String sql = "SELECT id FROM user UNION SELECT id FROM admin";
        List<PerfWarning> warnings = PerfRulesBatch1.UnionInsteadOfUnionAllRule.INSTANCE.check(sql, null);
        assertEquals(1, warnings.size());
        assertEquals("union_instead_of_union_all", warnings.get(0).getRuleId());
    }

    @Test
    public void union_instead_of_union_all_unionAllNoTrigger() {
        String sql = "SELECT id FROM user UNION ALL SELECT id FROM admin";
        List<PerfWarning> warnings = PerfRulesBatch1.UnionInsteadOfUnionAllRule.INSTANCE.check(sql, null);
        assertTrue(warnings.isEmpty());
    }

    // ---------- 综合 ----------

    @Test
    public void batch1_allRulesRegistered() {
        assertEquals(6, PerfRulesBatch1.all.size());
    }
}
