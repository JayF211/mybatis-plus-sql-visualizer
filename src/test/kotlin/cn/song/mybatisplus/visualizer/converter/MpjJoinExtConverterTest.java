package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.ColumnRole;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperArg;
import cn.song.mybatisplus.visualizer.model.WrapperCall;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * M21 转换器单元测试：MPJ 联表扩展（纯逻辑，无 PSI）。
 */
public class MpjJoinExtConverterTest {

    private final TableInfo table = new TableInfo(
        "DeviceInfoEntity",
        "car_device_info",
        List.of(
            new ColumnInfo("id", "id", true, false, null, null, null, ColumnRole.BUSINESS),
            new ColumnInfo("stationId", "station_id", false, false, null, null, null, ColumnRole.BUSINESS),
            new ColumnInfo("shareSchemeId", "share_scheme_id", false, false, null, null, null, ColumnRole.BUSINESS)
        ),
        null, null, null, List.of()
    );

    /** 关联表：station_info（psi 层 EntityRefArg 形态，带真实 TableInfo）。 */
    private final TableInfo stationTable = new TableInfo(
        "StationInfoEntity",
        "station_info",
        List.of(
            new ColumnInfo("id", "id", true, false, null, null, null, ColumnRole.BUSINESS),
            new ColumnInfo("stationId", "station_id", false, false, null, null, null, ColumnRole.BUSINESS),
            new ColumnInfo("stationName", "station_name", false, false, null, null, null, ColumnRole.BUSINESS),
            new ColumnInfo("merchantId", "merchant_id", false, false, null, null, null, ColumnRole.BUSINESS)
        ),
        null, null, null, List.of()
    );

    /** T-1800 测试 fixture：AddressDO 实体（@TableName("user_address")）。 */
    private final TableInfo addressTable = new TableInfo(
        "AddressDO",
        "user_address",
        List.of(
            new ColumnInfo("id", "id", true, false, null, null, null, ColumnRole.BUSINESS),
            new ColumnInfo("userId", "user_id", false, false, null, null, null, ColumnRole.BUSINESS),
            new ColumnInfo("tel", "tel", false, false, null, null, null, ColumnRole.BUSINESS),
            new ColumnInfo("address", "address", false, false, null, null, null, ColumnRole.BUSINESS),
            new ColumnInfo("areaId", "area_id", false, false, null, null, null, ColumnRole.BUSINESS),
            new ColumnInfo("status", "status", false, false, null, null, null, ColumnRole.BUSINESS)
        ),
        null, null, null, List.of()
    );

    private static ColumnRef cref(String column) {
        return new ColumnRef(null, column, null, null, null, null, null);
    }

    private static ColumnRef crefAlias(String tableAlias, String column) {
        return new ColumnRef(tableAlias, column, null, null, null, null, null);
    }

