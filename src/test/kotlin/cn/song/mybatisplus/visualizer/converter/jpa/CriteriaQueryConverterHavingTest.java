package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.converter.jpa.CriteriaQueryConverter;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRole;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.AggregateInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryModel;
import cn.song.mybatisplus.visualizer.psi.jpa.GroupByModel;
import cn.song.mybatisplus.visualizer.psi.jpa.JoinModel;
import cn.song.mybatisplus.visualizer.psi.jpa.PredicateModel;
import cn.song.mybatisplus.visualizer.psi.jpa.SelectColumn;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;
import org.junit.Assert.assertEquals;
import org.junit.Assert.assertFalse;
import org.junit.Assert.assertNotNull;
import org.junit.Assert.assertNull;
import org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;

/**
 * CriteriaQueryConverter HAVING / 聚合函数 / JOIN ON 条件测试（Wave2-P1）。
 */
public class CriteriaQueryConverterHavingTest {

    private static final TableInfo USER_TABLE = new TableInfo(
            "UserEntity",
            "user_entity",
            List.of(
                    new ColumnInfo("id", "id", true, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("username", "username", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("status", "status", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("age", "age", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("departmentId", "department_id", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("createTime", "create_time", false, false, null, null, null, ColumnRole.BUSINESS)),
            null, null, null, List.of());

    private static SelectStatement convert(CriteriaQueryModel model) {
        return (SelectStatement) CriteriaQueryConverter.convert(model, USER_TABLE);
    }

    private static SelectStatement convert(CriteriaQueryModel model, TableInfo tbl) {
        return (SelectStatement) CriteriaQueryConverter.convert(model, tbl);
    }

    private static CriteriaQueryModel model(
            String entityClassName,
            List<SelectColumn> selectColumns,
            List<PredicateModel> predicates,
            List<GroupByModel> groupBys,
            List<PredicateModel> havings,
            List<JoinModel> joins) {
        return new CriteriaQueryModel(
                entityClassName, null, selectColumns, predicates, groupBys, havings, List.of(), joins, null);
    }

    // GROUP BY 测试

    @Test
    public void groupBy_singleColumn_generatesGroupByClause() {
        CriteriaQueryModel m = model(
                "UserEntity",
                List.of(new SelectColumn("status", null, null)),
                List.of(),
                List.of(new GroupByModel("status")),
                List.of(),
                List.of());
        SelectStatement stmt = convert(m);
        assertEquals(1, stmt.getGroupBy().size());
        assertEquals("status", stmt.getGroupBy().get(0).getColumnName());
        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 GROUP BY", sql.contains("GROUP BY"));
    }

    @Test
    public void groupBy_multipleColumns_generatesGroupByClause() {
        CriteriaQueryModel m = model(
                "UserEntity",
                List.of(),
                List.of(),
                List.of(new GroupByModel("status"), new GroupByModel("departmentId")),
                List.of(),
                List.of());
        SelectStatement stmt = convert(m);
        assertEquals(2, stmt.getGroupBy().size());
        assertEquals("status", stmt.getGroupBy().get(0).getColumnName());
        assertEquals("department_id", stmt.getGroupBy().get(1).getColumnName());
        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 GROUP BY", sql.contains("GROUP BY"));
    }

    @Test
    public void groupBy_fieldNameMapping_appliesSnakeCase() {
        CriteriaQueryModel m = model(
                "UserEntity",
                List.of(),
                List.of(),
                List.of(new GroupByModel("departmentId")),
                List.of(),
                List.of());
        SelectStatement stmt = convert(m);
        assertEquals("department_id", stmt.getGroupBy().get(0).getColumnName());
    }

    // HAVING 测试

    @Test
    public void having_singleCondition_generatesHavingClause() {
        CriteriaQueryModel m = model(
                "UserEntity",
                List.of(),
                List.of(),
                List.of(new GroupByModel("status")),
                List.of(new PredicateModel.Simple("status", ">", "0")),
                List.of());
        SelectStatement stmt = convert(m);
        assertNotNull("HAVING 应非空", stmt.getHaving());
        assertTrue("HAVING 应为 Simple 条件", stmt.getHaving() instanceof Condition.Simple);
        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 HAVING", sql.contains("HAVING"));
    }

    @Test
    public void having_multipleConditions_andConnected() {
        CriteriaQueryModel m = model(
                "UserEntity",
                List.of(),
                List.of(),
                List.of(new GroupByModel("status")),
                List.of(
                        new PredicateModel.Simple("status", ">", "0"),
                        new PredicateModel.Simple("status", "<", "100")),
                List.of());
        SelectStatement stmt = convert(m);
        assertNotNull(stmt.getHaving());
        assertTrue("多个 HAVING 条件应以 AND 连接", stmt.getHaving() instanceof Condition.Group);
        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 HAVING", sql.contains("HAVING"));
        assertTrue("SQL 应包含 AND", sql.contains("AND"));
    }

    @Test
    public void noGroupBy_noHaving() {
        CriteriaQueryModel m = model(
                "UserEntity",
                List.of(),
                List.of(new PredicateModel.Simple("status", "=", "1")),
                List.of(),
                List.of(),
                List.of());
        SelectStatement stmt = convert(m);
        assertNull("无 HAVING 条件时应为 null", stmt.getHaving());
        assertTrue("无 GROUP BY 时应为空", stmt.getGroupBy().isEmpty());
        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertFalse("SQL 不应包含 HAVING", sql.contains("HAVING"));
    }

    @Test
    public void groupByWithoutHaving_generatesGroupByOnly() {
        CriteriaQueryModel m = model(
                "UserEntity",
                List.of(),
                List.of(),
                List.of(new GroupByModel("status")),
                List.of(),
                List.of());
        SelectStatement stmt = convert(m);
        assertTrue("应有 GROUP BY", !stmt.getGroupBy().isEmpty());
        assertNull("无 HAVING 条件时 having 应为 null", stmt.getHaving());
        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 GROUP BY", sql.contains("GROUP BY"));
        assertFalse("SQL 不应包含 HAVING", sql.contains("HAVING"));
    }

    // 聚合函数测试

    @Test
    public void aggregateCount_star_generatesCountStar() {
        CriteriaQueryModel m = model(
                "UserEntity",
                List.of(new SelectColumn(null, null, new AggregateInfo("COUNT", null, false))),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        SelectStatement stmt = convert(m);
        assertEquals(1, stmt.getSelectColumns().size());
        assertEquals("COUNT", stmt.getSelectColumns().get(0).getFunction());
        assertEquals("*", stmt.getSelectColumns().get(0).getColumnName());
        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 COUNT(", sql.contains("COUNT("));
    }

    @Test
    public void aggregateSum_generatesSumFunction() {
        CriteriaQueryModel m = model(
                "UserEntity",
                List.of(new SelectColumn("age", null, new AggregateInfo("SUM", "age", false))),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        SelectStatement stmt = convert(m);
        assertEquals("SUM", stmt.getSelectColumns().get(0).getFunction());
        assertEquals("age", stmt.getSelectColumns().get(0).getColumnName());
        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 SUM(", sql.contains("SUM("));
    }

    @Test
    public void multiselect_withAggregateAndColumn() {
        CriteriaQueryModel m = model(
                "UserEntity",
                List.of(
                        new SelectColumn("status", null, null),
                        new SelectColumn(null, null, new AggregateInfo("COUNT", null, false))),
                List.of(),
                List.of(new GroupByModel("status")),
                List.of(),
                List.of());
        SelectStatement stmt = convert(m);
        assertEquals(2, stmt.getSelectColumns().size());
        assertEquals("status", stmt.getSelectColumns().get(0).getColumnName());
        assertNull("普通列无聚合函数", stmt.getSelectColumns().get(0).getFunction());
        assertEquals("COUNT", stmt.getSelectColumns().get(1).getFunction());
        assertEquals("*", stmt.getSelectColumns().get(1).getColumnName());
        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 status", sql.contains("status"));
        assertTrue("SQL 应包含 COUNT(", sql.contains("COUNT("));
        assertTrue("SQL 应包含 GROUP BY", sql.contains("GROUP BY"));
    }

    @Test
    public void aggregate_fieldNameMapping_appliesSnakeCase() {
        CriteriaQueryModel m = model(
                "UserEntity",
                List.of(new SelectColumn("departmentId", null, new AggregateInfo("SUM", "departmentId", false))),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        SelectStatement stmt = convert(m);
        assertEquals("department_id", stmt.getSelectColumns().get(0).getColumnName());
    }

    // JOIN ON 条件推断测试

    @Test
    public void join_withMatchingColumn_generatesOnCondition() {
        CriteriaQueryModel m = model(
                "UserEntity",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new JoinModel("departmentId", null, JoinType.LEFT, "dept")));
        SelectStatement stmt = convert(m);
        assertEquals(1, stmt.getJoins().size());
        assertEquals("dept", stmt.getJoins().get(0).getAlias());
        assertEquals(JoinType.LEFT, stmt.getJoins().get(0).getType());
        assertTrue("JOIN 应有 ON 条件", !stmt.getJoins().get(0).getOnConditions().isEmpty());
        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 LEFT JOIN", sql.contains("LEFT JOIN"));
        assertTrue("SQL 应包含 ON", sql.contains("ON"));
    }

    @Test
    public void join_withoutMatchingColumn_infersOnCondition() {
        CriteriaQueryModel m = model(
                "UserEntity",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new JoinModel("department", null, JoinType.LEFT, "d")));
        SelectStatement stmt = convert(m);
        assertTrue("JOIN 应有推断的 ON 条件", !stmt.getJoins().get(0).getOnConditions().isEmpty());
        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 LEFT JOIN", sql.contains("LEFT JOIN"));
        assertTrue("SQL 应包含 ON", sql.contains("ON"));
    }

    @Test
    public void join_defaultAlias_fromFieldName() {
        CriteriaQueryModel m = model(
                "UserEntity",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new JoinModel("department", null, JoinType.LEFT, null)));
        SelectStatement stmt = convert(m);
        assertEquals("department", stmt.getJoins().get(0).getAlias());
    }

    @Test
    public void join_innerJoin_generatesInnerJoin() {
        CriteriaQueryModel m = model(
                "UserEntity",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new JoinModel("department", null, JoinType.INNER, "d")));
        SelectStatement stmt = convert(m);
        assertEquals(JoinType.INNER, stmt.getJoins().get(0).getType());
        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 INNER JOIN", sql.contains("INNER JOIN"));
    }

    // 综合场景测试

    @Test
    public void groupByHavingAggregate_fullQuery() {
        CriteriaQueryModel m = model(
                "UserEntity",
                List.of(
                        new SelectColumn("status", null, null),
                        new SelectColumn(null, null, new AggregateInfo("COUNT", null, false))),
                List.of(),
                List.of(new GroupByModel("status")),
                List.of(new PredicateModel.Simple("COUNT(*)", ">", "1")),
                List.of());
        SelectStatement stmt = convert(m);
        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 SELECT", sql.contains("SELECT"));
        assertTrue("SQL 应包含 FROM", sql.contains("FROM"));
        assertTrue("SQL 应包含 GROUP BY", sql.contains("GROUP BY"));
        assertTrue("SQL 应包含 HAVING", sql.contains("HAVING"));
        assertTrue("SQL 应包含 COUNT(", sql.contains("COUNT("));
    }

    @Test
    public void joinWithGroupByHaving_fullQuery() {
        CriteriaQueryModel m = model(
                "UserEntity",
                List.of(
                        new SelectColumn("status", null, null),
                        new SelectColumn(null, null, new AggregateInfo("COUNT", null, false))),
                List.of(),
                List.of(new GroupByModel("status")),
                List.of(new PredicateModel.Simple("COUNT(*)", ">", "1")),
                List.of(new JoinModel("departmentId", null, JoinType.LEFT, "dept")));
        SelectStatement stmt = convert(m);
        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 LEFT JOIN", sql.contains("LEFT JOIN"));
        assertTrue("SQL 应包含 ON", sql.contains("ON"));
        assertTrue("SQL 应包含 GROUP BY", sql.contains("GROUP BY"));
        assertTrue("SQL 应包含 HAVING", sql.contains("HAVING"));
    }
}
