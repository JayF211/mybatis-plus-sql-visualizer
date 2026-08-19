package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.FillClause
import cn.song.mybatisplus.visualizer.model.SelectStatement
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
 * T-MpjFill 转换器单元测试：MPJ fill 关联填充（MPJ 1.5.1+，纯逻辑无 PSI）。
 *
 * 覆盖：
 *  - 形态 1（2 参）：基础形态分派、lambda 字段解析、关联表推断（via aliasToTable）
 *  - 形态 3（4 参 limit）：LIMIT 渲染、整数参数提取
 *  - 形态 4（4 参 condition=false）：跳过 fill
 *  - 形态 4（4 参 condition=true）：执行 fill
 *  - 形态 5（4 参 data）：数据源对象占位
 *  - 形态 6（5 参 limit+data）：复合参数
 *  - 多次 fill：按 chain 顺序保持
 *  - 主查询 + fill：主 SELECT 独立、fill 段追加
 *  - 容错：参数不足、无 join 上下文（无法推断关联表）返回 null 不崩溃
 *
 * 语义对齐 MPJ 官方源码（yulichang/mybatis-plus-join 1.5.6）：
 *  - JoinAbstractLambdaWrapper.fill 系列（6 种重载）
 *  - fill 为附加查询，不改变主查询形态
 */
class MpjFillConverterTest {

