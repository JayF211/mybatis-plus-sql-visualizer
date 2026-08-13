package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4 转换器单元测试：UPDATE / DELETE / MPJ 联表（纯逻辑，无 PSI）。
 */
class UpdateDeleteMpjConverterTest {

    private val table = TableInfo(
        entityClassName = "DeviceInfoEntity",
        tableName = "car_device_info",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("stationId", "station_id"),
            ColumnInfo("shareSchemeId", "share_scheme_id"),
        ),
    )

    /** 便捷构造：eq(column, value)。 */
    private fun eqCall(column: String, value: String) = WrapperCall(
        "eq",
        listOf(WrapperArg.ColumnArg(ColumnRef(columnName = column)), WrapperArg.ValueArg(SqlValue.Literal(value))),
    )

    // ------------------------------------------------------------------
    // ConverterRegistry 分发（T-M4-01）
    // ------------------------------------------------------------------

    @Test
    fun `注册表_按类型分发`() {
        assertTrue(ConverterRegistry.convert(
            WrapperChain(WrapperType.LAMBDA_QUERY, "E", listOf(eqCall("id", "1"))), table,
        ) is cn.song.mybatisplus.visualizer.model.SelectStatement)
        assertTrue(ConverterRegistry.convert(
            WrapperChain(WrapperType.LAMBDA_UPDATE, "E", emptyList()), table,
        ) is cn.song.mybatisplus.visualizer.model.UpdateStatement)
        assertTrue(ConverterRegistry.convert(
            WrapperChain(WrapperType.LAMBDA_DELETE, "E", emptyList()), table,
        ) is cn.song.mybatisplus.visualizer.model.DeleteStatement)
        assertTrue(ConverterRegistry.convert(
            WrapperChain(WrapperType.MPJ_LAMBDA, "E", emptyList()), table,
        ) is cn.song.mybatisplus.visualizer.model.SelectStatement)
        assertTrue(ConverterRegistry.convert(
            WrapperChain(WrapperType.MPJ_QUERY, "E", emptyList()), table,
        ) is cn.song.mybatisplus.visualizer.model.SelectStatement)
    }

    // ------------------------------------------------------------------
    // UPDATE（F-09）
    // ------------------------------------------------------------------

    @Test
    fun `update_set与条件`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_UPDATE, "DeviceInfoEntity",
            listOf(
                WrapperCall("set", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")), WrapperArg.ValueArg(SqlValue.Literal("101")))),
                eqCall("id", "1"),
            ),
        )
        val sql = SqlGenerator.generate(LambdaUpdateConverter.convert(chain, table))
        assertEquals(
            """
            UPDATE car_device_info
            SET station_id = 101
            WHERE id = 1
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `update_setSql原始片段`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_UPDATE, "DeviceInfoEntity",
            listOf(
                WrapperCall("setSql", listOf(WrapperArg.ValueArg(SqlValue.Literal("share_scheme_id = share_scheme_id + 1")))),
            ),
        )
        val sql = SqlGenerator.generate(LambdaUpdateConverter.convert(chain, table))
        assertEquals(
            """
            UPDATE car_device_info
            SET share_scheme_id = share_scheme_id + 1
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `update_无where条件集为空`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_UPDATE, "DeviceInfoEntity",
            listOf(WrapperCall("set", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")), WrapperArg.ValueArg(SqlValue.Literal("1"))))),
        )
        val statement = LambdaUpdateConverter.convert(chain, table) as cn.song.mybatisplus.visualizer.model.UpdateStatement
        assertTrue("无 WHERE 条件", statement.conditions.isEmpty())
    }

    // ------------------------------------------------------------------
    // DELETE（F-10）
    // ------------------------------------------------------------------

    @Test
    fun `delete_带条件`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_DELETE, "DeviceInfoEntity",
            listOf(eqCall("id", "1001")),
        )
        val sql = SqlGenerator.generate(LambdaDeleteConverter.convert(chain, table))
        assertEquals(
            """
            DELETE FROM car_device_info
            WHERE id = 1001
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `delete_无where条件集为空`() {
        val chain = WrapperChain(WrapperType.LAMBDA_DELETE, "DeviceInfoEntity", emptyList())
        val statement = LambdaDeleteConverter.convert(chain, table) as cn.song.mybatisplus.visualizer.model.DeleteStatement
        assertTrue("无 WHERE 条件", statement.conditions.isEmpty())
        assertEquals("DELETE FROM car_device_info", SqlGenerator.generate(statement))
    }

    // ------------------------------------------------------------------
    // MPJ 联表（F-11）
    // ------------------------------------------------------------------

    @Test
    fun `mpjLambda_selectAs与leftJoin`() {
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("selectAs", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "id")), WrapperArg.ValueArg(SqlValue.Literal("device_id")))),
                WrapperCall("leftJoin", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("StationSchemeMerchantInfo")),
                    WrapperArg.OnConditionArg(
                        ColumnRef(columnName = "share_scheme_id"),
                        ColumnRef(columnName = "scheme_id"),
                    ),
                )),
                eqCall("id", "1001"),
            ),
        )
        val statement = MpjLambdaConverter.convert(chain, table)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT t.id AS device_id
            FROM car_device_info t
            LEFT JOIN station_scheme_merchant_info stationSchemeMerchantInfo ON t.share_scheme_id = stationSchemeMerchantInfo.scheme_id
            WHERE t.id = 1001
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjQuery_字符串字段与join`() {
        val chain = WrapperChain(
            WrapperType.MPJ_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("select", listOf(WrapperArg.ValueArg(SqlValue.Literal("t.id AS device_id")))),
                WrapperCall("leftJoin", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("station_scheme_merchant_info m")),
                    WrapperArg.ValueArg(SqlValue.Literal("m.scheme_id = t.share_scheme_id")),
                )),
                WrapperCall("eq", listOf(WrapperArg.ValueArg(SqlValue.Literal("t.id")), WrapperArg.ValueArg(SqlValue.Literal("1001")))),
            ),
        )
        val sql = SqlGenerator.generate(MpjQueryConverter.convert(chain, table))
        assertEquals(
            """
            SELECT t.id AS device_id
            FROM car_device_info t
            LEFT JOIN station_scheme_merchant_info m ON m.scheme_id = t.share_scheme_id
            WHERE t.id = 1001
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjLambda_selectAs方法引用别名`() {
        // P01/P02/P04：selectAs(Entity::getX, VO::getY)，别名 = VO 属性名（保留驼峰）
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("selectAs", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                    WrapperArg.AliasArg("deviceId"),
                )),
                WrapperCall("selectAs", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "station_name")),
                    WrapperArg.AliasArg("stationName"),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT t.id AS deviceId, t.station_name AS stationName
            FROM car_device_info t
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjLambda_selectAs字符串列不补主表前缀`() {
        // P11：selectAs("auditAccount.username", VO::getY) → 已含表别名限定，不再补 t.
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "WalletWithdrawEntity",
            listOf(
                WrapperCall("selectAs", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("auditAccount.username")),
                    WrapperArg.AliasArg("auditedUsername"),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT auditAccount.username AS auditedUsername
            FROM car_device_info t
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjLambda_selectAll主表全列`() {
        // P01：selectAll(Class) → 主表全列（带 t. 前缀）+ selectAs 别名列追加
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("selectAll", listOf(WrapperArg.ValueArg(SqlValue.Literal("DeviceInfoEntity")))),
                WrapperCall("selectAs", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                    WrapperArg.AliasArg("deviceId"),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id, t.id AS deviceId
            FROM car_device_info t
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjLambda_distinct与innerJoin`() {
        // P05：distinct + 多级 innerJoin
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "StationSchemeMerchantInfoEntity",
            listOf(
                WrapperCall("select", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")))),
                WrapperCall("distinct", emptyList()),
                WrapperCall("innerJoin", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("StationSchemeInfoEntity")),
                    WrapperArg.OnConditionArg(ColumnRef(columnName = "id"), ColumnRef(columnName = "scheme_id")),
                )),
                WrapperCall("eq", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "merchant_id")),
                    WrapperArg.ValueArg(SqlValue.Literal("5")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT DISTINCT t.station_id
            FROM car_device_info t
            INNER JOIN station_scheme_info_entity stationSchemeInfoEntity ON t.id = stationSchemeInfoEntity.scheme_id
            WHERE t.merchant_id = 5
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjLambda_selectMax与selectCount`() {
        // P18：selectMax 无别名；selectCount 第二参为 VO 属性名（保留驼峰）
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "OrderMonitorRecordEntity",
            listOf(
                WrapperCall("selectMax", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "power")))),
                WrapperCall("eq", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "order_id")),
                    WrapperArg.ValueArg(SqlValue.Literal("10")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT MAX(t.power)
            FROM car_device_info t
            WHERE t.order_id = 10
            """.trimIndent(),
            sql,
        )

        val countChain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "InvoiceApplyEntity",
            listOf(
                WrapperCall("selectCount", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                    WrapperArg.AliasArg("applyCount"),
                )),
                WrapperCall("groupBy", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "pay_id")))),
                WrapperCall("groupBy", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "contact_name")))),
            ),
        )
        val countSql = SqlGenerator.generate(MpjLambdaConverter.convert(countChain, table))
        assertEquals(
            """
            SELECT COUNT(t.id) AS applyCount
            FROM car_device_info t
            GROUP BY t.pay_id, t.contact_name
            """.trimIndent(),
            countSql,
        )
    }

    @Test
    fun `mpjLambda_selectFunc聚合`() {
        // P09：selectFunc 模板 %s 列占位 + {0}/{1} 绑定参数 + func.accept/values + 别名
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "WalletWithdrawEntity",
            listOf(
                WrapperCall("selectFunc", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("COUNT(%s)")),
                    WrapperArg.FuncArg(
                        columns = listOf(ColumnRef(columnName = "id")),
                        values = emptyList(),
                    ),
                    WrapperArg.ValueArg(SqlValue.Literal("totalCount")),
                )),
                WrapperCall("selectFunc", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("COUNT(CASE WHEN %s = {0} AND %s = {1} THEN 1 END)")),
                    WrapperArg.FuncArg(
                        columns = listOf(
                            ColumnRef(columnName = "dic_audit_status"),
                            ColumnRef(columnName = "dic_withdraw_status"),
                        ),
                        values = listOf(
                            SqlValue.Literal("2"),
                            SqlValue.Literal("1"),
                        ),
                    ),
                    WrapperArg.ValueArg(SqlValue.Literal("paymentProcessingCount")),
                )),
                WrapperCall("selectFunc", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("COALESCE(SUM(%s), 0)")),
                    WrapperArg.FuncArg(
                        columns = listOf(ColumnRef(columnName = "withdraw_amount")),
                        values = emptyList(),
                    ),
                    WrapperArg.ValueArg(SqlValue.Literal("totalWithdrawAmount")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT COUNT(t.id) AS totalCount, COUNT(CASE WHEN t.dic_audit_status = 2 AND t.dic_withdraw_status = 1 THEN 1 END) AS paymentProcessingCount, COALESCE(SUM(t.withdraw_amount), 0) AS totalWithdrawAmount
            FROM car_device_info t
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjLambda_selectCollection子查询`() {
        // P06 子查询形态：selectAll + selectCollection(子表, VO::get集合, map.id/result)
        // （P06 中同子表 leftJoin 的 ON 复用属 T-M7-05 三参 join 范围，此处聚焦子查询渲染本身）
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "OccupyRecordEntity",
            listOf(
                WrapperCall("selectAll", listOf(WrapperArg.ValueArg(SqlValue.Literal("OccupyRecordEntity")))),
                WrapperCall("selectCollection", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("OccupyConsumptionRecordEntity")),
                    WrapperArg.AliasArg("consumptions"),
                    WrapperArg.NestedArg(WrapperChain(WrapperType.NESTED, null, listOf(
                        WrapperCall("id", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "id")))),
                        WrapperCall("result", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "ladder")))),
                        WrapperCall("result", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "fee")))),
                    ))),
                )),
                WrapperCall("eq", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                    WrapperArg.ValueArg(SqlValue.Literal("1")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN (SELECT id, ladder, fee FROM occupy_consumption_record_entity occupyConsumptionRecordEntity) occupyConsumptionRecordEntity
            WHERE t.id = 1
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjLambda_selectAssociation不产生额外SQL`() {
        // P08：selectAssociation 仅结果映射标记，leftJoin 已体现关联，SQL 不重复生成
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("selectAll", listOf(WrapperArg.ValueArg(SqlValue.Literal("DeviceInfoEntity")))),
                WrapperCall("selectAssociation", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("ProductInfoEntity")),
                    WrapperArg.AliasArg("productInfo"),
                )),
                WrapperCall("leftJoin", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("ProductInfoEntity")),
                    WrapperArg.OnConditionArg(ColumnRef(columnName = "product_id"), ColumnRef(columnName = "id")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN product_info_entity productInfoEntity ON t.product_id = productInfoEntity.id
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjLambda_selectCollection嵌套collection`() {
        // P07：子映射内嵌套 collection → 子查询内递归 LEFT JOIN（三层关联；ON 按「孙表.父表名_id = 父表.id」约定，R7 待 MPJ 对照）
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "StationPriceTaskRecordEntity",
            listOf(
                WrapperCall("selectCollection", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("StationPriceTaskStationRelEntity")),
                    WrapperArg.AliasArg("stations"),
                    WrapperArg.NestedArg(WrapperChain(WrapperType.NESTED, null, listOf(
                        WrapperCall("id", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "id")))),
                        WrapperCall("collection", listOf(
                            WrapperArg.ValueArg(SqlValue.Literal("DeviceInfoEntity")),
                            WrapperArg.AliasArg("devices"),
                        )),
                    ))),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN (SELECT id FROM station_price_task_station_rel_entity stationPriceTaskStationRelEntity LEFT JOIN (SELECT * FROM device_info_entity deviceInfoEntity) deviceInfoEntity ON deviceInfoEntity.stationPriceTaskStationRelEntity_id = stationPriceTaskStationRelEntity.id) stationPriceTaskStationRelEntity
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjLambda_三参leftJoin`() {
        // P01/P05：leftJoin(Class, 关联表字段, 主表字段) → ON alias.左 = t.右
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("leftJoin", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("StationInfoEntity")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN station_info_entity stationInfoEntity ON stationInfoEntity.id = t.station_id
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjLambda_四参带别名join`() {
        // P11：leftJoin(Class, "rel", 关联表字段, 主表字段) → 显式别名
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "InvoiceApplyEntity",
            listOf(
                WrapperCall("innerJoin", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("InvoiceApplyOrderRelEntity")),
                    WrapperArg.ValueArg(SqlValue.Literal("rel")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "apply_id")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            INNER JOIN invoice_apply_order_rel_entity rel ON rel.apply_id = t.id
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjLambda_lambdaOn条件`() {
        // P13：leftJoin(Class, on -> on.eq(关联表字段, 主表字段).eq(关联表字段, 值))
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "CouponEntity",
            listOf(
                WrapperCall("leftJoin", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("CouponStationEntity")),
                    WrapperArg.NestedArg(WrapperChain(WrapperType.NESTED, null, listOf(
                        WrapperCall("eq", listOf(
                            WrapperArg.ColumnArg(ColumnRef(columnName = "coupon_id")),
                            WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                        )),
                        WrapperCall("eq", listOf(
                            WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                            WrapperArg.ValueArg(SqlValue.Literal("5")),
                        )),
                    ))),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN coupon_station_entity couponStationEntity ON couponStationEntity.coupon_id = t.id AND couponStationEntity.station_id = 5
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjLambda_表别名构造`() {
        // C4/P11：new MPJLambdaWrapper<>(Class, "apply_info") → 主表别名 apply_info
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "InvoiceApplyEntity",
            listOf(
                WrapperCall("eq", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                    WrapperArg.ValueArg(SqlValue.Literal("1")),
                )),
            ),
            constructorAlias = "apply_info",
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT apply_info.id, apply_info.station_id, apply_info.share_scheme_id
            FROM car_device_info apply_info
            WHERE apply_info.id = 1
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjLambda_orderBy字符串别名`() {
        // P11：orderByAsc("auditAccount", Entity::getCreateTime) → 字符串别名排序
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "WalletWithdrawEntity",
            listOf(
                WrapperCall("orderByAsc", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("auditAccount")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "create_time")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            ORDER BY auditAccount.create_time ASC
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjLambda_join类型映射`() {
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "E",
            listOf(
                WrapperCall("innerJoin", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("Foo")),
                    WrapperArg.OnConditionArg(ColumnRef(columnName = "a"), ColumnRef(columnName = "b")),
                )),
            ),
        )
        val statement = MpjLambdaConverter.convert(chain, table) as cn.song.mybatisplus.visualizer.model.SelectStatement
        assertEquals(1, statement.joins.size)
        assertEquals(JoinType.INNER, statement.joins[0].type)
        assertEquals("foo", statement.joins[0].tableName)
        assertEquals("foo", statement.joins[0].alias)
        assertNotNull(statement.joins[0].onConditions.firstOrNull() as Condition.ColumnComparison)
    }

    // ------------------------------------------------------------------
    // T-M20-04：selectIgnore（selectAll 全列展开后剔除列）+ stringQuery()/lambda() 互转
    // ------------------------------------------------------------------

    @Test
    fun `mpjQuery_selectIgnore从全列剔除字符串列`() {
        // T-M20-04：MPJQueryWrapper（全字符串）selectAll + selectIgnore("t.station_id", "t.share_scheme_id AS s")
        // → SELECT 全列 - 忽略列（带主表别名 t.；字符串形态剔除 AS 后缀与 t. 前缀后与实体列匹配）
        val chain = WrapperChain(
            WrapperType.MPJ_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("selectAll", listOf(WrapperArg.ValueArg(SqlValue.Literal("DeviceInfoEntity")))),
                WrapperCall("selectIgnore", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("t.station_id")),
                    WrapperArg.ValueArg(SqlValue.Literal("t.share_scheme_id AS s")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjQueryConverter.convert(chain, table))
        assertEquals(
            """
            SELECT t.id
            FROM car_device_info t
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjLambdaQuery_selectIgnore从全列剔除lambda列`() {
        // T-M20-04：MPJLambdaQueryWrapper（lambda 列）selectAll + selectIgnore(Entity::getX, "t.share_scheme_id")
        // → SELECT 全列 - 忽略列（无主表别名）
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("selectAll", listOf(WrapperArg.ValueArg(SqlValue.Literal("DeviceInfoEntity")))),
                WrapperCall("selectIgnore", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                    WrapperArg.ValueArg(SqlValue.Literal("t.share_scheme_id")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaQueryConverter.convert(chain, table))
        assertEquals(
            """
            SELECT id
            FROM car_device_info
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjLambdaQuery_lambda与stringQuery中间节点不破坏解析`() {
        // T-M20-04：stringQuery()/lambda() 为链中间节点（psi 层已并入链，WrapperType 保持 MPJ_LAMBDA_QUERY），
        // 转换器静默忽略——select/条件/orderByStr 解析不受影响
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("select", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "id")))),
                WrapperCall("lambda", emptyList()),
                WrapperCall("eq", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                    WrapperArg.ValueArg(SqlValue.Literal("1001")),
                )),
                WrapperCall("stringQuery", emptyList()),
                WrapperCall("orderByStr", listOf(WrapperArg.ValueArg(SqlValue.Literal("t.create_time")))),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaQueryConverter.convert(chain, table))
        assertEquals(
            """
            SELECT id
            FROM car_device_info
            WHERE id = 1001
            ORDER BY t.create_time ASC
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjQuery_stringQuery与lambda中间节点不破坏解析`() {
        // T-M20-04：MPJQueryWrapper 链含 stringQuery()/lambda() 中间节点——restCalls 过滤按方法名进行，
        // 不受互转节点截断；groupByStr 经字符串链端到端生效
        val chain = WrapperChain(
            WrapperType.MPJ_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("select", listOf(WrapperArg.ValueArg(SqlValue.Literal("t.id AS device_id")))),
                WrapperCall("stringQuery", emptyList()),
                WrapperCall("eq", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("t.id")),
                    WrapperArg.ValueArg(SqlValue.Literal("1001")),
                )),
                WrapperCall("lambda", emptyList()),
                WrapperCall("groupByStr", listOf(WrapperArg.ValueArg(SqlValue.Literal("t.station_id")))),
            ),
        )
        val sql = SqlGenerator.generate(MpjQueryConverter.convert(chain, table))
        assertEquals(
            """
            SELECT t.id AS device_id
            FROM car_device_info t
            WHERE t.id = 1001
            GROUP BY t.station_id
            """.trimIndent(),
            sql,
        )
    }

    // ------------------------------------------------------------------
    // T-M20-05：parseJoin 填充关联表逻辑删除列（@TableLogic）
    // ------------------------------------------------------------------

    @Test
    fun `mpjLambda_leftJoin实体引用携带逻辑删除列`() {
        // T-M20-05：EntityRefArg 形态（psi 解析实体）填充 logicDeleteColumn（@TableLogic 列），
        // 供 SqlPreviewService 副表注入；类名字面量兜底形态无 TableInfo → 不填充（不注入）
        val subTable = TableInfo(
            entityClassName = "CouponEntity",
            tableName = "coupon_entity",
            columns = listOf(
                ColumnInfo("id", "id"),
                ColumnInfo("merchantId", "merchant_id"),
                ColumnInfo("deleted", "deleted"),
            ),
            logicDeleteColumn = "deleted",
        )
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("leftJoin", listOf(
                    WrapperArg.EntityRefArg(subTable),
                    WrapperArg.OnConditionArg(
                        ColumnRef(columnName = "merchant_id"),
                        ColumnRef(columnName = "id"),
                    ),
                )),
            ),
        )
        val statement = MpjLambdaConverter.convert(chain, table) as cn.song.mybatisplus.visualizer.model.SelectStatement
        assertEquals("EntityRefArg 形态应填充逻辑删除列", "deleted", statement.joins.single().logicDeleteColumn)

        // 类名字面量兜底形态（无 TableInfo）→ logicDeleteColumn 为 null
        val literalChain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("leftJoin", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("StationInfoEntity")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                )),
            ),
        )
        val literalStatement = MpjLambdaConverter.convert(literalChain, table) as cn.song.mybatisplus.visualizer.model.SelectStatement
        assertNull("类名字面量形态无 TableInfo → 不填充", literalStatement.joins.single().logicDeleteColumn)
    }
}
