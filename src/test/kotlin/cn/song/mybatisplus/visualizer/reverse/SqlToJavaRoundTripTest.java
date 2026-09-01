package cn.song.mybatisplus.visualizer.reverse;

import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.Connector;
import cn.song.mybatisplus.visualizer.model.JoinClause;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.OrderByItem;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator;
import cn.song.mybatisplus.visualizer.reverse.ReverseJoin;
import cn.song.mybatisplus.visualizer.reverse.SqlReverseParser;
import cn.song.mybatisplus.visualizer.reverse.SqlReverseResult;
import cn.song.mybatisplus.visualizer.reverse.SelectColumn;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;

import org.junit.Assert.assertEquals;
import org.junit.Assert.assertFalse;
import org.junit.Assert.assertNotNull;
import org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 反向链路 round-trip 闭环测试（P0）：SqlGenerator 规范形 SQL → [SqlReverseParser] 反解
 * → 重组 [SelectStatement] → SqlGenerator 再渲染，两次输出逐字符相等。
 */
public class SqlToJavaRoundTripTest {

    private static final TableInfo TABLE = new TableInfo("User", "user", List.of(), null, null, null, List.of());

    private static final ColumnRef ID = new ColumnRef(null, "id", null, null, null, null, null);

    private static ColumnRef col(String columnName) {
        return new ColumnRef(null, columnName, null, null, null, null, null);
    }

    private static SqlValue.Literal lit(String raw) {
        return new SqlValue.Literal(raw, raw);
    }

    private static SqlValue.Literal lit(String raw, String unquoted) {
        return new SqlValue.Literal(raw, unquoted);
    }

    private static SelectStatement full(
            List<ColumnRef> selectColumns,
            String tableAlias,
            List<JoinClause> joins,
            List<Condition> conditions,
            List<OrderByItem> orderBy,
            LimitClause limit) {
        return new SelectStatement(TABLE, selectColumns, tableAlias, joins, false, conditions,
                List.of(), null, orderBy, limit, null, null, null, List.of(), null, null);
    }

    private static SelectStatement stmt(List<Condition> conditions) {
        return full(List.of(ID), null, List.of(), conditions, List.of(), null);
    }

    private static SelectStatement stmt(List<Condition> conditions, List<OrderByItem> orderBy, LimitClause limit) {
        return full(List.of(ID), null, List.of(), conditions, orderBy, limit);
    }

    private static SelectStatement stmt(List<Condition> conditions, String tableAlias, List<JoinClause> joins) {
        return full(List.of(ID), tableAlias, joins, conditions, List.of(), null);
    }

    private static SelectStatement selectOnly(List<ColumnRef> selectColumns) {
        return full(selectColumns, null, List.of(), List.of(), List.of(), null);
    }

    /** 模型 → SQL → 反解 → 模型 → SQL，断言两次 SQL 相等。 */
    private static String roundTrip(SelectStatement statement) {
        String sql1 = SqlGenerator.INSTANCE.generate(statement);
        SqlReverseResult parsed = SqlReverseParser.INSTANCE.parse(sql1);
        List<ColumnRef> selectColumns;
        if (parsed.selectColumns() == null) {
            selectColumns = List.of(ID);
        } else {
            List<ColumnRef> tmp = new ArrayList<>();
            for (SelectColumn sc : parsed.selectColumns()) {
                tmp.add(new ColumnRef(null, sc.expr(), null, null, null, null, null));
            }
            selectColumns = tmp;
        }
        SelectStatement rebuilt = full(selectColumns, null, List.of(), parsed.conditions(), parsed.orderBy(), parsed.limit());
        String sql2 = SqlGenerator.INSTANCE.generate(rebuilt);
        assertEquals("round-trip SQL 不一致", sql1, sql2);
        return sql1;
    }

    @Test
    public void 无WHERE_空语句闭环() {
        roundTrip(full(List.of(ID), null, List.of(), List.of(), List.of(), null));
    }

