package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.ColumnRole;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.UpdateStatement;
import cn.song.mybatisplus.visualizer.model.WrapperArg;
import cn.song.mybatisplus.visualizer.model.WrapperCall;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;
import kotlin.Pair;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * M4 转换器单元测试：UPDATE / DELETE / MPJ 联表（纯逻辑，无 PSI）。
 */
public class UpdateDeleteMpjConverterTest {

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

    private static ColumnRef cref(String column) {
        return new ColumnRef(null, column, null, null, null, null, null);
    }

    private static WrapperArg.ColumnArg col(String column) {
        return new WrapperArg.ColumnArg(cref(column));
    }

    private static WrapperArg.ValueArg val(String value) {
        return new WrapperArg.ValueArg(new SqlValue.Literal(value, value));
    }

    private static SqlValue.Literal lit(String value) {
        return new SqlValue.Literal(value, value);
    }

    private static WrapperArg.ValueArg ph(String value) {
        return new WrapperArg.ValueArg(new SqlValue.Placeholder(value));
    }

    private static WrapperArg.AliasArg aliasArg(String prop) {
        return new WrapperArg.AliasArg(prop, null);
    }

    private static WrapperArg.OnConditionArg onCond(String left, String right) {
        return new WrapperArg.OnConditionArg(cref(left), cref(right));
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

    /** 便捷构造：eq(column, value)。 */
    private static WrapperCall eqCall(String column, String value) {
        return new WrapperCall(
            "eq",
            List.of(col(column), val(value))
        );
    }

    private static WrapperChain chain(WrapperType type, String entityClass, List<WrapperCall> calls) {
        return new WrapperChain(type, entityClass, calls, null, false, false, false, false, null);
    }

    private static WrapperChain chainAlias(WrapperType type, String entityClass, List<WrapperCall> calls, String alias) {
        return new WrapperChain(type, entityClass, calls, alias, false, false, false, false, null);
    }

    private static WrapperChain nested(List<WrapperCall> calls) {
        return new WrapperChain(WrapperType.NESTED, null, calls, null, false, false, false, false, null);
    }

    // ------------------------------------------------------------------
    // ConverterRegistry 分发（T-M4-01）
    // ------------------------------------------------------------------

    @Test
    public void 注册表_按类型分发() throws Exception {
        SqlStatement s1 = ConverterRegistry.INSTANCE.convert(
            chain(WrapperType.LAMBDA_QUERY, "E", List.of(eqCall("id", "1"))), table);
        assertTrue(s1 instanceof SelectStatement);
        SqlStatement s2 = ConverterRegistry.INSTANCE.convert(
            chain(WrapperType.LAMBDA_UPDATE, "E", List.of()), table);
        assertTrue(s2 instanceof UpdateStatement);
        SqlStatement s3 = ConverterRegistry.INSTANCE.convert(
            chain(WrapperType.LAMBDA_DELETE, "E", List.of()), table);
        assertTrue(s3 instanceof DeleteStatement);
        SqlStatement s4 = ConverterRegistry.INSTANCE.convert(
            chain(WrapperType.MPJ_LAMBDA, "E", List.of()), table);
        assertTrue(s4 instanceof SelectStatement);
        SqlStatement s5 = ConverterRegistry.INSTANCE.convert(
            chain(WrapperType.MPJ_QUERY, "E", List.of()), table);
        assertTrue(s5 instanceof SelectStatement);
    }

    // ------------------------------------------------------------------
    // UPDATE（F-09）
    // ------------------------------------------------------------------

    @Test
    public void update_set与条件() throws Exception {
        WrapperChain chain = chain(WrapperType.LAMBDA_UPDATE, "DeviceInfoEntity", List.of(
            call("set", List.of(col("station_id"), val("101"))),
            eqCall("id", "1")
        ));
        String sql = SqlGenerator.INSTANCE.generate(LambdaUpdateConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            UPDATE car_device_info
            SET station_id = 101
            WHERE id = 1
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void update_setSql原始片段() throws Exception {
        WrapperChain chain = chain(WrapperType.LAMBDA_UPDATE, "DeviceInfoEntity", List.of(
            call("setSql", List.of(val("share_scheme_id = share_scheme_id + 1")))
        ));
        String sql = SqlGenerator.INSTANCE.generate(LambdaUpdateConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            UPDATE car_device_info
            SET share_scheme_id = share_scheme_id + 1
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void update_无where条件集为空() throws Exception {
        WrapperChain chain = chain(WrapperType.LAMBDA_UPDATE, "DeviceInfoEntity", List.of(
            call("set", List.of(col("station_id"), val("1")))
        ));
        UpdateStatement statement = (UpdateStatement) LambdaUpdateConverter.INSTANCE.convert(chain, table);
        assertTrue("无 WHERE 条件", statement.getConditions().isEmpty());
    }

    // ------------------------------------------------------------------
    // DELETE（F-10）
    // ------------------------------------------------------------------

    @Test
    public void delete_带条件() throws Exception {
        WrapperChain chain = chain(WrapperType.LAMBDA_DELETE, "DeviceInfoEntity", List.of(
            eqCall("id", "1001")
        ));
        String sql = SqlGenerator.INSTANCE.generate(LambdaDeleteConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            DELETE FROM car_device_info
            WHERE id = 1001
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void delete_无where条件集为空() throws Exception {
        WrapperChain chain = chain(WrapperType.LAMBDA_DELETE, "DeviceInfoEntity", List.of());
        DeleteStatement statement = (DeleteStatement) LambdaDeleteConverter.INSTANCE.convert(chain, table);
        assertTrue("无 WHERE 条件", statement.getConditions().isEmpty());
        assertEquals("DELETE FROM car_device_info", SqlGenerator.INSTANCE.generate(statement));
    }

    // ------------------------------------------------------------------
    // MPJ 联表（F-11）
    // ------------------------------------------------------------------

    @Test
    public void mpjLambda_selectAs与leftJoin() throws Exception {
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("selectAs", List.of(col("id"), val("device_id"))),
            call("leftJoin", List.of(
                val("StationSchemeMerchantInfo"),
                onCond("share_scheme_id", "scheme_id")
            )),
            eqCall("id", "1001")
        ));
        SelectStatement statement = (SelectStatement) MpjLambdaConverter.INSTANCE.convert(chain, table);
        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertEquals(
            """
            SELECT t.id AS device_id
            FROM car_device_info t
            LEFT JOIN station_scheme_merchant_info stationSchemeMerchantInfo ON t.share_scheme_id = stationSchemeMerchantInfo.scheme_id
            WHERE t.id = 1001
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjQuery_字符串字段与join() throws Exception {
        WrapperChain chain = chain(WrapperType.MPJ_QUERY, "DeviceInfoEntity", List.of(
            call("select", List.of(val("t.id AS device_id"))),
            call("leftJoin", List.of(
                val("station_scheme_merchant_info m"),
                val("m.scheme_id = t.share_scheme_id")
            )),
            call("eq", List.of(val("t.id"), val("1001")))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjQueryConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id AS device_id
            FROM car_device_info t
            LEFT JOIN station_scheme_merchant_info m ON m.scheme_id = t.share_scheme_id
            WHERE t.id = 1001
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjLambda_selectAs方法引用别名() throws Exception {
        // P01/P02/P04：selectAs(Entity::getX, VO::getY)，别名 = VO 属性名（保留驼峰）
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("selectAs", List.of(col("id"), aliasArg("deviceId"))),
            call("selectAs", List.of(col("station_name"), aliasArg("stationName")))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id AS deviceId, t.station_name AS stationName
            FROM car_device_info t
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjLambda_selectAs字符串列不补主表前缀() throws Exception {
        // P11：selectAs("auditAccount.username", VO::getY) → 已含表别名限定，不再补 t.
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "WalletWithdrawEntity", List.of(
            call("selectAs", List.of(val("auditAccount.username"), aliasArg("auditedUsername")))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT auditAccount.username AS auditedUsername
            FROM car_device_info t
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjLambda_selectAll主表全列() throws Exception {
        // P01：selectAll(Class) → 主表全列（带 t. 前缀）+ selectAs 别名列追加
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("selectAll", List.of(val("DeviceInfoEntity"))),
            call("selectAs", List.of(col("id"), aliasArg("deviceId")))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id, t.id AS deviceId
            FROM car_device_info t
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjLambda_distinct与innerJoin() throws Exception {
        // P05：distinct + 多级 innerJoin
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "StationSchemeMerchantInfoEntity", List.of(
            call("select", List.of(col("station_id"))),
            call("distinct", List.of()),
            call("innerJoin", List.of(
                val("StationSchemeInfoEntity"),
                onCond("id", "scheme_id")
            )),
            call("eq", List.of(col("merchant_id"), val("5")))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT DISTINCT t.station_id
            FROM car_device_info t
            INNER JOIN station_scheme_info_entity stationSchemeInfoEntity ON t.id = stationSchemeInfoEntity.scheme_id
            WHERE t.merchant_id = 5
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjLambda_selectMax与selectCount() throws Exception {
        // P18：selectMax 无别名；selectCount 第二参为 VO 属性名（保留驼峰）
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "OrderMonitorRecordEntity", List.of(
            call("selectMax", List.of(col("power"))),
            call("eq", List.of(col("order_id"), val("10")))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT MAX(t.power)
            FROM car_device_info t
            WHERE t.order_id = 10
            """.stripTrailing(),
            sql
        );

        WrapperChain countChain = chain(WrapperType.MPJ_LAMBDA, "InvoiceApplyEntity", List.of(
            call("selectCount", List.of(col("id"), aliasArg("applyCount"))),
            call("groupBy", List.of(col("pay_id"))),
            call("groupBy", List.of(col("contact_name")))
        ));
        String countSql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(countChain, table));
        assertEquals(
            """
            SELECT COUNT(t.id) AS applyCount
            FROM car_device_info t
            GROUP BY t.pay_id, t.contact_name
            """.stripTrailing(),
            countSql
        );
    }

    @Test
    public void mpjLambda_selectFunc聚合() throws Exception {
        // P09：selectFunc 模板 %s 列占位 + {0}/{1} 绑定参数 + func.accept/values + 别名
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "WalletWithdrawEntity", List.of(
            call("selectFunc", List.of(
                val("COUNT(%s)"),
                new WrapperArg.FuncArg(List.of(cref("id")), List.of()),
                val("totalCount")
            )),
            call("selectFunc", List.of(
                val("COUNT(CASE WHEN %s = {0} AND %s = {1} THEN 1 END)"),
                new WrapperArg.FuncArg(
                    List.of(cref("dic_audit_status"), cref("dic_withdraw_status")),
                    List.of(lit("2"), lit("1"))
                ),
                val("paymentProcessingCount")
            )),
            call("selectFunc", List.of(
                val("COALESCE(SUM(%s), 0)"),
                new WrapperArg.FuncArg(List.of(cref("withdraw_amount")), List.of()),
                val("totalWithdrawAmount")
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT COUNT(t.id) AS totalCount, COUNT(CASE WHEN t.dic_audit_status = 2 AND t.dic_withdraw_status = 1 THEN 1 END) AS paymentProcessingCount, COALESCE(SUM(t.withdraw_amount), 0) AS totalWithdrawAmount
            FROM car_device_info t
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjLambda_selectCollection子查询() throws Exception {
        // P06 子查询形态：selectAll + selectCollection(子表, VO::get集合, map.id/result)
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "OccupyRecordEntity", List.of(
            call("selectAll", List.of(val("OccupyRecordEntity"))),
            call("selectCollection", List.of(
                val("OccupyConsumptionRecordEntity"),
                aliasArg("consumptions"),
                nestedArg(nested(List.of(
                    call("id", List.of(col("id"))),
                    call("result", List.of(col("ladder"))),
                    call("result", List.of(col("fee")))
                )))
            )),
            call("eq", List.of(col("id"), val("1")))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN (SELECT id, ladder, fee FROM occupy_consumption_record_entity occupyConsumptionRecordEntity) occupyConsumptionRecordEntity
            WHERE t.id = 1
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjLambda_selectAssociation不产生额外SQL() throws Exception {
        // P08：selectAssociation 仅结果映射标记，leftJoin 已体现关联，SQL 不重复生成
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("selectAll", List.of(val("DeviceInfoEntity"))),
            call("selectAssociation", List.of(
                val("ProductInfoEntity"),
                aliasArg("productInfo")
            )),
            call("leftJoin", List.of(
                val("ProductInfoEntity"),
                onCond("product_id", "id")
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN product_info_entity productInfoEntity ON t.product_id = productInfoEntity.id
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjLambda_selectCollection嵌套collection() throws Exception {
        // P07：子映射内嵌套 collection → 子查询内递归 LEFT JOIN
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "StationPriceTaskRecordEntity", List.of(
            call("selectCollection", List.of(
                val("StationPriceTaskStationRelEntity"),
                aliasArg("stations"),
                nestedArg(nested(List.of(
                    call("id", List.of(col("id"))),
                    call("collection", List.of(
                        val("DeviceInfoEntity"),
                        aliasArg("devices")
                    ))
                )))
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN (SELECT id FROM station_price_task_station_rel_entity stationPriceTaskStationRelEntity LEFT JOIN (SELECT * FROM device_info_entity deviceInfoEntity) deviceInfoEntity ON deviceInfoEntity.stationPriceTaskStationRelEntity_id = stationPriceTaskStationRelEntity.id) stationPriceTaskStationRelEntity
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjLambda_三参leftJoin() throws Exception {
        // P01/P05：leftJoin(Class, 关联表字段, 主表字段) → ON alias.左 = t.右
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("leftJoin", List.of(
                val("StationInfoEntity"),
                col("id"),
                col("station_id")
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN station_info_entity stationInfoEntity ON stationInfoEntity.id = t.station_id
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjLambda_四参带别名join() throws Exception {
        // P11：leftJoin(Class, "rel", 关联表字段, 主表字段) → 显式别名
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "InvoiceApplyEntity", List.of(
            call("innerJoin", List.of(
                val("InvoiceApplyOrderRelEntity"),
                val("rel"),
                col("apply_id"),
                col("id")
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            INNER JOIN invoice_apply_order_rel_entity rel ON rel.apply_id = t.id
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjLambda_lambdaOn条件() throws Exception {
        // P13：leftJoin(Class, on -> on.eq(关联表字段, 主表字段).eq(关联表字段, 值))
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "CouponEntity", List.of(
            call("leftJoin", List.of(
                val("CouponStationEntity"),
                nestedArg(nested(List.of(
                    call("eq", List.of(col("coupon_id"), col("id"))),
                    call("eq", List.of(col("station_id"), val("5")))
                )))
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN coupon_station_entity couponStationEntity ON couponStationEntity.coupon_id = t.id AND couponStationEntity.station_id = 5
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjLambda_表别名构造() throws Exception {
        // C4/P11：new MPJLambdaWrapper<>(Class, "apply_info") → 主表别名 apply_info
        WrapperChain chain = chainAlias(WrapperType.MPJ_LAMBDA, "InvoiceApplyEntity", List.of(
            call("eq", List.of(col("id"), val("1")))
        ), "apply_info");
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT apply_info.id, apply_info.station_id, apply_info.share_scheme_id
            FROM car_device_info apply_info
            WHERE apply_info.id = 1
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjLambda_orderBy字符串别名() throws Exception {
        // P11：orderByAsc("auditAccount", Entity::getCreateTime) → 字符串别名排序
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "WalletWithdrawEntity", List.of(
            call("orderByAsc", List.of(
                val("auditAccount"),
                col("create_time")
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            ORDER BY auditAccount.create_time ASC
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjLambda_join类型映射() throws Exception {
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "E", List.of(
            call("innerJoin", List.of(
                val("Foo"),
                onCond("a", "b")
            ))
        ));
        SelectStatement statement = (SelectStatement) MpjLambdaConverter.INSTANCE.convert(chain, table);
        assertEquals(1, statement.getJoins().size());
        assertEquals(JoinType.INNER, statement.getJoins().get(0).getType());
        assertEquals("foo", statement.getJoins().get(0).getTableName());
        assertEquals("foo", statement.getJoins().get(0).getAlias());
        assertNotNull((Condition.ColumnComparison) statement.getJoins().get(0).getOnConditions().get(0));
    }

    // ------------------------------------------------------------------
    // T-M20-04：selectIgnore（selectAll 全列展开后剔除列）+ stringQuery()/lambda() 互转
    // ------------------------------------------------------------------

    @Test
    public void mpjQuery_selectIgnore从全列剔除字符串列() throws Exception {
        // T-M20-04：MPJQueryWrapper（全字符串）selectAll + selectIgnore("t.station_id", "t.share_scheme_id AS s")
        WrapperChain chain = chain(WrapperType.MPJ_QUERY, "DeviceInfoEntity", List.of(
            call("selectAll", List.of(val("DeviceInfoEntity"))),
            call("selectIgnore", List.of(
                val("t.station_id"),
                val("t.share_scheme_id AS s")
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjQueryConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id
            FROM car_device_info t
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjLambdaQuery_selectIgnore从全列剔除lambda列() throws Exception {
        // T-M20-04：MPJLambdaQueryWrapper（lambda 列）selectAll + selectIgnore(Entity::getX, "t.share_scheme_id")
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA_QUERY, "DeviceInfoEntity", List.of(
            call("selectAll", List.of(val("DeviceInfoEntity"))),
            call("selectIgnore", List.of(
                col("station_id"),
                val("t.share_scheme_id")
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaQueryConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT id
            FROM car_device_info
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjLambdaQuery_lambda与stringQuery中间节点不破坏解析() throws Exception {
        // T-M20-04：stringQuery()/lambda() 为链中间节点，转换器静默忽略
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA_QUERY, "DeviceInfoEntity", List.of(
            call("select", List.of(col("id"))),
            call("lambda", List.of()),
            call("eq", List.of(col("id"), val("1001"))),
            call("stringQuery", List.of()),
            call("orderByStr", List.of(val("t.create_time")))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaQueryConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT id
            FROM car_device_info
            WHERE id = 1001
            ORDER BY t.create_time ASC
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjQuery_stringQuery与lambda中间节点不破坏解析() throws Exception {
        // T-M20-04：MPJQueryWrapper 链含 stringQuery()/lambda() 中间节点
        WrapperChain chain = chain(WrapperType.MPJ_QUERY, "DeviceInfoEntity", List.of(
            call("select", List.of(val("t.id AS device_id"))),
            call("stringQuery", List.of()),
            call("eq", List.of(val("t.id"), val("1001"))),
            call("lambda", List.of()),
            call("groupByStr", List.of(val("t.station_id")))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjQueryConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id AS device_id
            FROM car_device_info t
            WHERE t.id = 1001
            GROUP BY t.station_id
            """.stripTrailing(),
            sql
        );
    }

    // ------------------------------------------------------------------
    // T-M20-05：parseJoin 填充关联表逻辑删除列（@TableLogic）
    // ------------------------------------------------------------------

    @Test
    public void mpjLambda_leftJoin实体引用携带逻辑删除列() throws Exception {
        // T-M20-05：EntityRefArg 形态（psi 解析实体）填充 logicDeleteColumn；类名字面量兜底形态无 TableInfo → 不填充
        TableInfo subTable = new TableInfo(
            "CouponEntity",
            "coupon_entity",
            List.of(
                new ColumnInfo("id", "id", false, false, null, null, null, ColumnRole.BUSINESS),
                new ColumnInfo("merchantId", "merchant_id", false, false, null, null, null, ColumnRole.BUSINESS),
                new ColumnInfo("deleted", "deleted", false, false, null, null, null, ColumnRole.BUSINESS)
            ),
            "deleted", null, null, List.of()
        );
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("leftJoin", List.of(
                entityRef(subTable),
                onCond("merchant_id", "id")
            ))
        ));
        SelectStatement statement = (SelectStatement) MpjLambdaConverter.INSTANCE.convert(chain, table);
        assertEquals("EntityRefArg 形态应填充逻辑删除列", "deleted", statement.getJoins().get(0).getLogicDeleteColumn());

        // 类名字面量兜底形态（无 TableInfo）→ logicDeleteColumn 为 null
        WrapperChain literalChain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("leftJoin", List.of(
                val("StationInfoEntity"),
                col("id"),
                col("station_id")
            ))
        ));
        SelectStatement literalStatement = (SelectStatement) MpjLambdaConverter.INSTANCE.convert(literalChain, table);
        assertNull("类名字面量形态无 TableInfo → 不填充", literalStatement.getJoins().get(0).getLogicDeleteColumn());
    }

    // ------------------------------------------------------------------
    // T-M21-09：setIfExists（MPJ 1.4.9+）
    // ------------------------------------------------------------------

    @Test
    public void update_setIfExists归一为set() throws Exception {
        // T-M21-09：setIfExists(col, val) → 归一为 set 处理渲染 SET 子句
        WrapperChain chain = chain(WrapperType.LAMBDA_UPDATE, "DeviceInfoEntity", List.of(
            call("setIfExists", List.of(col("station_id"), val("101"))),
            eqCall("id", "1")
        ));
        String sql = SqlGenerator.INSTANCE.generate(LambdaUpdateConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            UPDATE car_device_info
            SET station_id = 101
            WHERE id = 1
            """.stripTrailing(),
            sql
        );
    }

    // ------------------------------------------------------------------
    // T-M21-04 / T-M21-01 / T-M21-06：MPJ 主表别名下的 allEq / selectFilter / selectFunc 枚举
    // ------------------------------------------------------------------

    @Test
    public void mpjLambda_allEq展开等值条件带主表前缀() throws Exception {
        // T-M21-04：allEq(Map) 在 MPJ 联表上下文 → 键列带主表别名前缀
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("allEq", List.of(
                new WrapperArg.MapArg(List.of(
                    new Pair<>(cref("station_id"), lit("1001")),
                    new Pair<>(cref("share_scheme_id"), lit("5"))
                ), false)
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            WHERE (t.station_id = 1001 AND t.share_scheme_id = 5)
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjLambda_selectFilter过滤列带主表前缀() throws Exception {
        // T-M21-01：selectFilter(Class, Predicate) 在 MPJ 上下文 → 过滤后列带主表别名前缀
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("selectFilter", List.of(
                entityRef(table),
                new WrapperArg.PredicateArg(List.of(), List.of("share_scheme_id"))
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT t.id, t.station_id
            FROM car_device_info t
            """.stripTrailing(),
            sql
        );
    }

    @Test
    public void mpjLambda_selectFunc枚举形态带主表前缀() throws Exception {
        // T-M21-06：selectFunc(DefaultFuncEnum.SUM, col, alias) 在 MPJ 上下文 → SUM(t.col) AS alias
        WrapperChain chain = chain(WrapperType.MPJ_LAMBDA, "DeviceInfoEntity", List.of(
            call("selectFunc", List.of(
                ph("DefaultFuncEnum.SUM"),
                new WrapperArg.FuncArg(List.of(cref("id")), List.of()),
                aliasArg("totalCount")
            )),
            call("selectFunc", List.of(
                ph("DefaultFuncEnum.AVG"),
                new WrapperArg.FuncArg(List.of(cref("station_id")), List.of()),
                val("avgStation")
            ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(MpjLambdaConverter.INSTANCE.convert(chain, table));
        assertEquals(
            """
            SELECT SUM(t.id) AS totalCount, AVG(t.station_id) AS avgStation
            FROM car_device_info t
            """.stripTrailing(),
            sql
        );
    }
}
