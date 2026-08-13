package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M21 转换器单元测试：MPJ 联表扩展（纯逻辑，无 PSI）。
 *
 * 覆盖：
 *  - T-M21-02：join 5 参 rightAlias / 4 参 rightAlias / ext MFunction / 子表 MConsumer（子查询 join）
 *  - T-M21-03：selectCollection prefix / free 形态（回归既有 Class 形态）
 *  - T-M21-07：from(子查询主表)
 *  - T-M21-09（部分）：around / setTableName / pageByMain
 *
 * 语义对齐 MPJ 官方源码（yulichang/mybatis-plus-join）：
 *  - QueryJoin.java 每 join 关键字 18 重载（rightAlias 作用于右列所在表、ext 追加 ON 条件、
 *    MConsumer 子表渲染子查询 join）
 *  - QueryLabel.java selectCollection(prefix, child, dtoField[, map]) 与 free 形态（无子表类）
 *  - JoinAbstractLambdaWrapper.from(MFunction)、Join.around(firstSql, lastSql)（1.4.11+）、
 *    pageByMain()/setTableName(Function)
 */
class MpjJoinExtConverterTest {

    private val table = TableInfo(
        entityClassName = "DeviceInfoEntity",
        tableName = "car_device_info",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("stationId", "station_id"),
            ColumnInfo("shareSchemeId", "share_scheme_id"),
        ),
    )

    /** 关联表：station_info（psi 层 EntityRefArg 形态，带真实 TableInfo）。 */
    private val stationTable = TableInfo(
        entityClassName = "StationInfoEntity",
        tableName = "station_info",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("stationId", "station_id"),
            ColumnInfo("stationName", "station_name"),
            ColumnInfo("merchantId", "merchant_id"),
        ),
    )

    // ------------------------------------------------------------------
    // T-M21-02：join 5 参 rightAlias / 4 参 rightAlias / ext MFunction / 子表 MConsumer
    // ------------------------------------------------------------------

    @Test
    fun `mpjJoin_5参rightAlias右列别名`() {
        // T-M21-02：leftJoin(Class, "st", 左列, "t", 右列) → ON st.左列 = t.右列
        // （右别名 "t" 作用于右列所在表——主表；显式别名 "st" 作用于左列所在表——关联表）
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("leftJoin", listOf(
                    WrapperArg.EntityRefArg(stationTable),
                    WrapperArg.ValueArg(SqlValue.Literal("st")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                    WrapperArg.ValueArg(SqlValue.Literal("t")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN station_info st ON st.station_id = t.id
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjJoin_4参rightAlias右列别名`() {
        // T-M21-02：leftJoin(Class, 左列, "t", 右列) → ON 关联别名.左列 = t.右列
        // （与「四参别名」[Class, "rel", 左列, 右列] 按第 2 参类型区分：ColumnArg=右别名形态）
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("leftJoin", listOf(
                    WrapperArg.EntityRefArg(stationTable),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                    WrapperArg.ValueArg(SqlValue.Literal("t")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN station_info stationInfoEntity ON stationInfoEntity.station_id = t.id
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjJoin_ext函数追加ON条件`() {
        // T-M21-02：leftJoin(Class, 左列, 右列, ext -> ext.eq(...)) → ON 关联别名.左列 = 主别名.右列
        // + ext 内条件追加到 ON 条件组（复用 parseOnLambda：eq(关联表字段, 值) → 简单等值）
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("leftJoin", listOf(
                    WrapperArg.EntityRefArg(stationTable),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                    WrapperArg.NestedArg(WrapperChain(WrapperType.NESTED, null, listOf(
                        WrapperCall("eq", listOf(
                            WrapperArg.ColumnArg(ColumnRef(columnName = "station_name")),
                            WrapperArg.ValueArg(SqlValue.Literal("1")),
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
            LEFT JOIN station_info stationInfoEntity ON stationInfoEntity.id = t.station_id AND stationInfoEntity.station_name = 1
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjJoin_子表MConsumer子查询join`() {
        // T-M21-02：leftJoin(Class, t -> t.setAlias("tt").selectAll().ge(...), 左列, 右列)
        // → LEFT JOIN (SELECT ... FROM 子表 tt WHERE ...) tt ON tt.左列 = t.右列
        // 子查询用 QueryWrapperParser(子表 TableInfo) 解析嵌套链 compact 单行渲染；
        // 子别名取 lambda 内 setAlias 值，无则类名首字母小写
        val childTable = TableInfo(
            entityClassName = "OccupyRecordEntity",
            tableName = "occupy_record",
            columns = listOf(
                ColumnInfo("id", "id", isId = true),
                ColumnInfo("deviceId", "device_id"),
            ),
        )
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("leftJoin", listOf(
                    WrapperArg.EntityRefArg(childTable),
                    WrapperArg.NestedArg(WrapperChain(WrapperType.NESTED, null, listOf(
                        WrapperCall("setAlias", listOf(WrapperArg.ValueArg(SqlValue.Literal("tt")))),
                        WrapperCall("selectAll", emptyList()),
                        WrapperCall("ge", listOf(
                            WrapperArg.ColumnArg(ColumnRef(columnName = "device_id")),
                            WrapperArg.ValueArg(SqlValue.Literal("1")),
                        )),
                    ))),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "device_id")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                )),
            ),
        )
        val statement = MpjLambdaConverter.convert(chain, table) as SelectStatement
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN (SELECT tt.id, tt.device_id FROM occupy_record tt WHERE tt.device_id >= 1) tt ON tt.device_id = t.id
            """.trimIndent(),
            sql,
        )
        // 子查询形态不携带逻辑删除列（与 selectCollection 子查询族一致，T-M21-02 边界）
        assertNull("子查询 join 不注入副表 deleted", statement.joins.single().logicDeleteColumn)

        // 无 setAlias：子别名 = 类名首字母小写（occupyRecordEntity，全类名保留 Entity 后缀——项目既有惯例）
        val noAliasChain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("leftJoin", listOf(
                    WrapperArg.EntityRefArg(childTable),
                    WrapperArg.NestedArg(WrapperChain(WrapperType.NESTED, null, listOf(
                        WrapperCall("selectAll", emptyList()),
                        WrapperCall("ge", listOf(
                            WrapperArg.ColumnArg(ColumnRef(columnName = "device_id")),
                            WrapperArg.ValueArg(SqlValue.Literal("1")),
                        )),
                    ))),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "device_id")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                )),
            ),
        )
        val noAliasSql = SqlGenerator.generate(MpjLambdaConverter.convert(noAliasChain, table))
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN (SELECT occupyRecordEntity.id, occupyRecordEntity.device_id FROM occupy_record occupyRecordEntity WHERE occupyRecordEntity.device_id >= 1) occupyRecordEntity ON occupyRecordEntity.device_id = t.id
            """.trimIndent(),
            noAliasSql,
        )
    }

    // ------------------------------------------------------------------
    // T-M21-03：selectCollection prefix / free 形态
    // ------------------------------------------------------------------

    @Test
    fun `mpjJoin_selectCollectionPrefix子查询别名`() {
        // T-M21-03：selectCollection("consumption", Child.class, VO::getList, map) → 子查询表别名用 prefix
        // （prefix 形态判定以第 2 参为类名 ValueArg 为准；列映射 map 位置顺延）
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("selectCollection", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("consumption")),
                    WrapperArg.ValueArg(SqlValue.Literal("OccupyConsumptionRecordEntity")),
                    WrapperArg.AliasArg("consumptions"),
                    WrapperArg.NestedArg(WrapperChain(WrapperType.NESTED, null, listOf(
                        WrapperCall("id", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "id")))),
                        WrapperCall("result", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "ladder")))),
                        WrapperCall("result", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "fee")))),
                    ))),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN (SELECT id, ladder, fee FROM occupy_consumption_record_entity consumption) consumption
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjJoin_selectCollectionFree不产生子查询`() {
        // T-M21-03：selectCollection(VO::getList, map)（首参方法引用、无子表类）→ 结果映射标记不产生 SQL
        // （子表不可知，文档化边界；与 selectAssociation 同策略从 collectionCalls 剔除）
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("selectAll", listOf(WrapperArg.ValueArg(SqlValue.Literal("DeviceInfoEntity")))),
                WrapperCall("selectCollection", listOf(
                    WrapperArg.AliasArg("consumptions"),
                    WrapperArg.NestedArg(WrapperChain(WrapperType.NESTED, null, listOf(
                        WrapperCall("id", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "id")))),
                        WrapperCall("result", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "ladder")))),
                    ))),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertFalse("free 形态不产生 LEFT JOIN 子查询", sql.contains("LEFT JOIN"))
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjJoin_selectCollection类名形态回归`() {
        // T-M21-03 回归：既有 selectCollection(Child.class, VO::getList[, map]) 形态不变
        // （首参类名、子别名 = 类名首字母小写）
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("selectCollection", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("OccupyConsumptionRecordEntity")),
                    WrapperArg.AliasArg("consumptions"),
                    WrapperArg.NestedArg(WrapperChain(WrapperType.NESTED, null, listOf(
                        WrapperCall("id", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "id")))),
                        WrapperCall("result", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "ladder")))),
                        WrapperCall("result", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "fee")))),
                    ))),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            LEFT JOIN (SELECT id, ladder, fee FROM occupy_consumption_record_entity occupyConsumptionRecordEntity) occupyConsumptionRecordEntity
            """.trimIndent(),
            sql,
        )
    }

    // ------------------------------------------------------------------
    // T-M21-07：from(子查询主表)
    // ------------------------------------------------------------------

    @Test
    fun `mpjJoin_from子查询主表`() {
        // T-M21-07：from(w -> w.setAlias("tb").selectAll().ge(User::getId, 0).last("LIMIT 10"))
        // → FROM (SELECT tb.id, ... FROM user tb WHERE tb.id >= 0 LIMIT 10) t WHERE ...
        // 外层别名 = 主表别名；主表实体表名在子查询内部
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("from", listOf(
                    WrapperArg.NestedArg(WrapperChain(WrapperType.NESTED, null, listOf(
                        WrapperCall("setAlias", listOf(WrapperArg.ValueArg(SqlValue.Literal("tb")))),
                        WrapperCall("selectAll", emptyList()),
                        WrapperCall("ge", listOf(
                            WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                            WrapperArg.ValueArg(SqlValue.Literal("0")),
                        )),
                        WrapperCall("last", listOf(WrapperArg.ValueArg(SqlValue.Literal("LIMIT 10")))),
                    ))),
                )),
                WrapperCall("eq", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                    WrapperArg.ValueArg(SqlValue.Literal("1001")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM (SELECT tb.id, tb.station_id, tb.share_scheme_id FROM car_device_info tb WHERE tb.id >= 0 LIMIT 10) t
            WHERE t.id = 1001
            """.trimIndent(),
            sql,
        )
    }

    // ------------------------------------------------------------------
    // T-M21-09（部分）：around / setTableName / pageByMain
    // ------------------------------------------------------------------

    @Test
    fun `mpjJoin_around位置与参数替换`() {
        // T-M21-09：around 渲染于 ORDER BY 之后、LIMIT 之前；模板 {i} 绑定参数替换（复用 renderJoinSql 思路）
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("orderByAsc", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "id")))),
                WrapperCall("around", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("AND t.id > {0}")),
                    WrapperArg.ValueArg(SqlValue.Literal("5")),
                )),
                WrapperCall("last", listOf(WrapperArg.ValueArg(SqlValue.Literal("LIMIT 10")))),
            ),
        )
        val sql = SqlGenerator.generate(MpjLambdaConverter.convert(chain, table))
        assertEquals(
            """
            SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            ORDER BY t.id ASC
            AND t.id > 5
            LIMIT 10
            """.trimIndent(),
            sql,
        )
        // 位置断言：ORDER BY 之后、LIMIT 之前
        val orderIdx = sql.indexOf("ORDER BY")
        val aroundIdx = sql.indexOf("AND t.id > 5")
        val limitIdx = sql.indexOf("LIMIT 10")
        assertTrue("around 位于 ORDER BY 之后", aroundIdx > orderIdx)
        assertTrue("around 位于 LIMIT 之前", aroundIdx < limitIdx)
    }

    @Test
    fun `mpjJoin_around双字符串形态`() {
        // T-M21-09：官方签名 around(firstSql, lastSql)（Join.java，1.4.11+）——firstSql 拼 SQL 起始、
        // lastSql 拼语句末尾（LAST 段，ORDER BY 后、LIMIT 前）；本实现首参并入 rawFirst、第二参为 rawAround
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("around", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("/* around hint */")),
                    WrapperArg.ValueArg(SqlValue.Literal("FOR UPDATE")),
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
            /* around hint */ SELECT t.id, t.station_id, t.share_scheme_id
            FROM car_device_info t
            WHERE t.id = 1
            FOR UPDATE
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `mpjJoin_setTableName与pageByMain不破坏`() {
        // T-M21-09：setTableName(Function)（1.4.4+ 动态表名，lambda 静态不可求值 → no-op 文档化边界）
        // 与 pageByMain()（主表分页标记，不影响 SELECT 形态 → no-op）从解析链过滤，链不报错且 SQL 正常
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("selectAll", listOf(WrapperArg.ValueArg(SqlValue.Literal("DeviceInfoEntity")))),
                WrapperCall("setTableName", listOf(WrapperArg.ValueArg(SqlValue.Placeholder("DeviceInfoEntity::getTableName")))),
                WrapperCall("pageByMain", emptyList()),
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
            WHERE t.id = 1
            """.trimIndent(),
            sql,
        )
        assertTrue("setTableName/pageByMain 不产生额外 SQL 片段", !sql.contains("getTableName"))
    }
}
