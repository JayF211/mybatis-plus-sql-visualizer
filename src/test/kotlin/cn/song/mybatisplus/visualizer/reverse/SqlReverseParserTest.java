package cn.song.mybatisplus.visualizer.reverse;

import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.Connector;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.reverse.ReverseStatementKind;
import cn.song.mybatisplus.visualizer.reverse.SelectColumn;
import cn.song.mybatisplus.visualizer.reverse.SqlReverseException;
import cn.song.mybatisplus.visualizer.reverse.SqlReverseParser;
import cn.song.mybatisplus.visualizer.reverse.SqlReverseResult;
import cn.song.mybatisplus.visualizer.reverse.JoinSubQuery;
import cn.song.mybatisplus.visualizer.reverse.ReverseJoin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 反向解析器单元测试（P0）：SQL 文本 → [SqlReverseResult]（复用 [Condition] 模型）。
 */
public class SqlReverseParserTest {

    private static SqlReverseResult parse(String sql) {
        return SqlReverseParser.INSTANCE.parse(sql);
    }

    private static ColumnRef col(String tableAlias, String columnName) {
        return new ColumnRef(tableAlias, columnName, null, null, null, null, null);
    }

    private static List<String> ops(SqlReverseResult r) {
        List<String> out = new ArrayList<>();
        for (Condition c : r.conditions()) out.add(((Condition.Simple) c).getOp());
        return out;
    }

    private static List<String> colNames(SqlReverseResult r) {
        List<String> out = new ArrayList<>();
        for (Condition c : r.conditions()) out.add(((Condition.Simple) c).getColumn().getColumnName());
        return out;
    }

    private static List<String> colNames(Condition.Group g) {
        List<String> out = new ArrayList<>();
        for (Condition c : g.getChildren()) out.add(((Condition.Simple) c).getColumn().getColumnName());
        return out;
    }

    private static List<String> raws(List<SqlValue> values) {
        List<String> out = new ArrayList<>();
        for (SqlValue v : values) out.add(((SqlValue.Literal) v).getRaw());
        return out;
    }

    private static SqlReverseException assertThrows(Runnable block) {
        try {
            block.run();
            throw new AssertionError("expected SqlReverseException");
        } catch (SqlReverseException e) {
            return e;
        }
    }

    // ------------------------------------------------------------------
    // 语句结构
    // ------------------------------------------------------------------