    private val table = TableInfo(
        entityClassName = "DeviceEntity",
        tableName = "device",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("name", "name"),
            ColumnInfo("stationId", "station_id"),
        ),
    )

    private val stationTable = TableInfo(
        entityClassName = "StationEntity",
        tableName = "station",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("stationName", "station_name"),
        ),
    )

    // ------------------------------------------------------------------
    // 形态 1（2 参）：fill(field, tagField)
    // ------------------------------------------------------------------

    /**
     * 构造带显式别名 "s" 的 leftJoin 调用（四参别名形态）。
     * fill 的 tagField 需使用相同别名 "s" 才能匹配关联表。
     */
    private fun leftJoinWithAliasS() = WrapperCall("leftJoin", listOf(
        WrapperArg.EntityRefArg(stationTable),
        WrapperArg.ValueArg(SqlValue.Literal("s")),
        WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
        WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
    ))

    @Test
    fun `fill_基础形态2参`() {
        // fill(Device::getStationId, Station::getId) + leftJoin(StationEntity, "s", ...)
        // → SELECT s.id, s.station_name FROM station s WHERE s.id IN (?)
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceEntity",
            listOf(
                leftJoinWithAliasS(),
                WrapperCall("fill", listOf(
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "t", columnName = "station_id")),
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "s", columnName = "id")),
                )),
            ),
        )
        val statement = MpjLambdaConverter.convert(chain, table) as SelectStatement
        val fills = statement.fillClauses
        assertNotNull(fills)
        assertEquals(1, fills!!.size)

        val fill = fills[0]
        assertEquals("station", fill.childTable.tableName)
        assertEquals("s", fill.childAlias)
        assertEquals("id", fill.tagField)
        assertNull(fill.limit)
        assertNull(fill.condition)
        assertNull(fill.data)

        // 验证 SQL 渲染
        val fillSql = SqlGenerator.generateFill(fill)
        assertEquals("SELECT s.id, s.station_name FROM station s WHERE s.id IN (?)", fillSql)
    }

    // ------------------------------------------------------------------
    // 形态 3（4 参 limit）：fill(field, tagField, mapperTagField, limit)
    // ------------------------------------------------------------------

    @Test
    fun `fill_limit限制_4参`() {
        // fill(Device::getStationId, Station::getId, "id", 10)
        // → SELECT s.id, s.station_name FROM station s WHERE s.id IN (?) LIMIT 10
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceEntity",
            listOf(
                leftJoinWithAliasS(),
                WrapperCall("fill", listOf(
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "t", columnName = "station_id")),
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "s", columnName = "id")),
                    WrapperArg.ValueArg(SqlValue.Literal("id")),
                    WrapperArg.ValueArg(SqlValue.Literal("10")),
                )),
            ),
        )
        val statement = MpjLambdaConverter.convert(chain, table) as SelectStatement
        val fills = statement.fillClauses
        assertNotNull(fills)
        assertEquals(1, fills!!.size)

        val fill = fills[0]
        assertEquals(10, fill.limit)
        assertNull(fill.condition)
        assertNull(fill.data)

        val fillSql = SqlGenerator.generateFill(fill)
        assertEquals("SELECT s.id, s.station_name FROM station s WHERE s.id IN (?) LIMIT 10", fillSql)
    }

    // ------------------------------------------------------------------
    // 形态 4（4 参 condition=false）：跳过 fill
    // ------------------------------------------------------------------

    @Test
    fun `fill_condition_false跳过`() {
        // fill(Device::getStationId, Station::getId, "id", false) → 不生成 FillClause
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceEntity",
            listOf(
                leftJoinWithAliasS(),
                WrapperCall("fill", listOf(
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "t", columnName = "station_id")),
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "s", columnName = "id")),
                    WrapperArg.ValueArg(SqlValue.Literal("id")),
                    WrapperArg.ValueArg(SqlValue.Literal("false")),
                )),
            ),
        )
        val statement = MpjLambdaConverter.convert(chain, table) as SelectStatement
        // condition=false → fillClauses 为 null（ifEmpty { null }）
        assertNull(statement.fillClauses)
    }

    // ------------------------------------------------------------------
    // 形态 4（4 参 condition=true）：执行 fill
    // ------------------------------------------------------------------

    @Test
    fun `fill_condition_true执行`() {
        // fill(Device::getStationId, Station::getId, "id", true) → 生成 FillClause
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceEntity",
            listOf(
                leftJoinWithAliasS(),
                WrapperCall("fill", listOf(
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "t", columnName = "station_id")),
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "s", columnName = "id")),
                    WrapperArg.ValueArg(SqlValue.Literal("id")),
                    WrapperArg.ValueArg(SqlValue.Literal("true")),
                )),
            ),
        )
        val statement = MpjLambdaConverter.convert(chain, table) as SelectStatement
        val fills = statement.fillClauses
        assertNotNull(fills)
        assertEquals(1, fills!!.size)
        assertEquals(true, fills[0].condition)
    }

    // ------------------------------------------------------------------
    // 形态 5（4 参 data）：数据源对象
    // ------------------------------------------------------------------

    @Test
    fun `fill_data数据源`() {
        // fill(Device::getStationId, Station::getId, "id", dataObj) → data 占位
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceEntity",
            listOf(
                leftJoinWithAliasS(),
                WrapperCall("fill", listOf(
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "t", columnName = "station_id")),
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "s", columnName = "id")),
                    WrapperArg.ValueArg(SqlValue.Literal("id")),
                    WrapperArg.ValueArg(SqlValue.Placeholder("dataObj")),
                )),
            ),
        )
        val statement = MpjLambdaConverter.convert(chain, table) as SelectStatement
        val fills = statement.fillClauses
        assertNotNull(fills)
        assertEquals(1, fills!!.size)
        assertNotNull(fills[0].data)
        assertEquals("dataObj", (fills[0].data as? SqlValue.Placeholder)?.source)
    }

    // ------------------------------------------------------------------
    // 形态 6（5 参 limit+data）：fill(field, tagField, mapperTagField, limit, data)
    // ------------------------------------------------------------------

    @Test
    fun `fill_limit_data_5参`() {
        // fill(Device::getStationId, Station::getId, "id", 5, dataObj)
        // → SELECT s.id, s.station_name FROM station s WHERE s.id IN (?) LIMIT 5
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceEntity",
            listOf(
                leftJoinWithAliasS(),
                WrapperCall("fill", listOf(
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "t", columnName = "station_id")),
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "s", columnName = "id")),
                    WrapperArg.ValueArg(SqlValue.Literal("id")),
                    WrapperArg.ValueArg(SqlValue.Literal("5")),
                    WrapperArg.ValueArg(SqlValue.Placeholder("dataObj")),
                )),
            ),
        )
        val statement = MpjLambdaConverter.convert(chain, table) as SelectStatement
        val fills = statement.fillClauses
        assertNotNull(fills)
        assertEquals(1, fills!!.size)

        val fill = fills[0]
        assertEquals(5, fill.limit)
        assertNotNull(fill.data)

        val fillSql = SqlGenerator.generateFill(fill)
        assertEquals("SELECT s.id, s.station_name FROM station s WHERE s.id IN (?) LIMIT 5", fillSql)
    }

    // ------------------------------------------------------------------
    // 多次 fill：按 chain 顺序保持
    // ------------------------------------------------------------------

    @Test
    fun `fill_多次fill_按顺序追加`() {
        // 两次 fill 调用 → 两个 FillClause 按 chain 顺序
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceEntity",
            listOf(
                leftJoinWithAliasS(),
                WrapperCall("fill", listOf(
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "t", columnName = "station_id")),
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "s", columnName = "id")),
                )),
                WrapperCall("fill", listOf(
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "t", columnName = "station_id")),
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "s", columnName = "station_name")),
                    WrapperArg.ValueArg(SqlValue.Literal("station_name")),
                    WrapperArg.ValueArg(SqlValue.Literal("3")),
                )),
            ),
        )
        val statement = MpjLambdaConverter.convert(chain, table) as SelectStatement
        val fills = statement.fillClauses
        assertNotNull(fills)
        assertEquals(2, fills!!.size)

        // 第一次 fill：基础形态（2 参）
        assertEquals("id", fills[0].tagField)
        assertNull(fills[0].limit)

        // 第二次 fill：带 limit
        assertEquals("station_name", fills[1].tagField)
        assertEquals(3, fills[1].limit)
    }

    // ------------------------------------------------------------------
    // 主查询 + fill：主 SELECT 独立、fill 段追加
    // ------------------------------------------------------------------

    @Test
    fun `fill_结合主查询join`() {
        // 主查询有 leftJoin + fill → 主 SELECT 独立（已有 join），fill 段追加
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceEntity",
            listOf(
                leftJoinWithAliasS(),
                WrapperCall("fill", listOf(
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "t", columnName = "station_id")),
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "s", columnName = "id")),
                )),
            ),
        )
        val statement = MpjLambdaConverter.convert(chain, table) as SelectStatement

        // 主 SELECT 包含 join
        assertTrue("主查询应包含 join", statement.joins.isNotEmpty())

        // fill 独立于主查询
        val fills = statement.fillClauses
        assertNotNull(fills)
        assertEquals(1, fills!!.size)

        // 主 SQL 渲染（不含 fill 段）
        val mainSql = SqlGenerator.generate(statement)
        assertTrue("主 SQL 应包含 JOIN", mainSql.contains("LEFT JOIN"))
        assertTrue("主 SQL 应包含主表列", mainSql.contains("FROM device"))

        // fill 段独立渲染
        val fillSql = SqlGenerator.generateFill(fills[0])
        assertEquals("SELECT s.id, s.station_name FROM station s WHERE s.id IN (?)", fillSql)

    }

    // ------------------------------------------------------------------
    // 容错：无 join 上下文（无法推断关联表）→ 返回 null 不崩溃
    // ------------------------------------------------------------------

    @Test
    fun `fill_无join上下文_容错返回null`() {
        // 无 leftJoin → aliasToTable 为空 → parseFill 返回 null → fillClauses 为 null
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceEntity",
            listOf(
                WrapperCall("fill", listOf(
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "t", columnName = "station_id")),
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "s", columnName = "id")),
                )),
            ),
        )
        val statement = MpjLambdaConverter.convert(chain, table) as SelectStatement
        assertNull("无 join 上下文时 fillClauses 应为 null", statement.fillClauses)
    }

    // ------------------------------------------------------------------
    // 容错：参数不足（< 2 参）→ 返回 null
    // ------------------------------------------------------------------

    @Test
    fun `fill_参数不足_容错返回null`() {
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceEntity",
            listOf(
                WrapperCall("fill", listOf(
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "t", columnName = "station_id")),
                )),
            ),
        )
        val statement = MpjLambdaConverter.convert(chain, table) as SelectStatement
        assertNull("参数不足时 fillClauses 应为 null", statement.fillClauses)
    }

    // ------------------------------------------------------------------
    // 容错：第 2 参为字符串（无实体上下文）→ 返回 null
    // ------------------------------------------------------------------

    @Test
    fun `fill_字符串tagField_无上下文容错`() {
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceEntity",
            listOf(
                WrapperCall("fill", listOf(
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "t", columnName = "station_id")),
                    WrapperArg.ValueArg(SqlValue.Literal("id")),
                )),
            ),
        )
        val statement = MpjLambdaConverter.convert(chain, table) as SelectStatement
        assertNull("字符串 tagField 无上下文时 fillClauses 应为 null", statement.fillClauses)
    }

    // ------------------------------------------------------------------
    // 容错：condition=false 在多次 fill 中仅跳过该次
    // ------------------------------------------------------------------

    @Test
    fun `fill_condition_false仅跳过当次`() {
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceEntity",
            listOf(
                leftJoinWithAliasS(),
                WrapperCall("fill", listOf(
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "t", columnName = "station_id")),
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "s", columnName = "id")),
                    WrapperArg.ValueArg(SqlValue.Literal("id")),
                    WrapperArg.ValueArg(SqlValue.Literal("false")),
                )),
                WrapperCall("fill", listOf(
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "t", columnName = "station_id")),
                    WrapperArg.ColumnArg(ColumnRef(tableAlias = "s", columnName = "id")),
                )),
            ),
        )
        val statement = MpjLambdaConverter.convert(chain, table) as SelectStatement
        val fills = statement.fillClauses
        assertNotNull(fills)
        // 第一次 fill 被跳过（condition=false），第二次保留
        assertEquals(1, fills!!.size)
        assertEquals("id", fills[0].tagField)
    }
}
