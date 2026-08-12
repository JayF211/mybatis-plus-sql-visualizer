package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QueryWrapperParser 单元测试（M1-03）：验证调用链 → SELECT 语句模型。
 */
class QueryWrapperParserTest {

    private val table = TableInfo(
        entityClassName = "DeviceInfoEntity",
        tableName = "car_device_info",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("stationId", "station_id"),
            ColumnInfo("shareSchemeId", "share_scheme_id"),
            ColumnInfo("name", "name"),
        ),
    )

    private val parser = QueryWrapperParser(table)

    /** 便捷构造：eq(column, value)。 */
    private fun eqCall(column: String, value: String) = WrapperCall(
        "eq",
        listOf(WrapperArg.ColumnArg(ColumnRef(columnName = column)), WrapperArg.ValueArg(SqlValue.Literal(value))),
    )

    // ------------------------------------------------------------------
    // 布尔条件重载 / 无参 or() / apply（清单 P02/P10/P14）
    // ------------------------------------------------------------------

    @Test
    fun `布尔条件重载_字面量true保留_false忽略`() {
        // P02：eq(true, col, val) 保留；eq(false, col, val) 整体忽略
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                WrapperCall("eq", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("true")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                    WrapperArg.ValueArg(SqlValue.Literal("1")),
                )),
                WrapperCall("eq", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("false")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                    WrapperArg.ValueArg(SqlValue.Literal("2")),
                )),
                WrapperCall("like", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("true")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "name")),
                    WrapperArg.ValueArg(SqlValue.Literal("abc")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(parser.parse(chain))
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE id = 1
                AND name LIKE %abc%
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `无参or分隔符`() {
        // P14：eq(a).or().eq(b) → (a OR b)
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                eqCall("id", "1"),
                WrapperCall("or", emptyList()),
                eqCall("id", "2"),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(1, statement.conditions.size)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE (id = 1 OR id = 2)
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `apply参数化片段`() {
        // P10：apply("1 = 0") 原样透传；apply(condition, "(rel.a, rel.id) > ({0}, {1})", a, b) 参数替换
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                WrapperCall("apply", listOf(WrapperArg.ValueArg(SqlValue.Literal("1 = 0")))),
                WrapperCall("apply", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("true")),
                    WrapperArg.ValueArg(SqlValue.Literal("(rel.apply_id, rel.id) > ({0}, {1})")),
                    WrapperArg.ValueArg(SqlValue.Literal("100")),
                    WrapperArg.ValueArg(SqlValue.Literal("0")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(parser.parse(chain))
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE 1 = 0
                AND (rel.apply_id, rel.id) > (100, 0)
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `apply字面量false忽略`() {
        // P10：apply(false, "sql", ...) 条件禁用
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                WrapperCall("apply", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("false")),
                    WrapperArg.ValueArg(SqlValue.Literal("1 = 0")),
                )),
                eqCall("id", "1"),
            ),
        )
        val sql = SqlGenerator.generate(parser.parse(chain))
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE id = 1
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `空调用链_生成全列查询`() {
        val chain = WrapperChain(WrapperType.LAMBDA_QUERY, "DeviceInfoEntity", emptyList())
        val statement = parser.parse(chain)
        assertTrue(statement.selectColumns.isEmpty())
        assertTrue(statement.conditions.isEmpty())
        assertEquals("car_device_info", statement.table.tableName)
    }

    @Test
    fun `条件解析_比较与空值`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY,
            "DeviceInfoEntity",
            listOf(
                eqCall("id", "1001"),
                WrapperCall("gt", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")), WrapperArg.ValueArg(SqlValue.Literal("0")))),
                WrapperCall("isNotNull", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "share_scheme_id")))),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(3, statement.conditions.size)
        val first = statement.conditions[0] as Condition.Simple
        assertEquals("id", first.column.columnName)
        assertEquals("=", first.op)
        assertEquals("1001", (first.value as SqlValue.Literal).raw)
        val third = statement.conditions[2] as Condition.Unary
        assertEquals("IS NOT NULL", third.op)
    }

    @Test
    fun `范围解析_in与between`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY,
            "DeviceInfoEntity",
            listOf(
                WrapperCall(
                    "in",
                    listOf(
                        WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                        WrapperArg.ValueArg(SqlValue.Literal("1")),
                        WrapperArg.ValueArg(SqlValue.Literal("2")),
                    ),
                ),
                WrapperCall(
                    "between",
                    listOf(
                        WrapperArg.ColumnArg(ColumnRef(columnName = "sort")),
                        WrapperArg.ValueArg(SqlValue.Literal("1")),
                        WrapperArg.ValueArg(SqlValue.Literal("10")),
                    ),
                ),
            ),
        )
        val statement = parser.parse(chain)
        val inCond = statement.conditions[0] as Condition.In
        assertEquals(listOf("1", "2"), inCond.values.map { (it as SqlValue.Literal).raw })
        val betweenCond = statement.conditions[1] as Condition.Between
        assertEquals("1", (betweenCond.lo as SqlValue.Literal).raw)
        assertEquals("10", (betweenCond.hi as SqlValue.Literal).raw)
    }

    @Test
    fun `like解析_位置与取反`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY,
            "DeviceInfoEntity",
            listOf(
                WrapperCall(
                    "likeLeft",
                    listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "name")), WrapperArg.ValueArg(SqlValue.Literal("张"))),
                ),
                WrapperCall(
                    "notLike",
                    listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "name")), WrapperArg.ValueArg(SqlValue.Literal("李"))),
                ),
            ),
        )
        val statement = parser.parse(chain)
        val like = statement.conditions[0] as Condition.Like
        assertEquals(LikePosition.LEFT, like.position)
        val notLike = statement.conditions[1] as Condition.Like
        assertTrue(notLike.negated)
        assertEquals(LikePosition.ANYWHERE, notLike.position)
    }

    @Test
    fun `逻辑分组_and或嵌套`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY,
            "DeviceInfoEntity",
            listOf(
                eqCall("id", "1"),
                WrapperCall(
                    "or",
                    listOf(
                        WrapperArg.NestedArg(
                            WrapperChain(
                                WrapperType.NESTED,
                                "DeviceInfoEntity",
                                listOf(
                                    eqCall("station_id", "101"),
                                    eqCall("share_scheme_id", "5"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(2, statement.conditions.size)
        val group = statement.conditions[1] as Condition.Group
        assertEquals(Connector.OR, group.connector)
        assertEquals(2, group.children.size)
    }

    @Test
    fun `结构解析_select排序分页`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY,
            "DeviceInfoEntity",
            listOf(
                WrapperCall(
                    "select",
                    listOf(
                        WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                        WrapperArg.ColumnArg(ColumnRef(columnName = "share_scheme_id")),
                    ),
                ),
                WrapperCall("orderByDesc", listOf(WrapperArg.ColumnArg(ColumnRef(columnName = "sort")))),
                WrapperCall(
                    "last",
                    listOf(WrapperArg.ValueArg(SqlValue.Literal("limit 10"))),
                ),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(listOf("station_id", "share_scheme_id"), statement.selectColumns.map { it.columnName })
        assertEquals(1, statement.orderBy.size)
        assertEquals("sort", statement.orderBy[0].column.columnName)
        assertEquals(false, statement.orderBy[0].asc)
        assertEquals(LimitClause(count = 10), statement.limit)
    }

    @Test
    fun `last带偏移量分页解析`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY,
            "DeviceInfoEntity",
            listOf(
                WrapperCall("last", listOf(WrapperArg.ValueArg(SqlValue.Literal("limit 5, 10")))),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(LimitClause(offset = 5, count = 10), statement.limit)
    }

    @Test
    fun `未知方法_安全忽略`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY,
            "DeviceInfoEntity",
            listOf(
                WrapperCall("someUnknownMethod", listOf(WrapperArg.ValueArg(SqlValue.Literal("x")))),
                eqCall("id", "1"),
            ),
        )
        val statement = parser.parse(chain)
        // 未知方法不抛异常且不影响后续条件
        assertEquals(1, statement.conditions.size)
    }

    @Test
    fun `字符串列名参数_兼容字符串版`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY,
            "DeviceInfoEntity",
            listOf(
                WrapperCall(
                    "eq",
                    listOf(WrapperArg.ValueArg(SqlValue.Literal("share_scheme_id")), WrapperArg.ValueArg(SqlValue.Literal("5"))),
                ),
            ),
        )
        val statement = parser.parse(chain)
        val cond = statement.conditions[0] as Condition.Simple
        assertEquals("share_scheme_id", cond.column.columnName)
    }
}
