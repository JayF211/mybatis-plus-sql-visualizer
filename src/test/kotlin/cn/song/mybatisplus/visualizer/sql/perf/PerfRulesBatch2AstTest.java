package cn.song.mybatisplus.visualizer.sql.perf;

import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity;
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning;
import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch2;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;

/**
 * 第二批性能规则测试（AST 规则，需要 JSqlParser）。
 */
public class PerfRulesBatch2AstTest {

    private static String rangeItems(int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            if (i > from) sb.append(", ");
            sb.append(i);
        }
        return sb.toString();
    }

    // ---------- join_without_on ----------

    @Test
    public void join_without_on_joinNoOn() throws Exception {
        String sql = "SELECT u.id FROM user u INNER JOIN order o";
        Statement ast = CCJSqlParserUtil.parse(sql);
        List<PerfWarning> warnings = PerfRulesBatch2.JoinWithoutOnRule.INSTANCE.check(sql, ast);
        assertEquals(1, warnings.size());
        assertEquals("join_without_on", warnings.get(0).getRuleId());
        assertEquals(PerfSeverity.ERROR, warnings.get(0).getSeverity());
    }

    @Test
    public void join_without_on_joinWithOnNoTrigger() throws Exception {
        String sql = "SELECT u.id FROM user u INNER JOIN order o ON o.user_id = u.id";
        Statement ast = CCJSqlParserUtil.parse(sql);
        List<PerfWarning> warnings = PerfRulesBatch2.JoinWithoutOnRule.INSTANCE.check(sql, ast);
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void join_without_on_noJoinNoTrigger() throws Exception {
        String sql = "SELECT id FROM user";
        Statement ast = CCJSqlParserUtil.parse(sql);
        List<PerfWarning> warnings = PerfRulesBatch2.JoinWithoutOnRule.INSTANCE.check(sql, ast);
        assertTrue(warnings.isEmpty());
    }

    // ---------- subquery_depth ----------

    @Test
    public void subquery_depth_1LevelNoTrigger() throws Exception {
        String sql = "SELECT id FROM user WHERE id IN (SELECT user_id FROM order)";
        Statement ast = CCJSqlParserUtil.parse(sql);
        List<PerfWarning> warnings = PerfRulesBatch2.SubqueryDepthRule.INSTANCE.check(sql, ast);
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void subquery_depth_3LevelTrigger() throws Exception {
        String sql = "SELECT id FROM user WHERE id IN " +
                "(SELECT user_id FROM order WHERE product_id IN " +
                "(SELECT id FROM product WHERE category_id IN " +
                "(SELECT id FROM category)))";
        Statement ast = CCJSqlParserUtil.parse(sql);
        List<PerfWarning> warnings = PerfRulesBatch2.SubqueryDepthRule.INSTANCE.check(sql, ast);
        assertTrue("深度 3 层应触发", !warnings.isEmpty());
        assertEquals("subquery_depth", warnings.get(0).getRuleId());
    }

    // ---------- in_clause_overflow ----------

    @Test
    public void in_clause_overflow_50ItemsNoTrigger() throws Exception {
        String items = rangeItems(1, 50);
        String sql = "SELECT id FROM user WHERE id IN (" + items + ")";
        Statement ast = CCJSqlParserUtil.parse(sql);
        List<PerfWarning> warnings = PerfRulesBatch2.InClauseOverflowRule.INSTANCE.check(sql, ast);
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void in_clause_overflow_150ItemsTrigger() throws Exception {
        String items = rangeItems(1, 150);
        String sql = "SELECT id FROM user WHERE id IN (" + items + ")";
        Statement ast = CCJSqlParserUtil.parse(sql);
        List<PerfWarning> warnings = PerfRulesBatch2.InClauseOverflowRule.INSTANCE.check(sql, ast);
        assertEquals(1, warnings.size());
        assertEquals("in_clause_overflow", warnings.get(0).getRuleId());
    }

    // ---------- function_on_column ----------

    @Test
    public void function_on_column_upperTrigger() throws Exception {
        String sql = "SELECT id FROM user WHERE UPPER(name) = 'ABC'";
        Statement ast = CCJSqlParserUtil.parse(sql);
        List<PerfWarning> warnings = PerfRulesBatch2.FunctionOnColumnRule.INSTANCE.check(sql, ast);
        assertTrue("应检测到函数包裹列", !warnings.isEmpty());
        assertEquals("function_on_column", warnings.get(0).getRuleId());
    }

    @Test
    public void function_on_column_noFunctionNoTrigger() throws Exception {
        String sql = "SELECT id FROM user WHERE name = 'ABC'";
        Statement ast = CCJSqlParserUtil.parse(sql);
        List<PerfWarning> warnings = PerfRulesBatch2.FunctionOnColumnRule.INSTANCE.check(sql, ast);
        assertTrue(warnings.isEmpty());
    }

    // ---------- or_condition_count ----------

    @Test
    public void or_condition_count_2OrNoTrigger() throws Exception {
        String sql = "SELECT id FROM user WHERE a = 1 OR b = 2";
        Statement ast = CCJSqlParserUtil.parse(sql);
        List<PerfWarning> warnings = PerfRulesBatch2.OrConditionCountRule.INSTANCE.check(sql, ast);
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void or_condition_count_5OrTrigger() throws Exception {
        String sql = "SELECT id FROM user WHERE a = 1 OR b = 2 OR c = 3 OR d = 4 OR e = 5";
        Statement ast = CCJSqlParserUtil.parse(sql);
        List<PerfWarning> warnings = PerfRulesBatch2.OrConditionCountRule.INSTANCE.check(sql, ast);
        assertEquals(1, warnings.size());
        assertEquals("or_condition_count", warnings.get(0).getRuleId());
    }

    // ---------- distinct_with_join ----------

    @Test
    public void distinct_with_join_trigger() throws Exception {
        String sql = "SELECT DISTINCT u.id FROM user u INNER JOIN order o ON o.user_id = u.id";
        Statement ast = CCJSqlParserUtil.parse(sql);
        List<PerfWarning> warnings = PerfRulesBatch2.DistinctWithJoinRule.INSTANCE.check(sql, ast);
        assertEquals(1, warnings.size());
        assertEquals("distinct_with_join", warnings.get(0).getRuleId());
    }

    @Test
    public void distinct_with_join_noJoinNoTrigger() throws Exception {
        String sql = "SELECT DISTINCT id FROM user";
        Statement ast = CCJSqlParserUtil.parse(sql);
        List<PerfWarning> warnings = PerfRulesBatch2.DistinctWithJoinRule.INSTANCE.check(sql, ast);
        assertTrue(warnings.isEmpty());
    }

    // ---------- group_by_without_aggregate ----------

    @Test
    public void group_by_without_aggregate_trigger() throws Exception {
        String sql = "SELECT id, name FROM user GROUP BY name";
        Statement ast = CCJSqlParserUtil.parse(sql);
        List<PerfWarning> warnings = PerfRulesBatch2.GroupByWithoutAggregateRule.INSTANCE.check(sql, ast);
        assertEquals(1, warnings.size());
        assertEquals("group_by_without_aggregate", warnings.get(0).getRuleId());
    }

    @Test
    public void group_by_without_aggregate_withAggregateNoTrigger() throws Exception {
        String sql = "SELECT name, COUNT(*) FROM user GROUP BY name";
        Statement ast = CCJSqlParserUtil.parse(sql);
        List<PerfWarning> warnings = PerfRulesBatch2.GroupByWithoutAggregateRule.INSTANCE.check(sql, ast);
        assertTrue(warnings.isEmpty());
    }

    // ---------- no_limit_large_result ----------

    @Test
    public void no_limit_large_result_trigger() throws Exception {
        String sql = "SELECT u.id FROM user u " +
                "INNER JOIN order o ON o.user_id = u.id " +
                "INNER JOIN product p ON p.id = o.product_id";
        Statement ast = CCJSqlParserUtil.parse(sql);
        List<PerfWarning> warnings = PerfRulesBatch2.NoLimitLargeResultRule.INSTANCE.check(sql, ast);
        assertEquals(1, warnings.size());
        assertEquals("no_limit_large_result", warnings.get(0).getRuleId());
    }

    @Test
    public void no_limit_large_result_withLimitNoTrigger() throws Exception {
        String sql = "SELECT u.id FROM user u " +
                "INNER JOIN order o ON o.user_id = u.id " +
                "INNER JOIN product p ON p.id = o.product_id LIMIT 10";
        Statement ast = CCJSqlParserUtil.parse(sql);
        List<PerfWarning> warnings = PerfRulesBatch2.NoLimitLargeResultRule.INSTANCE.check(sql, ast);
        assertTrue(warnings.isEmpty());
    }

    // ---------- AST 为 null 时降级 ----------

    @Test
    public void batch2_nullAstSafeDegrade() throws Exception {
        String sql = "SELECT id FROM user";
        assertTrue(PerfRulesBatch2.JoinWithoutOnRule.INSTANCE.check(sql, null).isEmpty());
        assertTrue(PerfRulesBatch2.SubqueryDepthRule.INSTANCE.check(sql, null).isEmpty());
        assertTrue(PerfRulesBatch2.InClauseOverflowRule.INSTANCE.check(sql, null).isEmpty());
        assertTrue(PerfRulesBatch2.FunctionOnColumnRule.INSTANCE.check(sql, null).isEmpty());
        assertTrue(PerfRulesBatch2.OrConditionCountRule.INSTANCE.check(sql, null).isEmpty());
        assertTrue(PerfRulesBatch2.DistinctWithJoinRule.INSTANCE.check(sql, null).isEmpty());
        assertTrue(PerfRulesBatch2.GroupByWithoutAggregateRule.INSTANCE.check(sql, null).isEmpty());
        assertTrue(PerfRulesBatch2.NoLimitLargeResultRule.INSTANCE.check(sql, null).isEmpty());
    }

    // ---------- 综合 ----------

    @Test
    public void batch2_allRulesRegistered() throws Exception {
        assertEquals(8, PerfRulesBatch2.all.size());
    }
}