    @Test
    public void 无条件_表名与星号列() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM user");
        assertEquals("user", result.tableName());
        assertNull(result.selectColumns());
        assertTrue(result.conditions().isEmpty());
        assertTrue(result.orderBy().isEmpty());
        assertNull(result.limit());
    }

    @Test
    public void 简单列清单_保留顺序() throws Exception {
        SqlReverseResult result = parse("SELECT id, name, age FROM user");
        assertEquals(List.of(new SelectColumn("id", null), new SelectColumn("name", null), new SelectColumn("age", null)), result.selectColumns());
    }

    @Test
    public void 函数列_整体放弃select生成() throws Exception {
        assertNull(parse("SELECT COUNT(*) FROM user").selectColumns());
        assertNull(parse("SELECT id, COUNT(*) FROM user").selectColumns());
        assertNull(parse("SELECT MAX(age) AS maxAge FROM user").selectColumns());
    }

    @Test
    public void 反引号与点分_剥壳取末段() throws Exception {
        SqlReverseResult result = parse("SELECT `id`, t.name FROM `my_db`.`user`");
        assertEquals("user", result.tableName());
        assertEquals(List.of(new SelectColumn("`id`", null), new SelectColumn("t.name", null)), result.selectColumns());
    }

    @Test
    public void 反引号列紧跟表别名_不吞列() throws Exception {
        SqlReverseResult result = parse("SELECT t.`signal`, id FROM user t");
        assertEquals(List.of(new SelectColumn("t.`signal`", null), new SelectColumn("id", null)), result.selectColumns());
    }

    @Test
    public void SELECT列别名_捕获为SelectColumn别名() throws Exception {
        SqlReverseResult result = parse("SELECT t.name AS productName, t.model productModel, id FROM user t");
        assertEquals(
                List.of(new SelectColumn("t.name", "productName"), new SelectColumn("t.model", "productModel"), new SelectColumn("id", null)),
                result.selectColumns());
    }

    @Test
    public void 库前缀表名_取末段() throws Exception {
        assertEquals("user", parse("SELECT * FROM mydb.user").tableName());
    }

    @Test
    public void 表别名_消费但不影响解析() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM user u WHERE u.id = 1");
        assertEquals("user", result.tableName());
        assertEquals(1, result.conditions().size());
    }

    @Test
    public void 多行缩进与注释_忽略() throws Exception {
        SqlReverseResult result = parse("-- 查询用户\nSELECT id /* 主键 */, name\nFROM user\nWHERE age > 18");
        assertEquals(List.of(new SelectColumn("id", null), new SelectColumn("name", null)), result.selectColumns());
        assertEquals(1, result.conditions().size());
    }

    @Test
    public void 结尾分号_允许() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM user WHERE id = 1;");
        assertEquals(1, result.conditions().size());
    }

    // ------------------------------------------------------------------
    // WHERE 条件
    // ------------------------------------------------------------------

    @Test
    public void 字符串值_保留引号原文与解转义() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM user WHERE name = '张三'");
        Condition.Simple c = (Condition.Simple) result.conditions().get(0);
        assertEquals("name", c.getColumn().getColumnName());
        assertEquals("=", c.getOp());
        SqlValue.Literal value = (SqlValue.Literal) c.getValue();
        assertEquals("'张三'", value.getRaw());
        assertEquals("张三", value.getUnquoted());
    }

    @Test
    public void 字符串双写转义_解转义() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM user WHERE name = 'O''Brien'");
        Condition.Simple c = (Condition.Simple) result.conditions().get(0);
        assertEquals("O'Brien", ((SqlValue.Literal) c.getValue()).getUnquoted());
    }

    @Test
    public void 数字与布尔值_原样字面量() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM user WHERE age > 18 AND flag = TRUE AND off = FALSE");
        List<Condition> conditions = result.conditions();
        assertEquals("18", ((SqlValue.Literal) ((Condition.Simple) conditions.get(0)).getValue()).getRaw());
        assertEquals("true", ((SqlValue.Literal) ((Condition.Simple) conditions.get(1)).getValue()).getRaw());
        assertEquals("false", ((SqlValue.Literal) ((Condition.Simple) conditions.get(2)).getValue()).getRaw());
    }

    @Test
    public void 比较操作符全家() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a = 1 AND b != 2 AND c <> 3 AND d > 4 AND e >= 5 AND f < 6 AND g <= 7");
        assertEquals(List.of("=", "!=", "<>", ">", ">=", "<", "<="), ops(result));
    }

    @Test
    public void 等号NULL_映射ISNULL() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a = NULL");
        Condition.Unary c = (Condition.Unary) result.conditions().get(0);
        assertEquals("a", c.getColumn().getColumnName());
        assertEquals("IS NULL", c.getOp());
    }

    @Test
    public void 不等号NULL_映射ISNOTNULL() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a != NULL");
        assertEquals("IS NOT NULL", ((Condition.Unary) result.conditions().get(0)).getOp());
    }

    @Test
    public void ISNULL与ISNOTNULL() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a IS NULL AND b IS NOT NULL");
        Condition.Unary a = (Condition.Unary) result.conditions().get(0);
        Condition.Unary b = (Condition.Unary) result.conditions().get(1);
        assertEquals("IS NULL", a.getOp());
        assertEquals("IS NOT NULL", b.getOp());
    }

    @Test
    public void IN与NOTIN_多值() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE status IN (1, 2, 3) AND name NOT IN ('a', 'b')");
        Condition.In in1 = (Condition.In) result.conditions().get(0);
        assertTrue(!in1.getNegated());
        assertEquals(List.of("1", "2", "3"), raws(in1.getValues()));
        Condition.In in2 = (Condition.In) result.conditions().get(1);
        assertTrue(in2.getNegated());
        List<String> unq = new ArrayList<>();
        for (SqlValue v : in2.getValues()) unq.add(((SqlValue.Literal) v).getUnquoted());
        assertEquals(List.of("a", "b"), unq);
    }

    @Test
    public void BETWEEN与NOTBETWEEN() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a BETWEEN 1 AND 5 AND b NOT BETWEEN 1 AND 2");
        Condition.Between between = (Condition.Between) result.conditions().get(0);
        assertTrue(!between.getNegated());
        assertEquals("1", ((SqlValue.Literal) between.getLo()).getRaw());
        assertEquals("5", ((SqlValue.Literal) between.getHi()).getRaw());
        assertTrue(((Condition.Between) result.conditions().get(1)).getNegated());
    }

    @Test
    public void LIKE通配符位置_映射LikePosition() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a LIKE '%abc%' AND b LIKE 'abc%' AND c LIKE '%abc'");
        Condition.Like a = (Condition.Like) result.conditions().get(0);
        assertEquals(LikePosition.ANYWHERE, a.getPosition());
        assertEquals("abc", ((SqlValue.Literal) a.getPattern()).getUnquoted());
        assertEquals(LikePosition.RIGHT, ((Condition.Like) result.conditions().get(1)).getPosition());
        assertEquals(LikePosition.LEFT, ((Condition.Like) result.conditions().get(2)).getPosition());
    }

    @Test
    public void NOTLIKE_negated标记() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a NOT LIKE '%abc%'");
        Condition.Like a = (Condition.Like) result.conditions().get(0);
        assertTrue(a.getNegated());
        assertEquals(LikePosition.ANYWHERE, a.getPosition());
    }

    @Test
    public void LIKE无通配符_降级Raw并记warning() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a LIKE 'abc'");
        Condition.Raw raw = (Condition.Raw) result.conditions().get(0);
        assertEquals("a LIKE 'abc'", raw.getSqlText());
        assertEquals(1, result.warnings().size());
    }

    @Test
    public void LIKE中间百分号或下划线_降级Raw() throws Exception {
        assertTrue(parse("SELECT * FROM t WHERE a LIKE 'a%b'").conditions().get(0) instanceof Condition.Raw);
        assertTrue(parse("SELECT * FROM t WHERE a LIKE 'a_b'").conditions().get(0) instanceof Condition.Raw);
        assertTrue(parse("SELECT * FROM t WHERE a LIKE 'ab'").conditions().get(0) instanceof Condition.Raw);
    }

    @Test
    public void 别名列_剥离前缀() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE u.a = 1 AND `v`.`b` = 2");
        assertEquals("a", ((Condition.Simple) result.conditions().get(0)).getColumn().getColumnName());
        assertEquals("b", ((Condition.Simple) result.conditions().get(1)).getColumn().getColumnName());
    }

    @Test
    public void 顶层AND连接_顺序保留() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a = 1 AND b = 2 AND c = 3");
        assertEquals(3, result.conditions().size());
        assertEquals(List.of("a", "b", "c"), colNames(result));
    }

    // ------------------------------------------------------------------
    // ORDER BY / LIMIT
    // ------------------------------------------------------------------

    @Test
    public void ORDERBY_默认ASC与显式DESC() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a = 1 ORDER BY id DESC, name");
        assertEquals(2, result.orderBy().size());
        assertTrue(!result.orderBy().get(0).getAsc());
        assertEquals("id", result.orderBy().get(0).getColumn().getColumnName());
        assertTrue(result.orderBy().get(1).getAsc());
    }

    @Test
    public void LIMIT_单值与偏移形态() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t LIMIT 10");
        assertEquals("10", ((SqlValue.Literal) result.limit().getCount()).getRaw());
        assertNull(result.limit().getOffset());

        SqlReverseResult paged = parse("SELECT * FROM t LIMIT 5, 10");
        assertEquals("5", ((SqlValue.Literal) paged.limit().getOffset()).getRaw());
        assertEquals("10", ((SqlValue.Literal) paged.limit().getCount()).getRaw());
    }

    @Test
    public void LIMIT_方言形态_宽容解析() throws Exception {
        SqlReverseResult pg = parse("SELECT * FROM t LIMIT 10 OFFSET 5");
        assertEquals("5", ((SqlValue.Literal) pg.limit().getOffset()).getRaw());
        assertEquals("10", ((SqlValue.Literal) pg.limit().getCount()).getRaw());

        SqlReverseResult offsetFetch = parse("SELECT * FROM t OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY");
        assertEquals("5", ((SqlValue.Literal) offsetFetch.limit().getOffset()).getRaw());
        assertEquals("10", ((SqlValue.Literal) offsetFetch.limit().getCount()).getRaw());

        SqlReverseResult fetchFirst = parse("SELECT * FROM t FETCH FIRST 10 ROWS ONLY");
        assertNull(fetchFirst.limit().getOffset());
        assertEquals("10", ((SqlValue.Literal) fetchFirst.limit().getCount()).getRaw());
        SqlReverseResult fetchNext = parse("SELECT * FROM t FETCH NEXT 10 ROWS ONLY");
        assertNull(fetchNext.limit().getOffset());
        assertEquals("10", ((SqlValue.Literal) fetchNext.limit().getCount()).getRaw());

        SqlReverseResult withWhere = parse("SELECT * FROM t WHERE a = 1 OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY");
        assertEquals(1, withWhere.conditions().size());
        assertEquals("5", ((SqlValue.Literal) withWhere.limit().getOffset()).getRaw());
        assertEquals("10", ((SqlValue.Literal) withWhere.limit().getCount()).getRaw());

        SqlReverseResult columnOffset = parse("SELECT * FROM t WHERE offset = 1");
        assertEquals("offset", ((Condition.Simple) columnOffset.conditions().get(0)).getColumn().getColumnName());
        SqlReverseResult columnFetch = parse("SELECT * FROM t WHERE fetch = 1");
        assertEquals("fetch", ((Condition.Simple) columnFetch.conditions().get(0)).getColumn().getColumnName());
        SqlReverseResult likeOffset = parse("SELECT * FROM t WHERE offset LIKE 'x'");
        assertEquals("offset LIKE 'x'", ((Condition.Raw) likeOffset.conditions().get(0)).getSqlText());
    }

    @Test
    public void LIMIT_方言形态_占位符() throws Exception {
        SqlReverseResult pg = parse("SELECT * FROM t LIMIT ? OFFSET ?");
        assertTrue(pg.limit().getOffset() instanceof SqlValue.Placeholder);
        assertTrue(pg.limit().getCount() instanceof SqlValue.Placeholder);

        SqlReverseResult offsetFetch = parse("SELECT * FROM t OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        assertTrue(offsetFetch.limit().getOffset() instanceof SqlValue.Placeholder);
        assertTrue(offsetFetch.limit().getCount() instanceof SqlValue.Placeholder);

        SqlReverseResult fetchFirst = parse("SELECT * FROM t FETCH FIRST ? ROWS ONLY");
        assertNull(fetchFirst.limit().getOffset());
        assertTrue(fetchFirst.limit().getCount() instanceof SqlValue.Placeholder);
    }

    // ------------------------------------------------------------------
    // 错误场景（P1+ 范围明确报错）
    // ------------------------------------------------------------------

    @Test
    public void 非CRUD语句_报错() throws Exception {
        SqlReverseException ex = assertThrows(() -> parse("CREATE TABLE t (id INT)"));
        assertEquals(SqlReverseParser.KEY_NOT_SELECT, ex.getKey());
    }

    @Test
    public void 顶层OR_还原为Group() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a = 1 OR b = 2");
        Condition.Group group = (Condition.Group) result.conditions().get(0);
        assertEquals(Connector.OR, group.getConnector());
        assertEquals(2, group.getChildren().size());
        assertEquals("a", ((Condition.Simple) group.getChildren().get(0)).getColumn().getColumnName());
        assertEquals("b", ((Condition.Simple) group.getChildren().get(1)).getColumn().getColumnName());
    }

    @Test
    public void 混合优先级_AND段包Group() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a = 1 AND b = 2 OR c = 3");
        Condition.Group group = (Condition.Group) result.conditions().get(0);
        assertEquals(Connector.OR, group.getConnector());
        Condition.Group andGroup = (Condition.Group) group.getChildren().get(0);
        assertEquals(Connector.AND, andGroup.getConnector());
        assertEquals(2, andGroup.getChildren().size());
        assertEquals("c", ((Condition.Simple) group.getChildren().get(1)).getColumn().getColumnName());
    }

    @Test
    public void 括号组_还原Group保留括号() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE (a = 1 AND b = 2) AND c = 3");
        assertEquals(2, result.conditions().size());
        Condition.Group group = (Condition.Group) result.conditions().get(0);
        assertEquals(Connector.AND, group.getConnector());
        assertEquals(2, group.getChildren().size());
        assertEquals("c", ((Condition.Simple) result.conditions().get(1)).getColumn().getColumnName());
    }

    @Test
    public void 括号组内OR_单组直接复用() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE (a = 1 OR b = 2) AND c = 3");
        Condition.Group group = (Condition.Group) result.conditions().get(0);
        assertEquals(Connector.OR, group.getConnector());
        assertEquals(2, group.getChildren().size());
    }

    @Test
    public void 括号组内单条件_解包不产生Group() throws Exception {
        // T-ReverseParenWira：MPJ 渲染自带括号（ON (x=y)、WHERE (x=1)），单条件括号
        // 无语义增量 → 解包。否则 ON 侧 extractJoinKey 认不出列对致 MPJ lambda 退化，
        // WHERE 侧多渲染一层 nested
        SqlReverseResult result = parse("SELECT * FROM t WHERE (t.id = 1)");
        assertEquals(1, result.conditions().size());
        assertTrue(result.conditions().get(0) instanceof Condition.Simple);

        SqlReverseResult on = parse("SELECT * FROM a t LEFT JOIN b st ON (st.station_id = t.id)");
        java.util.List<Condition> onConditions = on.joins().get(0).onConditions();
        assertEquals(1, onConditions.size());
        assertTrue(onConditions.get(0) instanceof Condition.ColumnComparison);
    }

    @Test
    public void NOT单条件括号_保留negated组() throws Exception {
        // 解包例外：NOT (a = 1) 的 negated 标记须由 Group 承载
        SqlReverseResult result = parse("SELECT * FROM t WHERE NOT (a = 1)");
        Condition.Group group = (Condition.Group) result.conditions().get(0);
        assertTrue(group.getNegated());
        assertEquals(1, group.getChildren().size());
    }

    @Test
    public void NOT括号组_negated标记() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE NOT (a = 1 OR b = 2)");
        Condition.Group group = (Condition.Group) result.conditions().get(0);
        assertTrue(group.getNegated());
        assertEquals(Connector.OR, group.getConnector());
    }

    @Test
    public void 嵌套组_递归还原() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE (a = 1 OR b = 2) AND (c = 3 OR (d = 4 AND e = 5))");
        assertEquals(2, result.conditions().size());
        Condition.Group outer = (Condition.Group) result.conditions().get(1);
        assertEquals(Connector.OR, outer.getConnector());
        Condition.Group inner = (Condition.Group) outer.getChildren().get(1);
        assertEquals(Connector.AND, inner.getConnector());
        assertEquals(2, inner.getChildren().size());
    }

    @Test
    public void DISTINCT_不收集为列() throws Exception {
        SqlReverseResult result = parse("SELECT DISTINCT id, name FROM t WHERE a = 1");
        assertNull(result.selectColumns());
    }

    // ------------------------------------------------------------------
    // 降级透传（T-M36.1：表达式形态 → apply() 原文）
    // ------------------------------------------------------------------

    @Test
    public void 函数条件_降级Raw透传() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE DATE_FORMAT(create_time, '%Y') = '2024'");
        Condition.Raw raw = (Condition.Raw) result.conditions().get(0);
        assertEquals("DATE_FORMAT(create_time, '%Y') = '2024'", raw.getSqlText());
        assertEquals(1, result.warnings().size());
    }

    @Test
    public void 右侧函数_降级Raw透传() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a = DATE_FORMAT(now(), '%Y')");
        assertTrue(result.conditions().get(0) instanceof Condition.Raw);
    }

    @Test
    public void 列对列_解析为ColumnComparison() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a = b");
        Condition.ColumnComparison cc = (Condition.ColumnComparison) result.conditions().get(0);
        assertEquals("a", cc.getLeft().getColumnName());
        assertEquals("b", cc.getRight().getColumnName());
    }

    @Test
    public void 右侧函数_仍降级Raw透传() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a = DATE_FORMAT(now(), '%Y')");
        assertTrue(result.conditions().get(0) instanceof Condition.Raw);
    }

    // ------------------------------------------------------------------
    // 多表 JOIN（T-M37）
    // ------------------------------------------------------------------

    @Test
    public void LEFTJOIN_单等值ON解析为ColumnComparison() throws Exception {
        SqlReverseResult result = parse("SELECT t.id, t1.city FROM user t LEFT JOIN address t1 ON t1.user_id = t.id WHERE t.status = 1");
        assertEquals(1, result.joins().size());
        ReverseJoin join = result.joins().get(0);
        assertEquals("address", join.tableName());
        assertEquals("t1", join.alias());
        Condition.ColumnComparison on = (Condition.ColumnComparison) join.onConditions().get(0);
        assertEquals("user_id", on.getLeft().getColumnName());
        assertEquals("t1", on.getLeft().getTableAlias());
        assertEquals("id", on.getRight().getColumnName());
        assertEquals("t", on.getRight().getTableAlias());
        Condition.Simple cond = (Condition.Simple) result.conditions().get(0);
        assertEquals("t", cond.getColumn().getTableAlias());
        assertEquals(List.of(new SelectColumn("t.id", null), new SelectColumn("t1.city", null)), result.selectColumns());
        assertNotNull(join.rawOn());
        assertNotNull(join.rawOn());
        assertTrue(join.rawOn().contains("t1.user_id = t.id"));
    }

    @Test
    public void 多JOIN链与ON多条件() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM a t LEFT JOIN b t1 ON t1.a_id = t.id AND t1.type = 1 INNER JOIN c t2 ON t2.b_id = t1.id");
        assertEquals(2, result.joins().size());
        assertEquals("LEFT", result.joins().get(0).type().name());
        assertEquals(2, result.joins().get(0).onConditions().size());
        assertTrue(result.joins().get(0).onConditions().get(0) instanceof Condition.ColumnComparison);
        assertTrue(result.joins().get(0).onConditions().get(1) instanceof Condition.Simple);
        assertEquals("INNER", result.joins().get(1).type().name());
        assertEquals("t2", result.joins().get(1).alias());
    }

    @Test
    public void JOIN无别名_不归属副实体() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM a LEFT JOIN b ON b.a_id = a.id");
        assertEquals(1, result.joins().size());
        assertNull(result.joins().get(0).alias());
    }

    @Test
    public void RIGHT与FULLJOIN_类型解析() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM a RIGHT JOIN b ON b.a_id = a.id FULL OUTER JOIN c ON c.b_id = b.id");
        assertEquals("RIGHT", result.joins().get(0).type().name());
        assertEquals("FULL", result.joins().get(1).type().name());
    }

    @Test
    public void 子查询_降级Raw透传() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a IN (SELECT id FROM u)");
        Condition.Raw raw = (Condition.Raw) result.conditions().get(0);
        assertEquals("a IN (SELECT id FROM u)", raw.getSqlText());
    }

    @Test
    public void 参数占位符_建模Placeholder() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a = ?");
        Condition.Simple simple = (Condition.Simple) result.conditions().get(0);
        assertEquals("=", simple.getOp());
        assertTrue(simple.getValue() instanceof SqlValue.Placeholder);
        assertEquals(0, result.warnings().size());
    }

    @Test
    public void 参数占位符_各比较算子() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a != ? AND b > ? AND c >= ? AND d < ? AND e <= ? AND f <> ?");
        List<String> ops = List.of("!=", ">", ">=", "<", "<=", "<>");
        assertEquals(6, result.conditions().size());
        for (int i = 0; i < result.conditions().size(); i++) {
            Condition.Simple simple = (Condition.Simple) result.conditions().get(i);
            assertEquals(ops.get(i), simple.getOp());
            assertTrue("第 " + i + " 个条件应为占位符", simple.getValue() instanceof SqlValue.Placeholder);
        }
        assertEquals(0, result.warnings().size());
    }

    @Test
    public void 参数占位符_IN与BETWEEN() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a IN (?, ?) AND b BETWEEN ? AND ?");
        Condition.In inCond = (Condition.In) result.conditions().get(0);
        assertTrue(inCond.getValues().stream().allMatch(v -> v instanceof SqlValue.Placeholder));
        Condition.Between between = (Condition.Between) result.conditions().get(1);
        assertTrue(between.getLo() instanceof SqlValue.Placeholder);
        assertTrue(between.getHi() instanceof SqlValue.Placeholder);
    }

    @Test
    public void 参数占位符_LIKE映射NONE() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a LIKE ?");
        Condition.Like like = (Condition.Like) result.conditions().get(0);
        assertEquals(LikePosition.NONE, like.getPosition());
        assertTrue(like.getPattern() instanceof SqlValue.Placeholder);
        SqlReverseResult negated = parse("SELECT * FROM t WHERE a NOT LIKE ?");
        assertTrue(((Condition.Like) negated.conditions().get(0)).getNegated());
    }

    @Test
    public void 参数占位符_UPDATE_SET值() throws Exception {
        SqlReverseResult result = parse("UPDATE t SET a = ? WHERE b = ?");
        assertEquals(ReverseStatementKind.UPDATE, result.kind());
        assertTrue(result.sets().get(0).value() instanceof SqlValue.Placeholder);
        assertTrue(((Condition.Simple) result.conditions().get(0)).getValue() instanceof SqlValue.Placeholder);
    }

    @Test
    public void 参数占位符_INSERT多行值() throws Exception {
        SqlReverseResult result = parse("INSERT INTO t (a, b) VALUES (?, ?), (?, ?)");
        assertEquals(ReverseStatementKind.INSERT, result.kind());
        assertEquals(2, result.insertValues().size());
        for (List<SqlValue> row : result.insertValues()) {
            assertEquals(2, row.size());
            for (SqlValue v : row) assertTrue(v instanceof SqlValue.Placeholder);
        }
    }

    @Test
    public void 参数占位符_LIMIT偏移形态() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a = ? LIMIT ?, ?");
        assertTrue(result.limit().getOffset() instanceof SqlValue.Placeholder);
        assertTrue(result.limit().getCount() instanceof SqlValue.Placeholder);
        SqlReverseResult single = parse("SELECT * FROM t LIMIT ?");
        assertNull(single.limit().getOffset());
        assertTrue(single.limit().getCount() instanceof SqlValue.Placeholder);
    }

    @Test
    public void 参数占位符与函数降级_混合边界() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE a = ? AND DATE_FORMAT(create_time, '%Y') = '2024'");
        assertEquals(2, result.conditions().size());
        assertTrue(((Condition.Simple) result.conditions().get(0)).getValue() instanceof SqlValue.Placeholder);
        assertTrue(result.conditions().get(1) instanceof Condition.Raw);
        assertEquals(1, result.warnings().size());
    }

    @Test
    public void 降级条件与普通条件混合_边界正确() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t WHERE DATE_FORMAT(create_time, '%Y') = '2024' AND a = 1 OR b = 2");
        Condition.Group group = (Condition.Group) result.conditions().get(0);
        assertEquals(Connector.OR, group.getConnector());
        Condition.Group andGroup = (Condition.Group) group.getChildren().get(0);
        assertTrue(andGroup.getChildren().get(0) instanceof Condition.Raw);
        assertEquals("a", ((Condition.Simple) andGroup.getChildren().get(1)).getColumn().getColumnName());
        assertEquals("b", ((Condition.Simple) group.getChildren().get(1)).getColumn().getColumnName());
        assertEquals(1, result.warnings().size());
    }

    @Test
    public void GROUPBY与HAVING_报错() throws Exception {
        assertThrows(() -> parse("SELECT * FROM t GROUP BY a"));
        assertThrows(() -> parse("SELECT * FROM t HAVING a > 1"));
    }

    @Test
    public void UPDATE含JOIN_报错() throws Exception {
        assertThrows(() -> parse("UPDATE t SET a = 1 LEFT JOIN u ON t.id = u.tid"));
    }

    @Test
    public void 多条语句_报错() throws Exception {
        SqlReverseException ex = assertThrows(() -> parse("SELECT * FROM t; SELECT * FROM u"));
        assertTrue(ex.getDetail().contains("多条"));
    }

    @Test
    public void 缺少FROM_报错() throws Exception {
        assertThrows(() -> parse("SELECT 1"));
    }

    @Test
    public void 空文本与未闭合字符串_报错() throws Exception {
        assertThrows(() -> parse("   "));
        SqlReverseException ex = assertThrows(() -> parse("SELECT * FROM t WHERE a = 'abc"));
        assertEquals(SqlReverseParser.KEY_PARSE, ex.getKey());
    }

    // ------------------------------------------------------------------
    // 增删改查（T-M36.7）
    // ------------------------------------------------------------------

    @Test
    public void UPDATE_解析SET与条件() throws Exception {
        SqlReverseResult result = parse("UPDATE user SET name = '李四', age = 20 WHERE id = 1");
        assertEquals(ReverseStatementKind.UPDATE, result.kind());
        assertEquals("user", result.tableName());
        assertEquals(2, result.sets().size());
        ReverseSet set1 = result.sets().get(0);
        assertEquals("name", set1.column());
        assertEquals("李四", ((SqlValue.Literal) set1.value()).getUnquoted());
        assertEquals("age", result.sets().get(1).column());
        Condition.Simple cond = (Condition.Simple) result.conditions().get(0);
        assertEquals("id", cond.getColumn().getColumnName());
        assertEquals("1", ((SqlValue.Literal) cond.getValue()).getRaw());
    }

    @Test
    public void UPDATE_SET函数值_降级rawSql透传() throws Exception {
        SqlReverseResult result = parse("UPDATE user SET update_time = NOW(), name = 'x' WHERE id = 1");
        assertEquals(2, result.sets().size());
        ReverseSet raw = result.sets().get(0);
        assertEquals("update_time", raw.column());
        assertTrue(raw.rawSql() != null && raw.rawSql().contains("update_time = NOW()"));
        assertEquals("name", result.sets().get(1).column());
    }

    @Test
    public void UPDATE_SETNULL_值解析为null() throws Exception {
        SqlReverseResult result = parse("UPDATE user SET deleted = NULL WHERE id = 1");
        assertNull(result.sets().get(0).value());
    }

    @Test
    public void DELETE_解析条件() throws Exception {
        SqlReverseResult result = parse("DELETE FROM user WHERE id = 1");
        assertEquals(ReverseStatementKind.DELETE, result.kind());
        assertEquals("user", result.tableName());
        assertEquals(1, result.conditions().size());
        assertTrue(result.deleteTargets().isEmpty());
    }

    @Test
    public void DELETE_多表目标_解析目标清单与JOIN() throws Exception {
        SqlReverseResult result = parse("DELETE t, t1 FROM user t LEFT JOIN address t1 ON t1.user_id = t.id WHERE t.status = 1");
        assertEquals(ReverseStatementKind.DELETE, result.kind());
        assertEquals("user", result.tableName());
        assertEquals(List.of("t", "t1"), result.deleteTargets());
        assertEquals(1, result.joins().size());
        assertEquals("t1", result.joins().get(0).alias());
        Condition.Simple cond = (Condition.Simple) result.conditions().get(0);
        assertEquals("t", cond.getColumn().getTableAlias());
    }

    @Test
    public void DELETE_多表目标_无JOIN() throws Exception {
        SqlReverseResult result = parse("DELETE t FROM user t WHERE t.id = 1");
        assertEquals(List.of("t"), result.deleteTargets());
        assertTrue(result.joins().isEmpty());
        assertEquals(1, result.conditions().size());
    }

    @Test
    public void DELETE_多表目标_反引号剥壳() throws Exception {
        SqlReverseResult result = parse("DELETE `t`, `t1` FROM user t LEFT JOIN address t1 ON t1.user_id = t.id");
        assertEquals(List.of("t", "t1"), result.deleteTargets());
    }

    @Test
    public void DELETE_多表目标_缺少FROM_报错() throws Exception {
        SqlReverseException ex = assertThrows(() -> parse("DELETE t, t1 WHERE id = 1"));
        assertEquals(SqlReverseParser.KEY_PARSE, ex.getKey());
    }

    @Test
    public void INSERT_解析列与值() throws Exception {
        SqlReverseResult result = parse("INSERT INTO user (name, age) VALUES ('张三', 18)");
        assertEquals(ReverseStatementKind.INSERT, result.kind());
        assertEquals(List.of("name", "age"), result.insertColumns());
        assertEquals(1, result.insertValues().size());
        assertEquals("张三", ((SqlValue.Literal) result.insertValues().get(0).get(0)).getUnquoted());
        assertEquals("18", ((SqlValue.Literal) result.insertValues().get(0).get(1)).getRaw());
    }

    @Test
    public void INSERT_多行VALUES_解析为行列表() throws Exception {
        SqlReverseResult result = parse("INSERT INTO user (name, age) VALUES ('张三', 18), ('李四', 20)");
        assertEquals(2, result.insertValues().size());
        assertEquals(2, result.insertValues().get(0).size());
        assertEquals("张三", ((SqlValue.Literal) result.insertValues().get(0).get(0)).getUnquoted());
        assertEquals("18", ((SqlValue.Literal) result.insertValues().get(0).get(1)).getRaw());
        assertEquals("李四", ((SqlValue.Literal) result.insertValues().get(1).get(0)).getUnquoted());
        assertEquals("20", ((SqlValue.Literal) result.insertValues().get(1).get(1)).getRaw());
    }

    @Test
    public void INSERT_多行VALUES_列数不齐_报错() throws Exception {
        SqlReverseException ex = assertThrows(() -> parse("INSERT INTO user (name, age) VALUES ('张三', 18), ('李四')"));
        assertEquals(SqlReverseParser.KEY_PARSE, ex.getKey());
        assertTrue(ex.getDetail().contains("列数"));
    }

    @Test
    public void INSERT_列值数量不一致_报错() throws Exception {
        assertThrows(() -> parse("INSERT INTO user (name, age) VALUES ('张三')"));
    }

    @Test
    public void UPDATE缺少SET_报错() throws Exception {
        assertThrows(() -> parse("UPDATE user WHERE id = 1"));
    }

    // ------------------------------------------------------------------
    // 子查询 JOIN / FROM 子查询
    // ------------------------------------------------------------------

    @Test
    public void 子查询JOIN_解析为rawJoin() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t LEFT JOIN (SELECT id FROM u WHERE u.type = 1) sub ON sub.id = t.id");
        assertEquals(1, result.joins().size());
        ReverseJoin join = result.joins().get(0);
        assertTrue("tableName 应包含子查询文本", join.tableName().contains("(SELECT"));
        assertNull("子查询 JOIN 的 alias 应为 null", join.alias());
        assertNotNull(join.rawOn());
        assertTrue(join.rawOn().contains("sub.id = t.id"));
    }

    @Test
    public void 子查询JOIN_嵌套括号() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t LEFT JOIN (SELECT * FROM (SELECT id FROM v) x) sub ON sub.id = t.id");
        assertEquals(1, result.joins().size());
        ReverseJoin join = result.joins().get(0);
        assertTrue("应正确解析嵌套括号", join.tableName().contains("(SELECT"));
        assertNull(join.alias());
    }

    @Test
    public void 子查询JOIN_无别名() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t LEFT JOIN (SELECT id FROM u) ON u.id = t.id");
        assertEquals(1, result.joins().size());
        ReverseJoin join = result.joins().get(0);
        assertTrue("tableName 应包含子查询文本", join.tableName().contains("(SELECT"));
        assertNull(join.alias());
    }

    @Test
    public void FROM子查询_解析() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM (SELECT id, name FROM user WHERE status = 1) t LEFT JOIN addr ON addr.user_id = t.id");
        assertTrue("FROM 子查询 tableName 应包含子查询文本", result.tableName().contains("(SELECT"));
        assertEquals(1, result.joins().size());
        assertEquals("addr", result.joins().get(0).tableName());
    }

    @Test
    public void 子查询JOIN_多表链() throws Exception {
        SqlReverseResult result = parse(
                "SELECT t.id, t.station_id, t.share_scheme_id, t.device_name\n" +
                "FROM car_device_info t\n" +
                "LEFT JOIN station_info stationInfoEntity ON stationInfoEntity.id = t.station_id\n" +
                "    AND stationInfoEntity.station_name = '中心站'\n" +
                "LEFT JOIN (\n" +
                "    SELECT tt.id, tt.station_id, tt.station_name, tt.merchant_id\n" +
                "    FROM station_info tt\n" +
                "    WHERE tt.merchant_id >= 0\n" +
                ") tt ON tt.station_id = t.station_id\n" +
                "WHERE t.id = 1");
        assertEquals(2, result.joins().size());
        assertEquals("station_info", result.joins().get(0).tableName());
        assertEquals("stationInfoEntity", result.joins().get(0).alias());
        String secondTableName = result.joins().get(1).tableName();
        assertTrue("第二个 JOIN 应为子查询, 实际: " + secondTableName, secondTableName.contains("(") && secondTableName.contains("SELECT"));
        assertNull(result.joins().get(1).alias());
        assertEquals(1, result.conditions().size());
    }

    @Test
    public void 子查询JOIN_紧跟WHERE() throws Exception {
        SqlReverseResult result = parse("SELECT * FROM t LEFT JOIN (SELECT id FROM u) sub ON sub.id = t.id WHERE t.status = 1");
        assertEquals(1, result.joins().size());
        assertEquals(1, result.conditions().size());
        Condition.Simple cond = (Condition.Simple) result.conditions().get(0);
        assertEquals("status", cond.getColumn().getColumnName());
    }

    // ------------------------------------------------------------------
    // MConsumer 子查询结构解析
    // ------------------------------------------------------------------

    @Test
    public void 子查询JOIN_解析MConsumer结构() throws Exception {
        SqlReverseResult result = parse("SELECT t.id FROM t LEFT JOIN (SELECT tt.id, tt.station_id FROM station_info tt WHERE tt.merchant_id >= 0) tt ON tt.station_id = t.station_id");
        assertEquals(1, result.joins().size());
        ReverseJoin join = result.joins().get(0);
        assertNotNull("子查询结构应被解析", join.subQuery());
        JoinSubQuery subQuery = join.subQuery();
        assertEquals("station_info", subQuery.tableName());
        assertEquals("tt", subQuery.alias());
        assertFalse("显式列 → selectAll=false", subQuery.selectAll());
        assertEquals(List.of("id", "station_id"), subQuery.selectColumns());
        assertEquals(1, subQuery.whereConditions().size());
    }

    @Test
    public void 子查询JOIN_selectAll() throws Exception {
        SqlReverseResult result = parse("SELECT t.id FROM t LEFT JOIN (SELECT * FROM station_info tt WHERE tt.merchant_id >= 0) tt ON tt.station_id = t.station_id");
        ReverseJoin join = result.joins().get(0);
        assertNotNull(join.subQuery());
        assertTrue("SELECT * → selectAll=true", join.subQuery().selectAll());
    }

    @Test
    public void 子查询JOIN_含嵌套子查询_回落null() throws Exception {
        SqlReverseResult result = parse("SELECT t.id FROM t LEFT JOIN (SELECT * FROM (SELECT id FROM u) x) tt ON tt.id = t.id");
        ReverseJoin join = result.joins().get(0);
        assertNull("嵌套子查询应回落 null", join.subQuery());
    }
}