    @Test
    public void 比较操作符闭环() {
        roundTrip(stmt(List.of(
                new Condition.Simple(col("a"), "=", lit("1"), null),
                new Condition.Simple(col("b"), "!=", lit("'x'", "x"), null),
                new Condition.Simple(col("c"), ">", lit("2"), null),
                new Condition.Simple(col("d"), ">=", lit("3"), null),
                new Condition.Simple(col("e"), "<", lit("4"), null),
                new Condition.Simple(col("f"), "<=", lit("5"), null))));
    }

    @Test
    public void 一元与集合条件闭环() {
        roundTrip(stmt(List.of(
                new Condition.Unary(col("a"), "IS NULL", null),
                new Condition.Unary(col("b"), "IS NOT NULL", null),
                new Condition.In(col("c"), false, List.of(lit("1"), lit("'s'", "s")), null),
                new Condition.In(col("d"), true, List.of(lit("'p'", "p")), null),
                new Condition.Between(col("e"), false, lit("'2024-01-01'", "2024-01-01"), lit("'2024-01-31'", "2024-01-31"), null),
                new Condition.Between(col("f"), true, lit("1"), lit("2"), null))));
    }

    @Test
    public void LIKE四位置闭环_正反向等价() {
        String sql1 = roundTrip(stmt(List.of(
                new Condition.Like(col("a"), false, lit("'%abc%'", "abc"), LikePosition.ANYWHERE, null),
                new Condition.Like(col("b"), false, lit("'abc%'", "abc"), LikePosition.RIGHT, null),
                new Condition.Like(col("c"), false, lit("'%abc'", "abc"), LikePosition.LEFT, null),
                new Condition.Like(col("d"), true, lit("'%abc%'", "abc"), LikePosition.ANYWHERE, null))));
        assertEquals("SELECT id\nFROM `user`\nWHERE a LIKE '%abc%'\n    AND b LIKE 'abc%'\n    AND c LIKE '%abc'\n    AND d NOT LIKE '%abc%'", sql1);
    }

    @Test
    public void LIKE降级Raw_原样透传闭环() {
        String sql1 = SqlGenerator.INSTANCE.generate(stmt(List.of(new Condition.Raw("a LIKE 'a_b'", null))));
        SqlReverseResult parsed = SqlReverseParser.INSTANCE.parse(sql1);
        assertEquals("a LIKE 'a_b'", ((Condition.Raw) parsed.conditions().get(0)).getSqlText());
        assertEquals(1, parsed.warnings().size());
        String sql2 = SqlGenerator.INSTANCE.generate(stmt(parsed.conditions()));
        assertEquals(sql1, sql2);
    }

    @Test
    public void ORDERBY与LIMIT闭环() {
        String sql1 = roundTrip(stmt(
                List.of(new Condition.Simple(col("a"), "=", lit("1"), null)),
                List.of(new OrderByItem(col("create_time"), false), new OrderByItem(col("id"), true)),
                new LimitClause(lit("10"))));
        assertEquals("SELECT id\nFROM `user`\nWHERE a = 1\nORDER BY create_time DESC, id ASC\nLIMIT 10", sql1);
    }

    @Test
    public void LIMIT偏移形态闭环() {
        roundTrip(stmt(List.of(), List.of(), new LimitClause(lit("5"), lit("10"))));
    }

    @Test
    public void 括号组_逐字符闭环() {
        String sql1 = roundTrip(stmt(List.of(
                new Condition.Group(Connector.OR, List.of(
                        new Condition.Simple(col("a"), "=", lit("1"), null),
                        new Condition.Simple(col("b"), "=", lit("2"), null)), null, false),
                new Condition.Simple(col("c"), "=", lit("'x'", "x"), null))));
        assertEquals("SELECT id\nFROM `user`\nWHERE (a = 1 OR b = 2)\n    AND c = 'x'", sql1);
    }

