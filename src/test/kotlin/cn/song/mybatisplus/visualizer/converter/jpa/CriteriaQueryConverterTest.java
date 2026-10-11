package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.converter.jpa.CriteriaQueryConverter;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRole;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.Connector;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.OrderByItem;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaOperation;
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryModel;
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaSetClause;
import cn.song.mybatisplus.visualizer.psi.jpa.ExpressionModel;
import cn.song.mybatisplus.visualizer.psi.jpa.GroupConnector;
import cn.song.mybatisplus.visualizer.psi.jpa.JoinModel;
import cn.song.mybatisplus.visualizer.psi.jpa.OrderByModel;
import cn.song.mybatisplus.visualizer.psi.jpa.PredicateModel;
import cn.song.mybatisplus.visualizer.psi.jpa.SelectColumn;
import cn.song.mybatisplus.visualizer.psi.jpa.SubqueryModel;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;

/**
 * CriteriaQueryConverter 单元测试（F-JPA-02）。
 *
 * 验证 CriteriaQueryModel → SelectStatement → SQL 的正确性，覆盖：
 *  - 简单条件（eq/ne/gt/lt/between/in/like/isNull）
 *  - 逻辑分组（and/or/not）
 *  - ORDER BY
 *  - SELECT 列选择
 *  - JOIN 子句
 *  - 降级（Raw 条件）
 */
public class CriteriaQueryConverterTest {

    private static final TableInfo TABLE = new TableInfo(
            "UserEntity",
            "user_entity",
            List.of(
                    new ColumnInfo("id", "id", true, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("username", "username", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("status", "status", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("age", "age", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("createTime", "create_time", false, false, null, null, null, ColumnRole.BUSINESS)),
            null, null, null, List.of());

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 使用默认表构建 CriteriaQueryModel 并转换。 */
    private static SelectStatement convert(CriteriaQueryModel model) {
        return (SelectStatement) CriteriaQueryConverter.convert(model, TABLE);
    }

    /** 使用给定表信息覆盖默认表构建 CriteriaQueryModel 并转换。 */
    private static SelectStatement convert(CriteriaQueryModel model, TableInfo tbl) {
        return (SelectStatement) CriteriaQueryConverter.convert(model, tbl);
    }

    /** 便捷构造模型（默认无 entityClass / groupBy / having / resultClassName）。 */
    private static CriteriaQueryModel model(
            String entityClassName,
            List<SelectColumn> selectColumns,
            List<PredicateModel> predicates,
            List<OrderByModel> orderBys,
            List<JoinModel> joins) {
        return new CriteriaQueryModel(
                entityClassName, null, selectColumns, predicates,
                List.of(), List.of(), orderBys, joins, null);
    }

    /** 便捷构造模型（T-J4/T-J17/T-J6 扩展形态：带 distinct / operation / setClauses）。 */
    private static CriteriaQueryModel modelFull(
            String entityClassName,
            List<SelectColumn> selectColumns,
            List<PredicateModel> predicates,
            List<OrderByModel> orderBys,
            List<JoinModel> joins,
            boolean distinct,
            CriteriaOperation operation,
            List<CriteriaSetClause> setClauses) {
        return new CriteriaQueryModel(
                entityClassName, null, selectColumns, predicates,
                List.of(), List.of(), orderBys, joins, null,
                distinct, operation, setClauses);
    }

    /** 便捷构造：Simple 条件。 */
    private static PredicateModel.Simple simple(String fieldName, String op, String value) {
        return new PredicateModel.Simple(fieldName, op, value);
    }

    /** 便捷构造：IsNull 条件。 */
    private static PredicateModel.IsNull isNull(String fieldName, boolean negated) {
        return new PredicateModel.IsNull(fieldName, negated);
    }

    /** 便捷构造：Between 条件。 */
    private static PredicateModel.Between between(String fieldName, String lo, String hi) {
        return new PredicateModel.Between(fieldName, lo, hi);
    }

    /** 便捷构造：Like 条件。 */
    private static PredicateModel.Like like(String fieldName, boolean negated, String pattern) {
        return new PredicateModel.Like(fieldName, negated, pattern);
    }

    /** 便捷构造：In 条件。 */
    private static PredicateModel.In inCond(String fieldName, boolean negated, List<String> values) {
        return new PredicateModel.In(fieldName, negated, values);
    }

    /** 便捷构造：Group 条件（默认未取反）。 */
    private static PredicateModel.Group group(GroupConnector connector, List<PredicateModel> children) {
        return new PredicateModel.Group(connector, children, false);
    }

    /** 便捷构造：NOT 条件。 */
    private static PredicateModel.Group not(PredicateModel child) {
        return new PredicateModel.Group(GroupConnector.AND, List.of(child), true);
    }

    // ------------------------------------------------------------------
    // 基础条件测试
    // ------------------------------------------------------------------

    @Test
    public void equal_生成等值条件() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(simple("username", "=", "'admin'")),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        String sql = SqlGenerator.INSTANCE.generate(stmt);

        Condition.Simple cond = (Condition.Simple) stmt.getConditions().get(0);
        assertEquals("username", cond.getColumn().getColumnName());
        assertEquals("=", cond.getOp());
        assertEquals(new SqlValue.Literal("'admin'", "admin"), cond.getValue());

        assertTrue("SQL 应包含 username = 'admin'", sql.contains("username = 'admin'"));
    }

    @Test
    public void notEqual_生成不等条件() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(simple("status", "!=", "0")),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);

        Condition.Simple cond = (Condition.Simple) stmt.getConditions().get(0);
        assertEquals("!=", cond.getOp());
        assertTrue("SQL 应包含 status != 0", SqlGenerator.INSTANCE.generate(stmt).contains("status != 0"));
    }

    @Test
    public void greaterThan_生成大于条件() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(simple("age", ">", "18")),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        Condition.Simple cond = (Condition.Simple) stmt.getConditions().get(0);
        assertEquals(">", cond.getOp());
        assertEquals("age", cond.getColumn().getColumnName());
    }

