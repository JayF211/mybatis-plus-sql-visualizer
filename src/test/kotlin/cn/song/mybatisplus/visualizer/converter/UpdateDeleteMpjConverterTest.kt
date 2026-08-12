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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4 转换器单元测试：UPDATE / DELETE / MPJ 联表（纯逻辑，无 PSI）。
 */
class UpdateDeleteMpjConverterTest {

    private val table = TableInfo(
        entityClassName = "CarDeviceInfoEntity",
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
            WrapperType.LAMBDA_UPDATE, "CarDeviceInfoEntity",
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
            WrapperType.LAMBDA_UPDATE, "CarDeviceInfoEntity",
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
            WrapperType.LAMBDA_UPDATE, "CarDeviceInfoEntity",
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
            WrapperType.LAMBDA_DELETE, "CarDeviceInfoEntity",
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
        val chain = WrapperChain(WrapperType.LAMBDA_DELETE, "CarDeviceInfoEntity", emptyList())
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
            WrapperType.MPJ_LAMBDA, "CarDeviceInfoEntity",
            listOf(
                WrapperCall("selectAs", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "id")), WrapperArg.ValueArg(SqlValue.Literal("device_id")))),
                WrapperCall("leftJoin", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("CarStationShareSchemeMerchantInfo")),
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
            LEFT JOIN car_station_share_scheme_merchant_info carStationShareSchemeMerchantInfo ON t.share_scheme_id = carStationShareSchemeMerchantInfo.scheme_id
            WHERE t.id = 1001
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjQuery_字符串字段与join`() {
        val chain = WrapperChain(
            WrapperType.MPJ_QUERY, "CarDeviceInfoEntity",
            listOf(
                WrapperCall("select", listOf(WrapperArg.ValueArg(SqlValue.Literal("t.id AS device_id")))),
                WrapperCall("leftJoin", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("car_station_share_scheme_merchant_info m")),
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
            LEFT JOIN car_station_share_scheme_merchant_info m ON m.scheme_id = t.share_scheme_id
            WHERE t.id = 1001
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
}