    @Test
    public void AND组与NOT组_逐字符闭环() {
        String sql1 = roundTrip(stmt(List.of(
                new Condition.Group(Connector.AND, List.of(
                        new Condition.Simple(col("a"), "=", lit("1"), null),
                        new Condition.Simple(col("b"), "=", lit("2"), null)), null, false),
                new Condition.Group(Connector.OR, List.of(
                        new Condition.Simple(col("d"), "=", lit("4"), null),
                        new Condition.Simple(col("e"), "=", lit("5"), null)), null, true))));
        assertEquals("SELECT id\nFROM `user`\nWHERE (a = 1 AND b = 2)\n    AND NOT (d = 4 OR e = 5)", sql1);
    }

    @Test
    public void 顶层OR_语义等价闭环() {
        SqlReverseResult parsed = SqlReverseParser.INSTANCE.parse("SELECT * FROM `user` WHERE a = 1 OR b = 2");
        Condition.Group group = (Condition.Group) parsed.conditions().get(0);
        assertEquals(Connector.OR, group.getConnector());
        String sql = SqlGenerator.INSTANCE.generate(full(List.of(ID), null, List.of(), parsed.conditions(), List.of(), null));
        assertEquals("SELECT id\nFROM `user`\nWHERE (a = 1 OR b = 2)", sql);
        SqlReverseResult reparsed = SqlReverseParser.INSTANCE.parse(sql);
        assertEquals(parsed.conditions(), reparsed.conditions());
    }

    @Test
    public void 降级Raw_函数与子查询透传闭环() {
        for (String sqlText : List.of(
                "SELECT * FROM `user` WHERE DATE_FORMAT(create_time, '%Y') = '2024'",
                "SELECT * FROM `user` WHERE a IN (SELECT id FROM u)")) {
            SqlReverseResult parsed = SqlReverseParser.INSTANCE.parse(sqlText);
            Condition.Raw raw = (Condition.Raw) parsed.conditions().get(0);
            assertEquals(1, parsed.warnings().size());
            String sql2 = SqlGenerator.INSTANCE.generate(stmt(parsed.conditions()));
            assertEquals("SELECT id\nFROM `user`\nWHERE " + raw.getSqlText(), sql2);
        }
    }

    @Test
    public void 参数占位符_逐字符闭环() {
        String sql1 = SqlGenerator.INSTANCE.generate(stmt(List.of(
                new Condition.Simple(col("a"), "=", new SqlValue.Placeholder("?"), null),
                new Condition.In(col("b"), false, List.of(new SqlValue.Placeholder("?"), new SqlValue.Placeholder("?")), null))));
        assertEquals("SELECT id\nFROM `user`\nWHERE a = ?\n    AND b IN (?, ?)", sql1);
        SqlReverseResult reparsed = SqlReverseParser.INSTANCE.parse(sql1);
        Condition.Simple first = (Condition.Simple) reparsed.conditions().get(0);
        assertTrue(first.getValue() instanceof SqlValue.Placeholder);
        Condition.In second = (Condition.In) reparsed.conditions().get(1);
        assertTrue(second.getValues().stream().allMatch(v -> v instanceof SqlValue.Placeholder));
        assertEquals(0, reparsed.warnings().size());
    }

    @Test
    public void LEFTJOIN单等值_逐字符闭环() {
        List<JoinClause> joins = List.of(new JoinClause(JoinType.LEFT, "address", "t1",
                List.of(new Condition.ColumnComparison(
                        new ColumnRef("t1", "user_id", null, null, null, null, null),
                        new ColumnRef("t", "id", null, null, null, null, null), null)),
                null, null, null, null));
        String sql1 = SqlGenerator.INSTANCE.generate(stmt(
                List.of(new Condition.Simple(new ColumnRef("t", "status", null, null, null, null, null), "=", lit("1"), null)),
                "t", joins));
        SqlReverseResult parsed = SqlReverseParser.INSTANCE.parse(sql1);
        assertEquals(1, parsed.joins().size());
        assertEquals("address", parsed.joins().get(0).getTableName());
        SelectStatement rebuilt = full(List.of(ID), "t",
                parsed.joins().stream().map(j -> new JoinClause(j.getType(), j.getTableName(),
                        j.getAlias() == null ? "" : j.getAlias(), j.getOnConditions(), null, null, null, null))
                        .collect(Collectors.toList()),
                parsed.conditions(), List.of(), null);
        assertEquals(sql1, SqlGenerator.INSTANCE.generate(rebuilt));
    }

