package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.ColumnRole;
import cn.song.mybatisplus.visualizer.model.FillClause;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperArg;
import cn.song.mybatisplus.visualizer.model.WrapperCall;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.sql.MySqlDialect;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * T-MpjFill 转换器单元测试：MPJ fill 关联填充（MPJ 1.5.1+，纯逻辑无 PSI）。
 */
public class MpjFillConverterTest {

    private final TableInfo table = new TableInfo(
        "DeviceEntity",
        "device",
        List.of(
            new ColumnInfo("id", "id", true, false, null, null, null, ColumnRole.BUSINESS),
            new ColumnInfo("name", "name", false, false, null, null, null, ColumnRole.BUSINESS),
            new ColumnInfo("stationId", "station_id", false, false, null, null, null, ColumnRole.BUSINESS)
        ),
        null, null, null, List.of()
    );

    private static final TableInfo stationTable = new TableInfo(
        "StationEntity",
        "station",
        List.of(
            new ColumnInfo("id", "id", true, false, null, null, null, ColumnRole.BUSINESS),
            new ColumnInfo("stationName", "station_name", false, false, null, null, null, ColumnRole.BUSINESS)
        ),
        null, null, null, List.of()
    );

    private static ColumnRef crefAlias(String tableAlias, String column) {
        return new ColumnRef(tableAlias, column, null, null, null, null, null);
    }

    private static WrapperArg.ColumnArg col(String column) {
        return colA(null, column);
    }

    private static WrapperArg.ColumnArg colA(String tableAlias, String column) {
        return new WrapperArg.ColumnArg(crefAlias(tableAlias, column));
    }

    private static WrapperArg.ValueArg val(String value) {
        return new WrapperArg.ValueArg(new SqlValue.Literal(value, value));
    }

    private static WrapperArg.ValueArg valPh(String value) {
        return new WrapperArg.ValueArg(new SqlValue.Placeholder(value));
    }

    private static WrapperArg.EntityRefArg entityRef(TableInfo t) {
        return new WrapperArg.EntityRefArg(t);
    }

    private static WrapperCall call(String method, List<WrapperArg> args) {
        return new WrapperCall(method, args);
    }

    private static WrapperChain chain(WrapperType type, String entityClass, List<WrapperCall> calls) {
        return new WrapperChain(type, entityClass, calls, null, false, false, false, false, null);
    }

    /** 构造带显式别名 "s" 的 leftJoin 调用（四参别名形态）。 */
    private static WrapperCall leftJoinWithAliasS() {
        return call("leftJoin", List.of(
            entityRef(stationTable),
            val("s"),
            col("id"),
            col("station_id")
        ));
    }

    // ------------------------------------------------------------------
    // 形态 1（2 参）：fill(field, tagField)
    // ------------------------------------------------------------------