    private static WrapperArg.ColumnArg col(String column) {
        return new WrapperArg.ColumnArg(cref(column));
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

    private static WrapperArg.AliasArg aliasArg(String prop) {
        return new WrapperArg.AliasArg(prop, null);
    }

    private static WrapperArg.EntityRefArg entityRef(TableInfo t) {
        return new WrapperArg.EntityRefArg(t);
    }

    private static WrapperArg.NestedArg nestedArg(WrapperChain c) {
        return new WrapperArg.NestedArg(c);
    }

    private static WrapperCall call(String method, List<WrapperArg> args) {
        return new WrapperCall(method, args);
    }

    private static WrapperCall eqCall(String column, String value) {
        return new WrapperCall("eq", List.of(col(column), val(value)));
    }

    private static WrapperChain chain(WrapperType type, String entityClass, List<WrapperCall> calls) {
        return new WrapperChain(type, entityClass, calls, null, false, false, false, false, null);
    }

    private static WrapperChain nested(List<WrapperCall> calls) {
        return new WrapperChain(WrapperType.NESTED, null, calls, null, false, false, false, false, null);
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    // ------------------------------------------------------------------
    // T-M21-02：join 5 参 rightAlias / 4 参 rightAlias / ext MFunction / 子表 MConsumer
    // ------------------------------------------------------------------

    @Test
    public void mpjJoin_5参rightAlias右列别名() {
        // T-M21-02：leftJoin(Class, "st", 左列, "t", 右列) → ON st.左列 = t.右列
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("leftJoin", List.of(
                entityRef(stationTable),
                val("st"),
                col("station_id"),
                val("t"),
                col("id")
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN station_info st ON st.station_id = t.id
            """.stripIndent(),
            sql
        );
    }

    @Test
    public void mpjJoin_4参rightAlias右列别名() {
        // T-M21-02：leftJoin(Class, 左列, "t", 右列) → ON 关联别名.左列 = t.右列
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("leftJoin", List.of(
                entityRef(stationTable),
                col("station_id"),
                val("t"),
                col("id")
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN station_info stationInfoEntity ON stationInfoEntity.station_id = t.id
            """.stripIndent(),
            sql
        );
    }

    @Test
    public void mpjJoin_ext函数追加ON条件() {
        // T-M21-02：leftJoin(Class, 左列, 右列, ext -> ext.eq(...)) → ON 关联别名.左列 = 主别名.右列 + ext 内条件追加
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("leftJoin", List.of(
                entityRef(stationTable),
                col("id"),
                col("station_id"),
                nestedArg(nested(List.of(
                    call("eq", List.of(col("station_name"), val("1")))
                )))
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN station_info stationInfoEntity ON stationInfoEntity.id = t.station_id AND stationInfoEntity.station_name = 1
            """.stripIndent(),
            sql
        );
    }

    @Test
    public void mpjJoin_子表MConsumer子查询join() {
        // T-M21-02：leftJoin(Class, t -> t.setAlias("tt").selectAll().ge(...), 左列, 右列)
        TableInfo childTable = new TableInfo(
            "OccupyRecordEntity",
            "occupy_record",
            List.of(
                new ColumnInfo("id", "id", true, false, null, null, null, ColumnRole.BUSINESS),
                new ColumnInfo("deviceId", "device_id", false, false, null, null, null, ColumnRole.BUSINESS)
            ),
            null, null, null, List.of()
        );
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("leftJoin", List.of(
                entityRef(childTable),
                nestedArg(nested(List.of(
                    call("setAlias", List.of(val("tt"))),
                    call("selectAll", List.of()),
                    call("ge", List.of(col("device_id"), val("1")))
                ))),
                col("device_id"),
                col("id")
            ))
        ));
        SelectStatement statement = (SelectStatement) MpjLambdaConverter.INSTANCE.convert(chain, table);
        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN (SELECT tt.id, tt.device_id FROM occupy_record tt WHERE tt.device_id >= 1) tt ON tt.device_id = t.id
            """.stripIndent(),
            sql
        );
        // 子查询形态不携带逻辑删除列
        assertNull("子查询 join 不注入副表 deleted", statement.getJoins().get(0).getLogicDeleteColumn());

        // 无 setAlias：子别名 = 类名首字母小写
        WrapperChain noAliasChain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("leftJoin", List.of(
                entityRef(childTable),
                nestedArg(nested(List.of(
                    call("selectAll", List.of()),
                    call("ge", List.of(col("device_id"), val("1")))
                ))),
                col("device_id"),
                col("id")
            ))
        ));
        String noAliasSql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(noAliasChain, table));
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN (SELECT occupyRecordEntity.id, occupyRecordEntity.device_id FROM occupy_record occupyRecordEntity WHERE occupyRecordEntity.device_id >= 1) occupyRecordEntity ON occupyRecordEntity.device_id = t.id
            """.stripIndent(),
            noAliasSql
        );
    }

    // ------------------------------------------------------------------
    // T-M21-03：selectCollection prefix / free 形态
    // ------------------------------------------------------------------

    @Test
    public void mpjJoin_selectCollectionPrefix子查询别名() {
        // T-M21-03：selectCollection("consumption", Child.class, VO::getList, map) → 子查询表别名用 prefix
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("selectCollection", List.of(
                val("consumption"),
                val("OccupyConsumptionRecordEntity"),
                aliasArg("consumptions"),
                nestedArg(nested(List.of(
                    call("id", List.of(col("id"))),
                    call("result", List.of(col("ladder"))),
                    call("result", List.of(col("fee")))
                )))
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN (SELECT id, ladder, fee FROM occupy_consumption_record_entity consumption) consumption
            """.stripIndent(),
            sql
        );
    }