    @Test
    public void demo_joinExtAndSubTable_子查询JOIN不报错() {
        String sql = "SELECT\n" +
                "    t.id,\n" +
                "    t.station_id,\n" +
                "    t.share_scheme_id,\n" +
                "    t.device_name\n" +
                "FROM car_device_info t\n" +
                "LEFT JOIN station_info stationInfoEntity ON stationInfoEntity.id = t.station_id\n" +
                "    AND stationInfoEntity.station_name = '中心站'\n" +
                "LEFT JOIN (\n" +
                "    SELECT\n" +
                "        tt.id, tt.station_id, tt.station_name, tt.merchant_id\n" +
                "    FROM station_info tt\n" +
                "    WHERE tt.merchant_id >= 0\n" +
                ") tt ON tt.station_id = t.station_id\n" +
                "WHERE t.id = 1";
        SqlReverseResult result = SqlReverseParser.INSTANCE.parse(sql);
        assertEquals(2, result.joins().size());
        String secondJoinName = result.joins().get(1).getTableName();
        assertTrue("第二个 JOIN 应为子查询, 实际: " + secondJoinName, secondJoinName.contains("(") && secondJoinName.contains("SELECT"));
        String code = JavaCodeGenerator.generate(result);
        assertTrue("生成代码应包含 leftJoin", code.contains("leftJoin"));
    }

    @Test
    public void demo_selectJoinSubQuery_子查询JOIN不报错() {
        String sql = "SELECT t.id, t.name, addr.tel\n" +
                "FROM user t\n" +
                "LEFT JOIN (\n" +
                "    SELECT user_id, tel, address FROM user_address WHERE status >= 0\n" +
                ") addr ON addr.user_id = t.id\n" +
                "WHERE t.id = 1";
        SqlReverseResult result = SqlReverseParser.INSTANCE.parse(sql);
        assertEquals(1, result.joins().size());
        String joinName = result.joins().get(0).getTableName();
        assertTrue("JOIN 应为子查询, 实际: " + joinName, joinName.contains("(") && joinName.contains("SELECT"));
        String code = JavaCodeGenerator.generate(result);
        assertTrue("生成代码应包含 leftJoin", code.contains("leftJoin"));
    }

    @Test
    public void demo_joinFromAndPage_FROM子查询不报错() {
        String sql = "SELECT t.id, t.device_name\n" +
                "FROM (\n" +
                "    SELECT id, device_name FROM car_device_info WHERE id >= 0 LIMIT 100\n" +
                ") t\n" +
                "LEFT JOIN station_info ON station_info.id = t.station_id";
        SqlReverseResult result = SqlReverseParser.INSTANCE.parse(sql);
        String fromTable = result.tableName();
        assertTrue("FROM 子查询 tableName 应包含子查询文本, 实际: " + fromTable, fromTable.contains("(") && fromTable.contains("SELECT"));
        assertEquals(1, result.joins().size());
    }

    @Test
    public void demo_selectJoinFrom_FROM子查询不报错() {
        String sql = "SELECT t.id, t.name, addr.tel\n" +
                "FROM (\n" +
                "    SELECT id, name FROM user WHERE id >= 0 LIMIT 10\n" +
                ") t\n" +
                "LEFT JOIN user_address addr ON addr.user_id = t.id\n" +
                "WHERE t.id >= 0";
        SqlReverseResult result = SqlReverseParser.INSTANCE.parse(sql);
        String fromTable = result.tableName();
        assertTrue("FROM 子查询 tableName 应包含子查询文本, 实际: " + fromTable, fromTable.contains("(") && fromTable.contains("SELECT"));
        assertEquals(1, result.joins().size());
        assertEquals("user_address", result.joins().get(0).getTableName());
    }