    @Test
    public void greaterThanOrEqualTo_生成大于等于条件() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(simple("age", ">=", "18")),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        Condition.Simple cond = (Condition.Simple) stmt.getConditions().get(0);
        assertEquals(">=", cond.getOp());
    }

    @Test
    public void lessThan_生成小于条件() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(simple("age", "<", "65")),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        Condition.Simple cond = (Condition.Simple) stmt.getConditions().get(0);
        assertEquals("<", cond.getOp());
    }

    @Test
    public void lessThanOrEqualTo_生成小于等于条件() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(simple("age", "<=", "65")),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        Condition.Simple cond = (Condition.Simple) stmt.getConditions().get(0);
        assertEquals("<=", cond.getOp());
    }

    // ------------------------------------------------------------------
    // IS NULL / IS NOT NULL 测试
    // ------------------------------------------------------------------

    @Test
    public void isNull_生成IS_NULL条件() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(isNull("createTime", false)),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        Condition.Unary cond = (Condition.Unary) stmt.getConditions().get(0);
        assertEquals("IS NULL", cond.getOp());
        assertEquals("create_time", cond.getColumn().getColumnName());
    }

    @Test
    public void isNotNull_生成IS_NOT_NULL条件() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(isNull("createTime", true)),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        Condition.Unary cond = (Condition.Unary) stmt.getConditions().get(0);
        assertEquals("IS NOT NULL", cond.getOp());
    }

    // ------------------------------------------------------------------
    // BETWEEN 测试
    // ------------------------------------------------------------------

    @Test
    public void between_生成BETWEEN条件() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(between("age", "18", "65")),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        Condition.Between cond = (Condition.Between) stmt.getConditions().get(0);
        assertEquals("age", cond.getColumn().getColumnName());
        assertFalse(cond.isNegated());

        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 BETWEEN", sql.contains("BETWEEN"));
        assertTrue("SQL 应包含 AND", sql.contains("AND"));
    }

    // ------------------------------------------------------------------
    // LIKE / NOT LIKE 测试
    // ------------------------------------------------------------------

    @Test
    public void like_生成LIKE条件() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(like("username", false, "'admin%'")),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        Condition.Like cond = (Condition.Like) stmt.getConditions().get(0);
        assertEquals("username", cond.getColumn().getColumnName());
        assertFalse(cond.isNegated());
        // T-J17：Criteria 的 LIKE 模式自带通配符，位置语义为 NONE（不二次包裹 %）
        assertEquals(LikePosition.NONE, cond.getPosition());

        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 LIKE", sql.contains("LIKE"));
        assertTrue("模式应原样渲染: " + sql, sql.contains("username LIKE 'admin%'"));
    }

    @Test
    public void notLike_生成NOT_LIKE条件() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(like("username", true, "'admin%'")),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        Condition.Like cond = (Condition.Like) stmt.getConditions().get(0);
        assertTrue(cond.isNegated());

        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 NOT LIKE", sql.contains("NOT LIKE"));
    }

    // ------------------------------------------------------------------
    // IN / NOT IN 测试
    // ------------------------------------------------------------------

    @Test
    public void in_生成IN条件() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(inCond("status", false, List.of("1", "2", "3"))),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        Condition.In cond = (Condition.In) stmt.getConditions().get(0);
        assertEquals("status", cond.getColumn().getColumnName());
        assertFalse(cond.isNegated());
        assertEquals(3, cond.getValues().size());

        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 IN", sql.contains("IN"));
    }

    @Test
    public void notIn_生成NOT_IN条件() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(inCond("status", true, List.of("0", "9"))),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        Condition.In cond = (Condition.In) stmt.getConditions().get(0);
        assertTrue(cond.isNegated());

        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 NOT IN", sql.contains("NOT IN"));
    }

    // ------------------------------------------------------------------
    // 逻辑分组（AND / OR / NOT）测试
    // ------------------------------------------------------------------

    @Test
    public void and_生成AND组() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(group(GroupConnector.AND,
                        List.of(simple("status", "=", "1"), simple("age", ">=", "18")))),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        Condition.Group cond = (Condition.Group) stmt.getConditions().get(0);
        assertEquals(Connector.AND, cond.getConnector());
        assertEquals(2, cond.getChildren().size());
    }

    @Test
    public void or_生成OR组() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(group(GroupConnector.OR,
                        List.of(simple("status", "=", "1"), simple("status", "=", "2")))),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        Condition.Group cond = (Condition.Group) stmt.getConditions().get(0);
        assertEquals(Connector.OR, cond.getConnector());

        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 OR", sql.contains("OR"));
    }

    @Test
    public void not_生成NOT包装条件() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(not(simple("status", "=", "0"))),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        Condition.Group cond = (Condition.Group) stmt.getConditions().get(0);
        assertTrue("NOT 条件应标记 negated", cond.isNegated());

        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 NOT", sql.contains("NOT"));
    }

    // ------------------------------------------------------------------
    // ORDER BY 测试
    // ------------------------------------------------------------------

    @Test
    public void orderByAsc_生成ASC排序() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(),
                List.of(new OrderByModel("createTime", true)),
                List.of());
        SelectStatement stmt = convert(model);
        assertEquals(1, stmt.getOrderBy().size());
        assertEquals("create_time", stmt.getOrderBy().get(0).getColumn().getColumnName());
        assertTrue("应为 ASC", stmt.getOrderBy().get(0).isAsc());

        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 ORDER BY", sql.contains("ORDER BY"));
        assertTrue("SQL 应包含 ASC", sql.contains("ASC"));
    }

    @Test
    public void orderByDesc_生成DESC排序() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(),
                List.of(new OrderByModel("id", false)),
                List.of());
        SelectStatement stmt = convert(model);
        assertFalse("应为 DESC", stmt.getOrderBy().get(0).isAsc());

        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 DESC", sql.contains("DESC"));
    }

    // ------------------------------------------------------------------
    // SELECT 列测试
    // ------------------------------------------------------------------

    @Test
    public void multiselect_生成指定列() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(new SelectColumn("id", null, null), new SelectColumn("username", null, null)),
                List.of(),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        assertEquals(2, stmt.getSelectColumns().size());
        assertEquals("id", stmt.getSelectColumns().get(0).getColumnName());
        assertEquals("username", stmt.getSelectColumns().get(1).getColumnName());

        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 id", sql.contains("id"));
        assertTrue("SQL 应包含 username", sql.contains("username"));
    }

    @Test
    public void 空selectColumns_生成全列SELECT() throws Exception {
        // 空 selectColumns + 带列映射的表 → 生成器展开为全列
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model, TABLE);
        // 空列表 → 生成器按 SELECT * 语义展开全列
        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 id", sql.contains("id"));
        assertTrue("SQL 应包含 username", sql.contains("username"));
        assertTrue("SQL 应包含 status", sql.contains("status"));
    }

    @Test
    public void 空selectColumns_无列映射时生成降级SELECT() throws Exception {
        // 空 selectColumns + 无列映射的表 → 列名来自 table.allColumnNames（空）
        // 此时生成器会渲染空列列表，验证不抛异常即可
        TableInfo emptyTable = new TableInfo(
                "UnknownEntity",
                "unknown_entity",
                List.of(),
                null, null, null, List.of());
        CriteriaQueryModel model = model(
                "UnknownEntity",
                List.of(),
                List.of(),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model, emptyTable);
        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 FROM", sql.contains("FROM"));
    }

    // ------------------------------------------------------------------
    // JOIN 测试
    // ------------------------------------------------------------------

    @Test
    public void join_生成LEFT_JOIN子句() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(),
                List.of(),
                List.of(new JoinModel("department", "DepartmentEntity", JoinType.LEFT, "dept")));
        SelectStatement stmt = convert(model);
        assertEquals(1, stmt.getJoins().size());
        assertEquals(JoinType.LEFT, stmt.getJoins().get(0).getType());
        assertEquals("dept", stmt.getJoins().get(0).getAlias());

        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 LEFT JOIN", sql.contains("LEFT JOIN"));
    }

    // ------------------------------------------------------------------
    // 降级（Raw 条件）测试
    // ------------------------------------------------------------------

    @Test
    public void raw条件_生成Raw占位() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(new PredicateModel.Raw("未知表达式")),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        Condition.Raw cond = (Condition.Raw) stmt.getConditions().get(0);
        assertEquals("未知表达式", cond.getSqlText());

        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含原始文本", sql.contains("未知表达式"));
    }

    // ------------------------------------------------------------------
    // 完整集成测试
    // ------------------------------------------------------------------

    @Test
    public void 完整CriteriaQueryModel_生成正确SQL() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(new SelectColumn("username", null, null), new SelectColumn("status", null, null)),
                List.of(group(GroupConnector.AND,
                        List.of(simple("status", "=", "1"), simple("age", ">=", "18")))),
                List.of(new OrderByModel("username", true)),
                List.of());
        SelectStatement stmt = convert(model);
        String sql = SqlGenerator.INSTANCE.generate(stmt);

        assertTrue("SQL 应包含 SELECT", sql.contains("SELECT"));
        assertTrue("SQL 应包含 FROM", sql.contains("FROM"));
        assertTrue("SQL 应包含 WHERE", sql.contains("WHERE"));
        assertTrue("SQL 应包含 ORDER BY", sql.contains("ORDER BY"));
        assertTrue("SQL 应包含 AND", sql.contains("AND"));
    }

    // ------------------------------------------------------------------
    // 值表达式求值测试
    // ------------------------------------------------------------------

    @Test
    public void 字符串值_正确处理引号() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(simple("username", "=", "'admin'")),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        Condition.Simple cond = (Condition.Simple) stmt.getConditions().get(0);
        assertEquals(new SqlValue.Literal("'admin'", "admin"), cond.getValue());
    }

    @Test
    public void 数值值_去除类型后缀() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(simple("age", ">", "18")),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        Condition.Simple cond = (Condition.Simple) stmt.getConditions().get(0);
        assertEquals(new SqlValue.Literal("18", "18"), cond.getValue());
    }

    @Test
    public void null值_转为NULL() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(simple("createTime", "=", "null")),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        Condition.Simple cond = (Condition.Simple) stmt.getConditions().get(0);
        assertEquals(new SqlValue.Literal("NULL", "NULL"), cond.getValue());
    }

    @Test
    public void 变量引用_转为占位符() throws Exception {
        CriteriaQueryModel model = model(
                "UserEntity",
                List.of(),
                List.of(simple("username", "=", "userNameVar")),
                List.of(),
                List.of());
        SelectStatement stmt = convert(model);
        Condition.Simple cond = (Condition.Simple) stmt.getConditions().get(0);
        assertTrue("变量引用应渲染为占位符", cond.getValue().isPlaceholder());
    }

    // ------------------------------------------------------------------
    // 无实体类信息时的降级测试
    // ------------------------------------------------------------------

    @Test
    public void 无实体类信息_按命名策略推断表名() throws Exception {
        CriteriaQueryModel model = new CriteriaQueryModel(
                "OrderEntity", null, List.of(), List.of(simple("status", "=", "1")),
                List.of(), List.of(), List.of(), List.of(), null);
        // 不传覆盖表 → converter 按命名策略推断
        SelectStatement stmt = (SelectStatement) CriteriaQueryConverter.convert(model);
        // 无 entityClass 时按命名策略推断表名：OrderEntity → order_entity
        assertEquals("order_entity", stmt.getTable().getTableName());

        Condition.Simple cond = (Condition.Simple) stmt.getConditions().get(0);
        // 无列映射时按驼峰转下划线兜底
        assertEquals("status", cond.getColumn().getColumnName());
    }

    // ------------------------------------------------------------------
    // T-J4：distinct
    // ------------------------------------------------------------------

    @Test
    public void distinct_生成SELECT_DISTINCT() throws Exception {
        CriteriaQueryModel model = modelFull(
                "UserEntity",
                List.of(),
                List.of(simple("status", "=", "1")),
                List.of(),
                List.of(),
                true, CriteriaOperation.SELECT, List.of());
        SelectStatement stmt = convert(model);
        assertTrue("应标记 distinct", stmt.isDistinct());
        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 SELECT DISTINCT: " + sql, sql.contains("SELECT DISTINCT"));
    }

    // ------------------------------------------------------------------
    // T-J17：布尔常量 / 表达式比较 / escapeChar
    // ------------------------------------------------------------------

    @Test
    public void conjunction_disjunction_生成恒真假条件() throws Exception {
        CriteriaQueryModel model = modelFull(
                "UserEntity",
                List.of(),
                List.of(
                        new PredicateModel.BooleanConstant(true),
                        new PredicateModel.BooleanConstant(false)),
                List.of(),
                List.of(),
                false, CriteriaOperation.SELECT, List.of());
        SelectStatement stmt = convert(model);
        Condition.Raw first = (Condition.Raw) stmt.getConditions().get(0);
        Condition.Raw second = (Condition.Raw) stmt.getConditions().get(1);
        assertEquals("1 = 1", first.getSqlText());
        assertEquals("1 = 0", second.getSqlText());

        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 1 = 1: " + sql, sql.contains("1 = 1"));
        assertTrue("SQL 应包含 1 = 0: " + sql, sql.contains("1 = 0"));
    }

    @Test
    public void isTrue_生成等值TRUE条件() throws Exception {
        CriteriaQueryModel model = modelFull(
                "UserEntity",
                List.of(),
                List.of(simple("status", "=", "TRUE")),
                List.of(),
                List.of(),
                false, CriteriaOperation.SELECT, List.of());
        SelectStatement stmt = convert(model);
        Condition.Simple cond = (Condition.Simple) stmt.getConditions().get(0);
        assertEquals(new SqlValue.Literal("TRUE"), cond.getValue());
        String sql = SqlGenerator.INSTANCE.generate(stmt);
        assertTrue("SQL 应包含 = TRUE: " + sql, sql.contains("status = TRUE"));
    }

    // ------------------------------------------------------------------
    // T-J17：CASE / COALESCE 表达式列
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // T-J17：聚合扩展
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // T-J17/J13：子查询渲染与降级
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // T-J7：显式 ON 优先
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // T-J6：CriteriaUpdate / CriteriaDelete
    // ------------------------------------------------------------------

}