    @Test
    public void fill_基础形态2参() throws Exception {
        // fill(Device::getStationId, Station::getId) + leftJoin(StationEntity, "s", ...)
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceEntity", List.of(
            leftJoinWithAliasS(),
            call("fill", List.of(
                colA("t", "station_id"),
                colA("s", "id")
            ))
        ));
        SelectStatement statement = (SelectStatement) MpjLambdaConverter.INSTANCE.convert(chain, table);
        List<FillClause> fills = statement.getFillClauses();
        assertNotNull(fills);
        assertEquals(1, fills.size());

        FillClause fill = fills.get(0);
        assertEquals("station", fill.getChildTable().getTableName());
        assertEquals("s", fill.getChildAlias());
        assertEquals("id", fill.getTagField());
        assertNull(fill.getLimit());
        assertNull(fill.getCondition());
        assertNull(fill.getData());

        String fillSql = SqlGenerator.INSTANCE.generateFill(fill, MySqlDialect.INSTANCE);
        assertEquals("SELECT s.id, s.station_name FROM station s WHERE s.id IN (?)", fillSql);
    }

    // ------------------------------------------------------------------
    // 形态 3（4 参 limit）：fill(field, tagField, mapperTagField, limit)
    // ------------------------------------------------------------------

    @Test
    public void fill_limit限制_4参() throws Exception {
        // fill(Device::getStationId, Station::getId, "id", 10)
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceEntity", List.of(
            leftJoinWithAliasS(),
            call("fill", List.of(
                colA("t", "station_id"),
                colA("s", "id"),
                val("id"),
                val("10")
            ))
        ));
        SelectStatement statement = (SelectStatement) MpjLambdaConverter.INSTANCE.convert(chain, table);
        List<FillClause> fills = statement.getFillClauses();
        assertNotNull(fills);
        assertEquals(1, fills.size());

        FillClause fill = fills.get(0);
        assertEquals(Integer.valueOf(10), fill.getLimit());
        assertNull(fill.getCondition());
        assertNull(fill.getData());

        String fillSql = SqlGenerator.INSTANCE.generateFill(fill, MySqlDialect.INSTANCE);
        assertEquals("SELECT s.id, s.station_name FROM station s WHERE s.id IN (?) LIMIT 10", fillSql);
    }

    // ------------------------------------------------------------------
    // 形态 4（4 参 condition=false）：跳过 fill
    // ------------------------------------------------------------------

    @Test
    public void fill_condition_false跳过() throws Exception {
        // fill(Device::getStationId, Station::getId, "id", false) → 不生成 FillClause
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceEntity", List.of(
            leftJoinWithAliasS(),
            call("fill", List.of(
                colA("t", "station_id"),
                colA("s", "id"),
                val("id"),
                val("false")
            ))
        ));
        SelectStatement statement = (SelectStatement) MpjLambdaConverter.INSTANCE.convert(chain, table);
        assertNull(statement.getFillClauses());
    }

    // ------------------------------------------------------------------
    // 形态 4（4 参 condition=true）：执行 fill
    // ------------------------------------------------------------------

    @Test
    public void fill_condition_true执行() throws Exception {
        // fill(Device::getStationId, Station::getId, "id", true) → 生成 FillClause
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceEntity", List.of(
            leftJoinWithAliasS(),
            call("fill", List.of(
                colA("t", "station_id"),
                colA("s", "id"),
                val("id"),
                val("true")
            ))
        ));
        SelectStatement statement = (SelectStatement) MpjLambdaConverter.INSTANCE.convert(chain, table);
        List<FillClause> fills = statement.getFillClauses();
        assertNotNull(fills);
        assertEquals(1, fills.size());
        assertEquals(true, fills.get(0).getCondition());
    }

    // ------------------------------------------------------------------
    // 形态 5（4 参 data）：数据源对象
    // ------------------------------------------------------------------

    @Test
    public void fill_data数据源() throws Exception {
        // fill(Device::getStationId, Station::getId, "id", dataObj) → data 占位
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceEntity", List.of(
            leftJoinWithAliasS(),
            call("fill", List.of(
                colA("t", "station_id"),
                colA("s", "id"),
                val("id"),
                valPh("dataObj")
            ))
        ));
        SelectStatement statement = (SelectStatement) MpjLambdaConverter.INSTANCE.convert(chain, table);
        List<FillClause> fills = statement.getFillClauses();
        assertNotNull(fills);
        assertEquals(1, fills.size());
        assertNotNull(fills.get(0).getData());
        assertEquals("dataObj", ((SqlValue.Placeholder) fills.get(0).getData()).getSource());
    }

    // ------------------------------------------------------------------
    // 形态 6（5 参 limit+data）：fill(field, tagField, mapperTagField, limit, data)
    // ------------------------------------------------------------------

    @Test
    public void fill_limit_data_5参() throws Exception {
        // fill(Device::getStationId, Station::getId, "id", 5, dataObj)
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceEntity", List.of(
            leftJoinWithAliasS(),
            call("fill", List.of(
                colA("t", "station_id"),
                colA("s", "id"),
                val("id"),
                val("5"),
                valPh("dataObj")
            ))
        ));
        SelectStatement statement = (SelectStatement) MpjLambdaConverter.INSTANCE.convert(chain, table);
        List<FillClause> fills = statement.getFillClauses();
        assertNotNull(fills);
        assertEquals(1, fills.size());

        FillClause fill = fills.get(0);
        assertEquals(Integer.valueOf(5), fill.getLimit());
        assertNotNull(fill.getData());

        String fillSql = SqlGenerator.INSTANCE.generateFill(fill, MySqlDialect.INSTANCE);
        assertEquals("SELECT s.id, s.station_name FROM station s WHERE s.id IN (?) LIMIT 5", fillSql);
    }

    // ------------------------------------------------------------------
    // 多次 fill：按 chain 顺序保持
    // ------------------------------------------------------------------

    @Test
    public void fill_多次fill_按顺序追加() throws Exception {
        // 两次 fill 调用 → 两个 FillClause 按 chain 顺序
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceEntity", List.of(
            leftJoinWithAliasS(),
            call("fill", List.of(
                colA("t", "station_id"),
                colA("s", "id")
            )),
            call("fill", List.of(
                colA("t", "station_id"),
                colA("s", "station_name"),
                val("station_name"),
                val("3")
            ))
        ));
        SelectStatement statement = (SelectStatement) MpjLambdaConverter.INSTANCE.convert(chain, table);
        List<FillClause> fills = statement.getFillClauses();
        assertNotNull(fills);
        assertEquals(2, fills.size());

        assertEquals("id", fills.get(0).getTagField());
        assertNull(fills.get(0).getLimit());

        assertEquals("station_name", fills.get(1).getTagField());
        assertEquals(Integer.valueOf(3), fills.get(1).getLimit());
    }

    // ------------------------------------------------------------------
    // 主查询 + fill：主 SELECT 独立、fill 段追加
    // ------------------------------------------------------------------

    @Test
    public void fill_结合主查询join() throws Exception {
        // 主查询有 leftJoin + fill → 主 SELECT 独立（已有 join），fill 段追加
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceEntity", List.of(
            leftJoinWithAliasS(),
            call("fill", List.of(
                colA("t", "station_id"),
                colA("s", "id")
            ))
        ));
        SelectStatement statement = (SelectStatement) MpjLambdaConverter.INSTANCE.convert(chain, table);

        assertTrue("主查询应包含 join", !statement.getJoins().isEmpty());

        List<FillClause> fills = statement.getFillClauses();
        assertNotNull(fills);
        assertEquals(1, fills.size());

        String mainSql = SqlGenerator.INSTANCE.generate(statement);
        assertTrue("主 SQL 应包含 JOIN", mainSql.contains("LEFT JOIN"));
        assertTrue("主 SQL 应包含主表列", mainSql.contains("FROM device"));

        String fillSql = SqlGenerator.INSTANCE.generateFill(fills.get(0), MySqlDialect.INSTANCE);
        assertEquals("SELECT s.id, s.station_name FROM station s WHERE s.id IN (?)", fillSql);
    }

    // ------------------------------------------------------------------
    // 容错：无 join 上下文（无法推断关联表）→ 返回 null 不崩溃
    // ------------------------------------------------------------------

    @Test
    public void fill_无join上下文_容错返回null() throws Exception {
        // 无 leftJoin → aliasToTable 为空 → parseFill 返回 null → fillClauses 为 null
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceEntity", List.of(
            call("fill", List.of(
                colA("t", "station_id"),
                colA("s", "id")
            ))
        ));
        SelectStatement statement = (SelectStatement) MpjLambdaConverter.INSTANCE.convert(chain, table);
        assertNull("无 join 上下文时 fillClauses 应为 null", statement.getFillClauses());
    }

    // ------------------------------------------------------------------
    // 容错：参数不足（< 2 参）→ 返回 null
    // ------------------------------------------------------------------

    @Test
    public void fill_参数不足_容错返回null() throws Exception {
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceEntity", List.of(
            call("fill", List.of(
                colA("t", "station_id")
            ))
        ));
        SelectStatement statement = (SelectStatement) MpjLambdaConverter.INSTANCE.convert(chain, table);
        assertNull("参数不足时 fillClauses 应为 null", statement.getFillClauses());
    }

    // ------------------------------------------------------------------
    // 容错：第 2 参为字符串（无实体上下文）→ 返回 null
    // ------------------------------------------------------------------

    @Test
    public void fill_字符串tagField_无上下文容错() throws Exception {
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceEntity", List.of(
            call("fill", List.of(
                colA("t", "station_id"),
                val("id")
            ))
        ));
        SelectStatement statement = (SelectStatement) MpjLambdaConverter.INSTANCE.convert(chain, table);
        assertNull("字符串 tagField 无上下文时 fillClauses 应为 null", statement.getFillClauses());
    }

    // ------------------------------------------------------------------
    // 容错：condition=false 在多次 fill 中仅跳过该次
    // ------------------------------------------------------------------

    @Test
    public void fill_condition_false仅跳过当次() throws Exception {
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceEntity", List.of(
            leftJoinWithAliasS(),
            call("fill", List.of(
                colA("t", "station_id"),
                colA("s", "id"),
                val("id"),
                val("false")
            )),
            call("fill", List.of(
                colA("t", "station_id"),
                colA("s", "id")
            ))
        ));
        SelectStatement statement = (SelectStatement) MpjLambdaConverter.INSTANCE.convert(chain, table);
        List<FillClause> fills = statement.getFillClauses();
        assertNotNull(fills);
        // 第一次 fill 被跳过（condition=false），第二次保留
        assertEquals(1, fills.size());
        assertEquals("id", fills.get(0).getTagField());
    }
}