    @Test
    public void mpjJoin_selectCollectionFree不产生子查询() {
        // T-M21-03：selectCollection(VO::getList, map)（首参方法引用、无子表类）→ 结果映射标记不产生 SQL
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("selectAll", List.of(val("DeviceInfoEntity"))),
            call("selectCollection", List.of(
                aliasArg("consumptions"),
                nestedArg(nested(List.of(
                    call("id", List.of(col("id"))),
                    call("result", List.of(col("ladder")))
                )))
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertFalse("free 形态不产生 LEFT JOIN 子查询", sql.contains("LEFT JOIN"));
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            """.stripIndent(),
            sql
        );
    }

    @Test
    public void mpjJoin_selectCollection类名形态回归() {
        // T-M21-03 回归：既有 selectCollection(Child.class, VO::getList[, map]) 形态不变
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("selectCollection", List.of(
                val("OccupyConsumptionRecordEntity"),
                aliasArg("consumptions"),
                nestedArg(nested(List.of(
                    call("id", List.of(col("id"))),
                    call("result", List.of(col("ladder"))),
                    call("result", List.of(col("fee")))
                )))
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN (SELECT id, ladder, fee FROM occupy_consumption_record_entity occupyConsumptionRecordEntity) occupyConsumptionRecordEntity
            """.stripIndent(),
            sql
        );
    }

    // ------------------------------------------------------------------
    // T-1800：selectCollection 三缺陷修复（EntityRefArg 携带 @TableName）
    // ------------------------------------------------------------------

    @Test
    public void mpjJoin_selectCollectionEntityRefArg子查询表名正确() {
        // T-1800（Bug1）：EntityRefArg 携带 @TableName("user_address") 时子查询表名应为 user_address
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "UserDO", List.of(
            call("selectCollection", List.of(
                val("user_address"),
                entityRef(addressTable),
                aliasArg("addressList"),
                nestedArg(nested(List.of(
                    call("id", List.of(col("id"))),
                    call("result", List.of(col("address")))
                )))
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertTrue("子查询表名应为用户真实表名 user_address", sql.contains("FROM user_address user_address"));
        assertFalse("不应出现 snake_case 推断名 address_d_o", sql.contains("address_d_o"));
    }

    @Test
    public void mpjJoin_selectCollectionPrefix去重普通join() {
        // T-1800（Bug2）：selectCollection + 同子表 leftJoin 不应重复渲染
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "UserDO", List.of(
            call("selectCollection", List.of(
                val("user_address"),
                entityRef(addressTable),
                aliasArg("addressList"),
                nestedArg(nested(List.of(
                    call("id", List.of(col("id"))),
                    call("result", List.of(col("address")))
                )))
            )),
            call("leftJoin", List.of(
                entityRef(addressTable),
                colA("addressDO", "user_id"),
                colA("t", "id")
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        int leftJoinCount = countOccurrences(sql, "LEFT JOIN");
        assertEquals("LEFT JOIN 应只出现一次（子查询）", 1, leftJoinCount);
        assertTrue("子查询表名应为用户真实表名", sql.contains("FROM user_address user_address"));
    }

    @Test
    public void mpjJoin_selectCollectionPrefixOn条件别名一致() {
        // T-1800（Bug3）：ON 条件别名应使用 collection 别名（prefix），而非 leftJoin 默认别名
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "UserDO", List.of(
            call("selectCollection", List.of(
                val("user_address"),
                entityRef(addressTable),
                aliasArg("addressList"),
                nestedArg(nested(List.of(
                    call("id", List.of(col("id"))),
                    call("result", List.of(col("address")))
                )))
            )),
            call("leftJoin", List.of(
                entityRef(addressTable),
                colA("addressDO", "user_id"),
                colA("t", "id")
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertTrue(
            "ON 条件应使用 collection 别名 user_address.user_id，实际 SQL:\n" + sql,
            sql.contains("ON t.id = user_address.user_id") || sql.contains("ON user_address.user_id = t.id")
        );
        assertFalse(
            "ON 条件不应使用 leftJoin 默认别名 addressDO，实际 SQL:\n" + sql,
            sql.contains("addressDO.user_id")
        );
    }

    @Test
    public void mpjJoin_selectCollection普通形态EntityRefArg() {
        // T-1800（Bug4）：普通形态（非 prefix）EntityRefArg 兼容 + 表名正确
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "UserDO", List.of(
            call("selectCollection", List.of(
                entityRef(addressTable),
                aliasArg("addressList"),
                nestedArg(nested(List.of(
                    call("id", List.of(col("id"))),
                    call("result", List.of(col("address")))
                )))
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertTrue("子查询表名应为用户真实表名", sql.contains("FROM user_address addressDO"));
        assertFalse("不应出现 snake_case 推断名 address_d_o", sql.contains("address_d_o"));
    }

    @Test
    public void mpjJoin_selectAssociationEntityRefArg不产生重复join() {
        // T-1800（Bug5）：selectAssociation EntityRefArg 形态不产生重复 join
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "UserDO", List.of(
            call("selectAssociation", List.of(
                entityRef(addressTable),
                aliasArg("defaultAddress")
            )),
            call("leftJoin", List.of(
                entityRef(addressTable),
                colA("addressDO", "user_id"),
                colA("t", "id")
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertFalse("selectAssociation 不应产生 LEFT JOIN 子查询", sql.contains("LEFT JOIN (SELECT"));
        assertTrue("leftJoin 应正常渲染", sql.contains("LEFT JOIN user_address addressDO"));
    }

    // ------------------------------------------------------------------
    // T-M21-07：from(子查询主表)
    // ------------------------------------------------------------------

    @Test
    public void mpjJoin_from子查询主表() {
        // T-M21-07：from(w -> w.setAlias("tb").selectAll().ge(User::getId, 0).last("LIMIT 10"))
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("from", List.of(nestedArg(nested(List.of(
                call("setAlias", List.of(val("tb"))),
                call("selectAll", List.of()),
                call("ge", List.of(col("id"), val("0"))),
                call("last", List.of(val("LIMIT 10")))
            )))),
            call("eq", List.of(col("id"), val("1001")))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM (SELECT tb.id, tb.station_id, tb.share_scheme_id FROM car_device_info tb WHERE tb.id >= 0 LIMIT 10) t
            WHERE t.id = 1001
            """.stripIndent(),
            sql
        );
    }

    // ------------------------------------------------------------------
    // T-M21-09（部分）：around / setTableName / pageByMain
    // ------------------------------------------------------------------

    @Test
    public void mpjJoin_around位置与参数替换() {
        // T-M21-09：around 渲染于 ORDER BY 之后、LIMIT 之前；模板 {i} 绑定参数替换
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("orderByAsc", List.of(col("id"))),
            call("around", List.of(val("AND t.id > {0}"), val("5"))),
            call("last", List.of(val("LIMIT 10")))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            ORDER BY t.id ASC
            AND t.id > 5
            LIMIT 10
            """.stripIndent(),
            sql
        );
        int orderIdx = sql.indexOf("ORDER BY");
        int aroundIdx = sql.indexOf("AND t.id > 5");
        int limitIdx = sql.indexOf("LIMIT 10");
        assertTrue("around 位于 ORDER BY 之后", aroundIdx > orderIdx);
        assertTrue("around 位于 LIMIT 之前", aroundIdx < limitIdx);
    }

    @Test
    public void mpjJoin_around双字符串形态() {
        // T-M21-09：around(firstSql, lastSql)
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("around", List.of(val("/* around hint */"), val("FOR UPDATE"))),
            call("eq", List.of(col("id"), val("1")))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            /* around hint */ SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            WHERE t.id = 1
            FOR UPDATE
            """.stripIndent(),
            sql
        );
    }

    @Test
    public void mpjJoin_setTableName与pageByMain不破坏() {
        // T-M21-09：setTableName(Function) 与 pageByMain() 从解析链过滤，链不报错且 SQL 正常
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("selectAll", List.of(val("DeviceInfoEntity"))),
            call("setTableName", List.of(valPh("DeviceInfoEntity::getTableName"))),
            call("pageByMain", List.of()),
            call("eq", List.of(col("id"), val("1")))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            WHERE t.id = 1
            """.stripIndent(),
            sql
        );
        assertTrue("setTableName/pageByMain 不产生额外 SQL 片段", !sql.contains("getTableName"));
    }
}
