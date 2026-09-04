package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRole;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;

/**
 * DerivedQueryConverter 单元测试（F-14，JPA 派生查询预览）。
 *
 * 验证 PartTree → SqlStatement 转换的正确性，覆盖：
 *  - 简单属性条件（eq/ne/gt/lt/between/in/like）
 *  - 聚合类型（COUNT/EXISTS）
 *  - DELETE 语句
 *  - ORDER BY / DISTINCT / LIMIT
 *  - 降级场景（嵌套属性/空间查询/EMPTY 检查）
 */
public class DerivedQueryConverterTest {

    private static final TableInfo TABLE = new TableInfo(
            "UserEntity",
            "user",
            List.of(
                    new ColumnInfo("id", "id", true, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("firstName", "first_name", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("lastName", "last_name", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("age", "age", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("name", "name", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("status", "status", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("departmentId", "department_id", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("createTime", "create_time", false, false, null, null, null, ColumnRole.BUSINESS)),
            null, null, null, List.of());

    private static cn.song.mybatisplus.visualizer.converter.jpa.Condition simpleProp(
            String property, PartType type, boolean ignoreCase) {
        return new cn.song.mybatisplus.visualizer.converter.jpa.Condition(property, type, ignoreCase, null);
    }

    private static cn.song.mybatisplus.visualizer.converter.jpa.Condition simpleProp(String property) {
        return simpleProp(property, PartType.SIMPLE_PROPERTY, false);
    }

    private static cn.song.mybatisplus.visualizer.converter.jpa.Condition simpleProp(
            String property, PartType type) {
        return simpleProp(property, type, false);
    }

    private static PartTree findTree(PredicatePart... predicates) {
        return new PartTree(new Subject(Operation.FIND, false, null), List.of(predicates), List.of());
    }

    private static PartTree findTreeDistinct(PredicatePart... predicates) {
        return new PartTree(new Subject(Operation.FIND, true, null), List.of(predicates), List.of());
    }

    private static PartTree findTreeLimit(int limit, PredicatePart... predicates) {
        return new PartTree(new Subject(Operation.FIND, false, limit), List.of(predicates), List.of());
    }

    private static PartTree findTreeOrderBy(List<OrderByPart> orderBy, PredicatePart... predicates) {
        return new PartTree(new Subject(Operation.FIND, false, null), List.of(predicates), orderBy);
    }

    private static PartTree findTreeLimitOrderBy(int limit, List<OrderByPart> orderBy, PredicatePart... predicates) {
        return new PartTree(new Subject(Operation.FIND, false, limit), List.of(predicates), orderBy);
    }

    private static PartTree countTree(PredicatePart... predicates) {
        return new PartTree(new Subject(Operation.COUNT, false, null), List.of(predicates), List.of());
    }

    private static PartTree existsTree(PredicatePart... predicates) {
        return new PartTree(new Subject(Operation.EXISTS, false, null), List.of(predicates), List.of());
    }

    private static PartTree deleteTree(PredicatePart... predicates) {
        return new PartTree(new Subject(Operation.DELETE, false, null), List.of(predicates), List.of());
    }

    // ------------------------------------------------------------------
    // 基础条件测试
    // ------------------------------------------------------------------

    @Test
    public void findByLastName_生成等值条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("lastName"))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);
        String sql = SqlGenerator.INSTANCE.generate(statement);

        assertEquals("""
            SELECT id, first_name, last_name, age, name, status, department_id, create_time
            FROM `user`
            WHERE last_name = ?
            """.stripIndent(), sql);
    }

    @Test
    public void findByLastNameAndFirstName_生成AND连接条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(
                simpleProp("lastName"),
                simpleProp("firstName"))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);
        String sql = SqlGenerator.INSTANCE.generate(statement);

        assertEquals(2, statement.getConditions().size());
        Condition.Simple cond1 = (Condition.Simple) statement.getConditions().get(0);
        Condition.Simple cond2 = (Condition.Simple) statement.getConditions().get(1);
        assertEquals("=", cond1.getOp());
        assertEquals("=", cond2.getOp());

        assertEquals("""
            SELECT id, first_name, last_name, age, name, status, department_id, create_time
            FROM `user`
            WHERE last_name = ?
                AND first_name = ?
            """.stripIndent(), sql);
    }

    @Test
    public void findByAgeBetween_生成BETWEEN条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("age", PartType.BETWEEN))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition condition = statement.getConditions().get(0);
        assertTrue("应为 Between 条件", condition instanceof Condition.Between);
        Condition.Between between = (Condition.Between) condition;
        assertEquals("age", between.getColumn().getColumnName());
        assertFalse(between.isNegated());

        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertEquals("""
            SELECT id, first_name, last_name, age, name, status, department_id, create_time
            FROM `user`
            WHERE age BETWEEN ? AND ?
            """.stripIndent(), sql);
    }