    @Test
    public void demo_joinExtAndSubTable_ext条件生成4参lambda() {
        String sql = "SELECT\n" +
                "    t.id,\n" +
                "    t.station_id,\n" +
                "    t.share_scheme_id,\n" +
                "    t.device_name\n" +
                "FROM car_device_info t\n" +
                "LEFT JOIN station_info stationInfoEntity ON stationInfoEntity.id = t.station_id\n" +
                "    AND stationInfoEntity.station_name = '中心站'\n" +
                "LEFT JOIN (\n" +
                "    SELECT\n" +
                "        tt.id, tt.station_id, tt.station_name, tt.merchant_id\n" +
                "    FROM station_info tt\n" +
                "    WHERE tt.merchant_id >= 0\n" +
                ") tt ON tt.station_id = t.station_id\n" +
                "WHERE t.id = 1";
        SqlReverseResult result = SqlReverseParser.INSTANCE.parse(sql);
        assertEquals(2, result.joins().size());

        JavaCodeGenerator.EntityTarget stationSub = new JavaCodeGenerator.EntityTarget("StationInfoEntity",
                Map.of("id", "id", "station_id", "stationId", "station_name", "stationName", "merchant_id", "merchantId"));
        JavaCodeGenerator.EntityTarget entity = new JavaCodeGenerator.EntityTarget("DeviceInfoEntity",
                Map.of("id", "id", "station_id", "stationId", "share_scheme_id", "shareSchemeId", "device_name", "deviceName"),
                Map.of("stationInfoEntity", stationSub));
        String code = JavaCodeGenerator.generate(result, entity);
        assertTrue("第一个 JOIN 应生成 4 参 leftJoin", code.contains("leftJoin(StationInfoEntity.class,"));
        assertTrue("应包含 ext lambda", code.contains("ext -> ext.eq(StationInfoEntity::getStationName,"));
        assertTrue("第二个 JOIN 应生成 MConsumer 形态", code.contains("t -> t.setAlias(\"tt\")"));
        assertTrue("应包含 ge 条件", code.contains(".ge(StationInfoEntity::getMerchantId,"));
    }

    @Test
    public void demo_selectJoinWithOnCondition_ext条件生成4参lambda() {
        String sql = "SELECT t.id, t.name, t1.tel, t1.address\n" +
                "FROM user t\n" +
                "LEFT JOIN user_address t1 ON t1.user_id = t.id AND t1.status = 1\n" +
                "LEFT JOIN area t2 ON t2.id = t1.area_id AND t2.city = '杭州'\n" +
                "WHERE t.sex = 1";
        SqlReverseResult result = SqlReverseParser.INSTANCE.parse(sql);
        assertEquals(2, result.joins().size());

        JavaCodeGenerator.EntityTarget addressSub = new JavaCodeGenerator.EntityTarget("AddressDO",
                Map.of("user_id", "userId", "tel", "tel", "address", "address", "status", "status", "area_id", "areaId"));
        JavaCodeGenerator.EntityTarget areaSub = new JavaCodeGenerator.EntityTarget("AreaDO",
                Map.of("id", "id", "city", "city"));
        JavaCodeGenerator.EntityTarget entity = new JavaCodeGenerator.EntityTarget("UserDO",
                Map.of("id", "id", "name", "name", "sex", "sex"),
                Map.of("t1", addressSub, "t2", areaSub));
        String code = JavaCodeGenerator.generate(result, entity);
        assertTrue("第一个 JOIN 应生成 4 参 leftJoin", code.contains("leftJoin(AddressDO.class,"));
        assertTrue("第一个 JOIN 应包含 status ext", code.contains("ext.eq(AddressDO::getStatus,"));
        assertTrue("第二个 JOIN 应为字符串形态", code.contains("(SELECT") || code.contains("leftJoin(\"area t2 ON"));
    }