    @Test
    public void findByNameLike_生成LIKE条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("name", PartType.LIKE))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition condition = statement.getConditions().get(0);
        assertTrue("应为 Like 条件", condition instanceof Condition.Like);
        Condition.Like like = (Condition.Like) condition;
        assertEquals("name", like.getColumn().getColumnName());
        assertFalse(like.isNegated());
        assertEquals(LikePosition.ANYWHERE, like.getPosition());

        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertEquals("""
            SELECT id, first_name, last_name, age, name, status, department_id, create_time
            FROM `user`
            WHERE name LIKE ?
            """.stripIndent(), sql);
    }

    @Test
    public void findByStatusIn_生成IN条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("status", PartType.IN))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition condition = statement.getConditions().get(0);
        assertTrue("应为 In 条件", condition instanceof Condition.In);
        Condition.In inCondition = (Condition.In) condition;
        assertEquals("status", inCondition.getColumn().getColumnName());
        assertFalse(inCondition.isNegated());

        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertEquals("""
            SELECT id, first_name, last_name, age, name, status, department_id, create_time
            FROM `user`
            WHERE status IN (?)
            """.stripIndent(), sql);
    }

    // ------------------------------------------------------------------
    // 聚合类型测试
    // ------------------------------------------------------------------

    @Test
    public void countByDepartmentId_生成COUNT聚合() throws Exception {
        PartTree partTree = countTree(new PredicatePart(Connector.AND, List.of(simpleProp("departmentId"))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        assertEquals(1, statement.getSelectColumns().size());
        assertEquals("COUNT", statement.getSelectColumns().get(0).getFunction());
        assertEquals("*", statement.getSelectColumns().get(0).getColumnName());

        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertEquals("""
            SELECT COUNT(*)
            FROM `user`
            WHERE department_id = ?
            """.stripIndent(), sql);
    }

    @Test
    public void existsByDepartmentId_生成SELECT_1() throws Exception {
        PartTree partTree = existsTree(new PredicatePart(Connector.AND, List.of(simpleProp("departmentId"))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        assertEquals(1, statement.getSelectColumns().size());
        assertEquals("1", statement.getSelectColumns().get(0).getColumnName());

        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertEquals("""
            SELECT 1
            FROM `user`
            WHERE department_id = ?
            """.stripIndent(), sql);
    }

    // ------------------------------------------------------------------
    // DELETE 测试
    // ------------------------------------------------------------------

    @Test
    public void deleteByStatus_生成DELETE语句() throws Exception {
        PartTree partTree = deleteTree(new PredicatePart(Connector.AND, List.of(simpleProp("status"))));
        SqlStatement statement = DerivedQueryConverter.convert(partTree, TABLE);

        assertTrue("应为 DeleteStatement", statement instanceof DeleteStatement);
        DeleteStatement deleteStmt = (DeleteStatement) statement;

        String sql = SqlGenerator.INSTANCE.generate(deleteStmt);
        assertEquals("""
            DELETE FROM `user`
            WHERE status = ?
            """.stripIndent(), sql);
    }

    // ------------------------------------------------------------------
    // ORDER BY / LIMIT / DISTINCT 测试
    // ------------------------------------------------------------------

    @Test
    public void findTop10ByOrderByCreateTimeDesc_生成ORDER_BY和LIMIT() throws Exception {
        PartTree partTree = findTreeLimitOrderBy(10,
                List.of(new OrderByPart("createTime", SortDirection.DESC)));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        assertEquals(1, statement.getOrderBy().size());
        assertEquals("create_time", statement.getOrderBy().get(0).getColumn().getColumnName());
        assertFalse(statement.getOrderBy().get(0).isAsc());

        assertNotNull(statement.getLimit());
        assertEquals(new SqlValue.Literal("10", "10"), statement.getLimit().getCount());
        assertEquals("10", ((SqlValue.Literal) statement.getLimit().getCount()).getRaw());

        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertEquals("""
            SELECT id, first_name, last_name, age, name, status, department_id, create_time
            FROM `user`
            ORDER BY create_time DESC
            LIMIT 10
            """.stripIndent(), sql);
    }

    @Test
    public void findDistinctByStatus_生成DISTINCT查询() throws Exception {
        PartTree partTree = findTreeDistinct(new PredicatePart(Connector.AND, List.of(simpleProp("status"))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        assertTrue(statement.isDistinct());

        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertEquals("""
            SELECT DISTINCT id, first_name, last_name, age, name, status, department_id, create_time
            FROM `user`
            WHERE status = ?
            """.stripIndent(), sql);
    }

    // ------------------------------------------------------------------
    // 复杂条件测试
    // ------------------------------------------------------------------

    @Test
    public void findByStatusAndAgeIn_生成复合条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(
                simpleProp("status"),
                simpleProp("age", PartType.IN))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);
        String sql = SqlGenerator.INSTANCE.generate(statement);

        assertTrue("应包含 status 条件", sql.contains("status = ?"));
        assertTrue("应包含 age IN 条件", sql.contains("age IN (?)"));
        assertTrue("应使用 AND 连接", sql.contains("AND"));
    }

    @Test
    public void findByFirstNameOrLastName_生成OR连接条件() throws Exception {
        PartTree partTree = new PartTree(
                new Subject(Operation.FIND, false, null),
                List.of(
                        new PredicatePart(Connector.AND, List.of(simpleProp("firstName"))),
                        new PredicatePart(Connector.OR, List.of(simpleProp("lastName")))),
                List.of());
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        assertEquals(1, statement.getConditions().size());
        Condition.Group orGroup = (Condition.Group) statement.getConditions().get(0);
        assertEquals(cn.song.mybatisplus.visualizer.model.Connector.OR, orGroup.getConnector());
        assertEquals(2, orGroup.getChildren().size());

        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertTrue("应包含 OR 连接", sql.contains("OR"));
    }

    @Test
    public void findByAgeGreaterThan_生成大于条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("age", PartType.GREATER_THAN))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Simple condition = (Condition.Simple) statement.getConditions().get(0);
        assertEquals(">", condition.getOp());
        assertEquals("age", condition.getColumn().getColumnName());

        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertTrue("应包含 > 操作符", sql.contains("age > ?"));
    }

    @Test
    public void findByStatusIsNull_生成IS_NULL条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("status", PartType.IS_NULL))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Unary condition = (Condition.Unary) statement.getConditions().get(0);
        assertEquals("IS NULL", condition.getOp());

        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertTrue("应包含 IS NULL", sql.contains("status IS NULL"));
    }

    @Test
    public void findByNameStartingWith_生成前缀匹配条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("name", PartType.STARTING_WITH))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Like condition = (Condition.Like) statement.getConditions().get(0);
        assertEquals(LikePosition.RIGHT, condition.getPosition());
        assertFalse(condition.isNegated());
    }

    @Test
    public void findByNameEndingWith_生成后缀匹配条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("name", PartType.ENDING_WITH))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Like condition = (Condition.Like) statement.getConditions().get(0);
        assertEquals(LikePosition.LEFT, condition.getPosition());
        assertFalse(condition.isNegated());
    }

    @Test
    public void findByNameContaining_生成包含匹配条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("name", PartType.CONTAINING))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Like condition = (Condition.Like) statement.getConditions().get(0);
        assertEquals(LikePosition.ANYWHERE, condition.getPosition());
        assertFalse(condition.isNegated());
    }

    @Test
    public void findByNameNotContaining_生成不包含匹配条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("name", PartType.NOT_CONTAINING))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Like condition = (Condition.Like) statement.getConditions().get(0);
        assertEquals(LikePosition.ANYWHERE, condition.getPosition());
        assertTrue(condition.isNegated());
    }

    @Test
    public void findByStatusNotEqual_生成不等条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("status", PartType.NEGATING_SIMPLE_PROPERTY))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Simple condition = (Condition.Simple) statement.getConditions().get(0);
        assertEquals("!=", condition.getOp());

        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertTrue("应包含 != 操作符", sql.contains("status != ?"));
    }

    @Test
    public void findByStatusNotIn_生成NOT_IN条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("status", PartType.NOT_IN))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.In condition = (Condition.In) statement.getConditions().get(0);
        assertTrue(condition.isNegated());

        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertTrue("应包含 NOT IN", sql.contains("status NOT IN (?)"));
    }

    @Test
    public void findByActiveTrue_生成布尔true条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("active", PartType.TRUE))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Simple condition = (Condition.Simple) statement.getConditions().get(0);
        assertEquals("=", condition.getOp());
        assertEquals(new SqlValue.Literal("true", "true"), condition.getValue());
    }

    @Test
    public void findByActiveFalse_生成布尔false条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("active", PartType.FALSE))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Simple condition = (Condition.Simple) statement.getConditions().get(0);
        assertEquals("=", condition.getOp());
        assertEquals(new SqlValue.Literal("false", "false"), condition.getValue());
    }

    @Test
    public void findByCreateTimeBefore_生成BEFORE条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("createTime", PartType.BEFORE))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Simple condition = (Condition.Simple) statement.getConditions().get(0);
        assertEquals("<", condition.getOp());
    }

    @Test
    public void findByCreateTimeAfter_生成AFTER条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("createTime", PartType.AFTER))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Simple condition = (Condition.Simple) statement.getConditions().get(0);
        assertEquals(">", condition.getOp());
    }

    @Test
    public void findByAgeLessThan_生成小于条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("age", PartType.LESS_THAN))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Simple condition = (Condition.Simple) statement.getConditions().get(0);
        assertEquals("<", condition.getOp());
    }

    @Test
    public void findByAgeLessThanEqual_生成小于等于条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("age", PartType.LESS_THAN_EQUAL))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Simple condition = (Condition.Simple) statement.getConditions().get(0);
        assertEquals("<=", condition.getOp());
    }

    @Test
    public void findByAgeGreaterThanEqual_生成大于等于条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("age", PartType.GREATER_THAN_EQUAL))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Simple condition = (Condition.Simple) statement.getConditions().get(0);
        assertEquals(">=", condition.getOp());
    }

    @Test
    public void findByStatusIsNotNull_生成IS_NOT_NULL条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("status", PartType.IS_NOT_NULL))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Unary condition = (Condition.Unary) statement.getConditions().get(0);
        assertEquals("IS NOT NULL", condition.getOp());
    }

    @Test
    public void findByNameNotLike_生成NOT_LIKE条件() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("name", PartType.NOT_LIKE))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Like condition = (Condition.Like) statement.getConditions().get(0);
        assertTrue(condition.isNegated());
        assertEquals(LikePosition.ANYWHERE, condition.getPosition());
    }

    // ------------------------------------------------------------------
    // 降级场景测试
    // ------------------------------------------------------------------

    @Test
    public void 嵌套属性路径_生成降级提示() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("address.city"))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition condition = statement.getConditions().get(0);
        assertTrue("嵌套属性应生成 Raw 条件", condition instanceof Condition.Raw);
        Condition.Raw raw = (Condition.Raw) condition;
        assertTrue("应提示关联查询", raw.getSqlText().contains("关联查询"));
    }

    @Test
    public void NEAR空间查询_生成降级提示() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("location", PartType.NEAR))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Raw condition = (Condition.Raw) statement.getConditions().get(0);
        assertEquals("空间查询不支持", condition.getSqlText());
    }

    @Test
    public void WITHIN空间查询_生成降级提示() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("location", PartType.WITHIN))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Raw condition = (Condition.Raw) statement.getConditions().get(0);
        assertEquals("空间查询不支持", condition.getSqlText());
    }

    @Test
    public void IS_EMPTY_生成降级提示() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("tags", PartType.IS_EMPTY))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Raw condition = (Condition.Raw) statement.getConditions().get(0);
        assertEquals("EMPTY 检查不支持", condition.getSqlText());
    }

    @Test
    public void IS_NOT_EMPTY_生成降级提示() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("tags", PartType.IS_NOT_EMPTY))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Raw condition = (Condition.Raw) statement.getConditions().get(0);
        assertEquals("EMPTY 检查不支持", condition.getSqlText());
    }

    @Test
    public void REGEX正则匹配_生成降级REGEXP() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("name", PartType.REGEX))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Raw condition = (Condition.Raw) statement.getConditions().get(0);
        assertEquals("REGEXP(?, ?)", condition.getSqlText());
    }

    @Test
    public void 嵌套属性EXISTS_生成IS_NOT_NULL() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("department", PartType.EXISTS))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Unary condition = (Condition.Unary) statement.getConditions().get(0);
        assertEquals("IS NOT NULL", condition.getOp());
    }

    // ------------------------------------------------------------------
    // 空谓词测试
    // ------------------------------------------------------------------

    @Test
    public void 无条件FIND_生成无条件SELECT() throws Exception {
        PartTree partTree = findTree();
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        assertTrue(statement.getConditions().isEmpty());
        assertNull(statement.getLimit());

        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertEquals("""
            SELECT id, first_name, last_name, age, name, status, department_id, create_time
            FROM `user`
            """.stripIndent(), sql);
    }

    @Test
    public void 无条件DELETE_生成无条件DELETE() throws Exception {
        PartTree partTree = deleteTree();
        DeleteStatement statement = (DeleteStatement) DerivedQueryConverter.convert(partTree, TABLE);

        assertTrue(statement.getConditions().isEmpty());

        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertEquals("""
            DELETE FROM `user`
            """.stripIndent(), sql);
    }

    // ------------------------------------------------------------------
    // 列名映射测试
    // ------------------------------------------------------------------

    @Test
    public void 驼峰属性名_正确映射为下划线列名() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("departmentId"))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Simple condition = (Condition.Simple) statement.getConditions().get(0);
        assertEquals("department_id", condition.getColumn().getColumnName());

        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertTrue("应使用下划线列名", sql.contains("department_id = ?"));
    }

    @Test
    public void 未知属性名_使用驼峰转下划线兜底() throws Exception {
        PartTree partTree = findTree(new PredicatePart(Connector.AND, List.of(simpleProp("unknownField"))));
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);

        Condition.Simple condition = (Condition.Simple) statement.getConditions().get(0);
        assertEquals("unknown_field", condition.getColumn().getColumnName());
    }

    // ------------------------------------------------------------------
    // 集成测试：与 PartTreeParser 联动
    // ------------------------------------------------------------------

    @Test
    public void PartTreeParser解析findByLastName_转换为正确SQL() throws Exception {
        PartTree partTree = PartTreeParser.INSTANCE.parse("findByLastName");
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);
        String sql = SqlGenerator.INSTANCE.generate(statement);

        assertTrue("应包含 last_name 列", sql.contains("last_name = ?"));
        assertTrue("应包含 WHERE 子句", sql.contains("WHERE"));
    }

    @Test
    public void PartTreeParser解析findByLastNameAndFirstName_转换为正确SQL() throws Exception {
        PartTree partTree = PartTreeParser.INSTANCE.parse("findByLastNameAndFirstName");
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);
        String sql = SqlGenerator.INSTANCE.generate(statement);

        assertTrue("应包含 last_name 条件", sql.contains("last_name = ?"));
        assertTrue("应包含 first_name 条件", sql.contains("first_name = ?"));
        assertTrue("应使用 AND 连接", sql.contains("AND"));
    }

    @Test
    public void PartTreeParser解析findByAgeBetween_转换为正确SQL() throws Exception {
        PartTree partTree = PartTreeParser.INSTANCE.parse("findByAgeBetween");
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);
        String sql = SqlGenerator.INSTANCE.generate(statement);

        assertTrue("应包含 BETWEEN", sql.contains("BETWEEN"));
        assertTrue("应包含 age 列", sql.contains("age"));
    }

    @Test
    public void PartTreeParser解析countByDepartmentId_转换为正确SQL() throws Exception {
        PartTree partTree = PartTreeParser.INSTANCE.parse("countByDepartmentId");
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);
        String sql = SqlGenerator.INSTANCE.generate(statement);

        assertTrue("应包含 COUNT(*)", sql.contains("COUNT(*)"));
        assertTrue("应包含 department_id 条件", sql.contains("department_id = ?"));
    }

    @Test
    public void PartTreeParser解析deleteByStatus_转换为正确SQL() throws Exception {
        PartTree partTree = PartTreeParser.INSTANCE.parse("deleteByStatus");
        SqlStatement statement = DerivedQueryConverter.convert(partTree, TABLE);
        String sql = SqlGenerator.INSTANCE.generate(statement);

        assertTrue("应为 DELETE 语句", sql.startsWith("DELETE"));
        assertTrue("应包含 status 条件", sql.contains("status = ?"));
    }

    @Test
    public void PartTreeParser解析findTop10ByAgeOrderByCreateTimeDesc_转换为正确SQL() throws Exception {
        PartTree partTree = PartTreeParser.INSTANCE.parse("findTop10ByAgeOrderByCreateTimeDesc");
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);
        String sql = SqlGenerator.INSTANCE.generate(statement);

        assertTrue("应包含 ORDER BY", sql.contains("ORDER BY"));
        assertTrue("应包含 DESC", sql.contains("DESC"));
        assertTrue("应包含 LIMIT", sql.contains("LIMIT"));
    }

    @Test
    public void PartTreeParser解析findDistinctByStatus_转换为正确SQL() throws Exception {
        PartTree partTree = PartTreeParser.INSTANCE.parse("findDistinctByStatus");
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE);
        String sql = SqlGenerator.INSTANCE.generate(statement);

        assertTrue("应包含 DISTINCT", sql.contains("DISTINCT"));
        assertTrue("应包含 status 条件", sql.contains("status = ?"));
    }
}