    @Test
    public void demo_joinExtAndSubTable_MConsumer子查询形态() {
        String sql = "SELECT\n" +
                "    t.id,\n" +
                "    t.station_id,\n" +
                "    t.share_scheme_id,\n" +
                "    t.device_name\n" +
                "FROM car_device_info t\n" +
                "LEFT JOIN station_info stationInfoEntity ON stationInfoEntity.id = t.station_id\n" +
                "    AND stationInfoEntity.station_name = '中心站'\n" +
                "LEFT JOIN (\n" +
                "    SELECT\n" +
                "        tt.id, tt.station_id, tt.station_name, tt.merchant_id\n" +
                "    FROM station_info tt\n" +
                "    WHERE tt.merchant_id >= 0\n" +
                ") tt ON tt.station_id = t.station_id\n" +
                "WHERE t.id = 1";
        SqlReverseResult result = SqlReverseParser.INSTANCE.parse(sql);
        assertEquals(2, result.joins().size());
        assertNotNull("第二个 JOIN 应有 subQuery", result.joins().get(1).getSubQuery());
        JoinSubQuery subQuery = result.joins().get(1).getSubQuery();
        assertEquals("station_info", subQuery.getTableName());
        assertEquals("tt", subQuery.getAlias());
        assertFalse(subQuery.selectAll());
        assertEquals(1, subQuery.getWhereConditions().size());

        JavaCodeGenerator.EntityTarget stationSub = new JavaCodeGenerator.EntityTarget("StationInfoEntity",
                Map.of("id", "id", "station_id", "stationId", "station_name", "stationName", "merchant_id", "merchantId"));
        JavaCodeGenerator.EntityTarget entity = new JavaCodeGenerator.EntityTarget("DeviceInfoEntity",
                Map.of("id", "id", "station_id", "stationId", "share_scheme_id", "shareSchemeId", "device_name", "deviceName"),
                Map.of("stationInfoEntity", stationSub, "tt", stationSub));
        String code = JavaCodeGenerator.generate(result, entity);
        assertTrue("第二个 JOIN 应生成 MConsumer 形态", code.contains("t -> t.setAlias(\"tt\")"));
        assertTrue("应包含 select（显式列）", code.contains(".select("));
        assertTrue("应包含 ge 条件", code.contains(".ge(StationInfoEntity::getMerchantId,"));
        assertTrue("第一个 JOIN 应生成 ext lambda", code.contains("ext -> ext.eq(StationInfoEntity::getStationName,"));
    }

    @Test
    public void demo_selectJoinSubQuery_MConsumer形态() {
        String sql = "SELECT t.id, t.name, addr.tel\n" +
                "FROM user t\n" +
                "LEFT JOIN (\n" +
                "    SELECT user_id, tel, address FROM user_address WHERE status >= 0\n" +
                ") addr ON addr.user_id = t.id\n" +
                "WHERE t.id = 1";
        SqlReverseResult result = SqlReverseParser.INSTANCE.parse(sql);
        assertEquals(1, result.joins().size());
        assertNotNull("应有 subQuery", result.joins().get(0).getSubQuery());

        JavaCodeGenerator.EntityTarget addressSub = new JavaCodeGenerator.EntityTarget("AddressDO",
                Map.of("user_id", "userId", "tel", "tel", "address", "address", "status", "status"));
        JavaCodeGenerator.EntityTarget entity = new JavaCodeGenerator.EntityTarget("UserDO",
                Map.of("id", "id", "name", "name"),
                Map.of("addr", addressSub));
        String code = JavaCodeGenerator.generate(result, entity);
        assertTrue("应生成 MConsumer 形态", code.contains("t -> t.setAlias(\"addr\")"));
        assertTrue("应包含 select 列", code.contains(".select("));
        assertTrue("应包含 ge 条件", code.contains(".ge(AddressDO::getStatus,"));
    }
}
