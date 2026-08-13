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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
                AND name LIKE '%abc%'
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

    // ------------------------------------------------------------------
    // M12：动态条件（布尔重载 → toggle 元数据 + 判空语义 + 静态求值 + 启用过滤）
    // ------------------------------------------------------------------

    /** 便捷构造：eq(布尔标志占位符, name, 值) 动态条件。 */
    private fun flaggedEq(flag: String, value: SqlValue) = WrapperChain(
        WrapperType.LAMBDA_QUERY, "E",
        listOf(WrapperCall("eq", listOf(
            WrapperArg.ValueArg(SqlValue.Placeholder(flag)),
            WrapperArg.ColumnArg(ColumnRef(columnName = "name")),
            WrapperArg.ValueArg(value),
        ))),
    )

    @Test
    fun `布尔条件重载_toggle元数据`() {
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
                WrapperCall("eq", listOf(
                    WrapperArg.ValueArg(SqlValue.Placeholder("isActive")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "name")),
                    WrapperArg.ValueArg(SqlValue.Placeholder("nameVar")),
                )),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(3, statement.conditions.size)

        val t0 = (statement.conditions[0] as Condition.Simple).toggle!!
        assertEquals("c0", t0.id)
        assertEquals("true", t0.flagSource)
        assertTrue(t0.defaultEnabled)
        assertNull(t0.valueSource)
        assertEquals("id = 1", t0.label)

        val t1 = (statement.conditions[1] as Condition.Simple).toggle!!
        assertEquals("c1", t1.id)
        assertEquals("false", t1.flagSource)
        assertFalse(t1.defaultEnabled)
        assertEquals("station_id = 2", t1.label)

        val t2 = (statement.conditions[2] as Condition.Simple).toggle!!
        assertEquals("c2", t2.id)
        assertEquals("isActive", t2.flagSource)
        assertTrue(t2.defaultEnabled)
        assertEquals("nameVar", t2.valueSource)
        assertEquals("name = ?", t2.label)
    }

    @Test
    fun `布尔重载_defaultEnabled三态_运行时布尔一律默认勾选`() {
        fun toggle(flag: String) =
            parser.parse(flaggedEq(flag, SqlValue.Placeholder("name"))).conditions[0].toggle!!

        // M13 简化：不再识别判空语义——判空/判非 null 的运行时布尔表达式一律默认勾选（复选框纯手动）
        assertTrue(toggle("isActive").defaultEnabled)
        assertTrue(toggle("StringUtils.isNotBlank(name)").defaultEnabled)
        assertTrue(toggle("StrUtil.isNotEmpty(name)").defaultEnabled)
        assertTrue(toggle("name != null").defaultEnabled)
        assertTrue(toggle("Objects.nonNull(name)").defaultEnabled)
        assertTrue("判空方法表达式默认勾选", toggle("hasText(name)").defaultEnabled)

        // 字面量 true/false 仍按字面量定默认勾选态
        fun literalEq(flag: String) = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(WrapperCall("eq", listOf(
                WrapperArg.ValueArg(SqlValue.Literal(flag)),
                WrapperArg.ColumnArg(ColumnRef(columnName = "name")),
                WrapperArg.ValueArg(SqlValue.Literal("1")),
            ))),
        )
        assertTrue(parser.parse(literalEq("true")).conditions[0].toggle!!.defaultEnabled)
        assertFalse(parser.parse(literalEq("false")).conditions[0].toggle!!.defaultEnabled)
    }

    @Test
    fun `生成层_enabledIds过滤`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                WrapperCall("eq", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("true")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                    WrapperArg.ValueArg(SqlValue.Literal("1")),
                )),
                WrapperCall("eq", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "name")),
                    WrapperArg.ValueArg(SqlValue.Literal("'张三'", "张三")),
                )),
                WrapperCall("eq", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("false")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                    WrapperArg.ValueArg(SqlValue.Literal("2")),
                )),
            ),
        )
        val statement = parser.parse(chain)

        // 默认（enabledIds=null）：eq(true) 保留、eq(false) 排除、普通条件保留 → 与 M11 基线一致
        val base = SqlGenerator.generate(statement)
        assertTrue(base.contains("WHERE id = 1"))
        assertTrue(base.contains("AND name = '张三'"))
        assertFalse("false 条件默认不应拼接", base.contains("station_id = 2"))

        // 显式启用 c0+c2（M19.6：普通条件 name 也占 id c1）→ 三个条件都在
        val all = SqlGenerator.generate(statement, compact = false, enabledConditionIds = setOf("c0", "c2"))
        assertTrue(all.contains("AND station_id = 2"))

        // 全禁用（空集合）→ 所有条件均关闭（M19.6：普通条件同样挂 toggle 可勾选，原"普通条件恒保留"语义扩展）
        val none = SqlGenerator.generate(statement, compact = false, enabledConditionIds = emptySet())
        assertFalse(none.contains("id = 1"))
        assertFalse(none.contains("name = '张三'"))
        assertFalse(none.contains("station_id = 2"))
    }

    @Test
    fun `嵌套组内动态条件_toggle与整组跳过`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                WrapperCall("eq", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("true")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                    WrapperArg.ValueArg(SqlValue.Literal("1")),
                )),
                WrapperCall("and", listOf(
                    WrapperArg.NestedArg(WrapperChain(WrapperType.NESTED, "E", listOf(
                        WrapperCall("eq", listOf(
                            WrapperArg.ValueArg(SqlValue.Literal("false")),
                            WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                            WrapperArg.ValueArg(SqlValue.Literal("2")),
                        )),
                    ))),
                )),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(2, statement.conditions.size)
        val group = statement.conditions[1] as Condition.Group
        val nested = group.children[0] as Condition.Simple
        assertEquals("c1", nested.toggle?.id)
        assertFalse(nested.toggle?.defaultEnabled!!)

        // 默认渲染：组内 false 条件排除 → 组空 → 整组不渲染（无空括号）
        val sql = SqlGenerator.generate(statement)
        assertTrue(sql.contains("WHERE id = 1"))
        assertFalse(sql.contains("("))
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

    // ------------------------------------------------------------------
    // T-M20-09：eqOrIsNull（MP 3.5.17 官方 API）
    // ------------------------------------------------------------------

    @Test
    fun `eqOrIsNull_值为null生成IS_NULL`() {
        // T-M20-09：eqOrIsNull(col, null) → col IS NULL
        // （psi 层 Java null 字面量经 ExpressionEvaluator 求值为 SqlValue.Literal("NULL")）
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("eqOrIsNull", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                    WrapperArg.ValueArg(SqlValue.Literal("NULL")),
                )),
            ),
        )
        val statement = parser.parse(chain)
        val cond = statement.conditions[0] as Condition.Unary
        assertEquals("station_id", cond.column.columnName)
        assertEquals("IS NULL", cond.op)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE station_id IS NULL
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `eqOrIsNull_值非null生成等于`() {
        // T-M20-09：eqOrIsNull(col, val) 值非 null → col = 值（与 eq 同语义）
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("eqOrIsNull", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                    WrapperArg.ValueArg(SqlValue.Literal("101")),
                )),
            ),
        )
        val statement = parser.parse(chain)
        val cond = statement.conditions[0] as Condition.Simple
        assertEquals("=", cond.op)
        assertEquals("101", (cond.value as SqlValue.Literal).raw)

        // 运行时值（占位符）→ `= ?`（运行时才能确定是否 null）
        val placeholderChain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("eqOrIsNull", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                    WrapperArg.ValueArg(SqlValue.Placeholder("stationId")),
                )),
            ),
        )
        val placeholderSql = SqlGenerator.generate(parser.parse(placeholderChain))
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE station_id = ?
            """.trimIndent(),
            placeholderSql,
        )
    }

    @Test
    fun `eqOrIsNull_布尔重载形态`() {
        // T-M20-09：eqOrIsNull(condition, col, val) 布尔重载——true 保留、false 整体忽略（与 eq 一致）
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("eqOrIsNull", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("true")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                    WrapperArg.ValueArg(SqlValue.Literal("NULL")),
                )),
                WrapperCall("eqOrIsNull", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("false")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "share_scheme_id")),
                    WrapperArg.ValueArg(SqlValue.Literal("NULL")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(parser.parse(chain))
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE station_id IS NULL
            """.trimIndent(),
            sql,
        )
    }

    // ------------------------------------------------------------------
    // T-M20-04：MPJ 字符串形态 groupByStr / orderByStr
    // ------------------------------------------------------------------

    @Test
    fun `groupByStr_字符串列原样`() {
        // T-M20-04：groupByStr（MPJ 字符串版 groupBy）——字符串列原样（可含表别名，不补前缀）
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("groupByStr", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("t.station_id")),
                    WrapperArg.ValueArg(SqlValue.Literal("t.share_scheme_id")),
                )),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(listOf("t.station_id", "t.share_scheme_id"), statement.groupBy.map { it.columnName })
        val sql = SqlGenerator.generate(statement)
        assertTrue("GROUP BY t.station_id, t.share_scheme_id", sql.contains("GROUP BY t.station_id, t.share_scheme_id"))
    }

    @Test
    fun `orderByStr_字符串列带别名默认ASC`() {
        // T-M20-04：orderByStr（MPJ 字符串版 orderBy）——字符串列原样、默认 ASC（MPJ 语义：恒升序）
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("orderByStr", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("t.create_time")),
                    WrapperArg.ValueArg(SqlValue.Literal("t.id")),
                )),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(2, statement.orderBy.size)
        assertEquals("t.create_time", statement.orderBy[0].column.columnName)
        assertTrue(statement.orderBy[0].asc)
        val sql = SqlGenerator.generate(statement)
        assertTrue("ORDER BY t.create_time ASC, t.id ASC", sql.contains("ORDER BY t.create_time ASC, t.id ASC"))
    }

    // ------------------------------------------------------------------
    // T-M21-04：allEq（MP 官方 API，6 重载）
    // ------------------------------------------------------------------

    @Test
    fun `allEq_Map字面量展开为等值条件`() {
        // T-M21-04：allEq(Map.of(k, v)) 静态展开 → 多条 `col = ?` AND 连接
        // （psi 层 Map.of 调用经 ArgResolver 解析为 MapArg.entries）
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("allEq", listOf(
                    WrapperArg.MapArg(
                        entries = listOf(
                            ColumnRef(columnName = "name") to SqlValue.Literal("'device'"),
                            ColumnRef(columnName = "station_id") to SqlValue.Literal("1001"),
                        ),
                        null2IsNull = false,
                    ),
                )),
            ),
        )
        val sql = SqlGenerator.generate(parser.parse(chain))
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE (name = 'device' AND station_id = 1001)
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `allEq_null2IsNull为true时null值渲染IS_NULL`() {
        // T-M21-04：allEq(Map, true) → 可判 null 的值渲染 `col IS NULL`（与 eqOrIsNull 同判定：Literal raw == "NULL"）
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("allEq", listOf(
                    WrapperArg.MapArg(
                        entries = listOf(
                            ColumnRef(columnName = "share_scheme_id") to SqlValue.Literal("NULL"),
                            ColumnRef(columnName = "station_id") to SqlValue.Literal("1001"),
                        ),
                        null2IsNull = true,
                    ),
                )),
            ),
        )
        val sql = SqlGenerator.generate(parser.parse(chain))
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE (share_scheme_id IS NULL AND station_id = 1001)
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `allEq_变量Map渲染可读占位`() {
        // T-M21-04：非 Map.of 的 Map 变量（entries 空）→ 可读占位条件（运行时条目不可静态展开）
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("allEq", listOf(
                    WrapperArg.MapArg(entries = emptyList(), null2IsNull = false),
                )),
            ),
        )
        val sql = SqlGenerator.generate(parser.parse(chain))
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE <Map 等值条件: col = ? AND col = ? ...>
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `allEq_布尔重载_true保留false忽略`() {
        // T-M21-04：allEq(boolean, Map) 首布尔参剥离——true 保留、false 整体忽略（与 eq 布尔重载一致）
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("allEq", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("true")),
                    WrapperArg.MapArg(
                        entries = listOf(ColumnRef(columnName = "id") to SqlValue.Literal("1")),
                        null2IsNull = false,
                    ),
                )),
                WrapperCall("allEq", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("false")),
                    WrapperArg.MapArg(
                        entries = listOf(ColumnRef(columnName = "station_id") to SqlValue.Literal("2")),
                        null2IsNull = false,
                    ),
                )),
            ),
        )
        val sql = SqlGenerator.generate(parser.parse(chain))
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE (id = 1)
            """.trimIndent(),
            sql,
        )
    }

    // ------------------------------------------------------------------
    // T-M21-05：setEntity（MP 官方 API）
    // ------------------------------------------------------------------

    @Test
    fun `setEntity_展开实体全部列等值条件`() {
        // T-M21-05：setEntity(entity) 静态近似——实体全部列 `col = ?`（运行时字段值不可知，
        // 官方语义为非 null 字段追加等值条件）
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("setEntity", listOf(WrapperArg.ValueArg(SqlValue.Placeholder("entity")))),
            ),
        )
        val sql = SqlGenerator.generate(parser.parse(chain))
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE id = ?
                AND station_id = ?
                AND share_scheme_id = ?
                AND name = ?
            """.trimIndent(),
            sql,
        )
    }

    // ------------------------------------------------------------------
    // T-M21-01：selectFilter / select(Class, Predicate) 谓词过滤列
    // ------------------------------------------------------------------

    @Test
    fun `selectFilter_黑名单过滤列`() {
        // T-M21-01：`t -> !t.getProperty().equals("xxx")` 黑名单形态——exclude 生效
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("selectFilter", listOf(
                    WrapperArg.EntityRefArg(table),
                    WrapperArg.PredicateArg(include = emptyList(), exclude = listOf("share_scheme_id", "name")),
                )),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(listOf("id", "station_id"), statement.selectColumns.map { it.columnName })
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id
            FROM car_device_info
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `selectFilter_白名单过滤列`() {
        // T-M21-01：`t -> t.getColumn().equals("col_name")` 白名单形态——include 生效
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("selectFilter", listOf(
                    WrapperArg.EntityRefArg(table),
                    WrapperArg.PredicateArg(include = listOf("id", "name"), exclude = emptyList()),
                )),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(listOf("id", "name"), statement.selectColumns.map { it.columnName })
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, name
            FROM car_device_info
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `selectFilter_空谓词回退全列`() {
        // T-M21-01：谓词无法静态分析（include/exclude 均空）→ 回退全列
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall("selectFilter", listOf(
                    WrapperArg.EntityRefArg(table),
                    WrapperArg.PredicateArg(include = emptyList(), exclude = emptyList()),
                )),
            ),
        )
        val sql = SqlGenerator.generate(parser.parse(chain))
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `select_Class谓词形态过滤列`() {
        // T-M21-01：select(Class, Predicate)（MP 3.4.3.2+）→ 同过滤逻辑；非该形态保持原列解析
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("select", listOf(
                    WrapperArg.EntityRefArg(table),
                    WrapperArg.PredicateArg(include = listOf("station_id"), exclude = emptyList()),
                )),
            ),
        )
        val statement = parser.parse(chain)
        assertEquals(listOf("station_id"), statement.selectColumns.map { it.columnName })
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT station_id
            FROM car_device_info
            """.trimIndent(),
            sql,
        )
    }

    // ------------------------------------------------------------------
    // T-M21-06：selectFunc(DefaultFuncEnum) 枚举形态
    // ------------------------------------------------------------------

    @Test
    fun `selectFunc_枚举形态映射内置函数`() {
        // T-M21-06：selectFunc(DefaultFuncEnum.SUM, col, alias) → SUM(col) AS alias
        // （psi 层枚举实参求值为 Placeholder，source 形如 "DefaultFuncEnum.SUM"）
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("selectFunc", listOf(
                    WrapperArg.ValueArg(SqlValue.Placeholder("DefaultFuncEnum.SUM")),
                    WrapperArg.FuncArg(columns = listOf(ColumnRef(columnName = "id")), values = emptyList()),
                    WrapperArg.ValueArg(SqlValue.Literal("totalCount")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(parser.parse(chain))
        assertEquals(
            """
            SELECT SUM(id) AS totalCount
            FROM car_device_info
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `selectFunc_未知枚举忽略不崩溃`() {
        // T-M21-06：未知枚举（映射表外）→ 忽略该列（不崩溃），其余列正常渲染
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("selectFunc", listOf(
                    WrapperArg.ValueArg(SqlValue.Placeholder("DefaultFuncEnum.UNKNOWN_FUNC")),
                    WrapperArg.FuncArg(columns = listOf(ColumnRef(columnName = "id")), values = emptyList()),
                    WrapperArg.ValueArg(SqlValue.Literal("x")),
                )),
                WrapperCall("selectFunc", listOf(
                    WrapperArg.ValueArg(SqlValue.Placeholder("LEN")),
                    WrapperArg.FuncArg(columns = listOf(ColumnRef(columnName = "name")), values = emptyList()),
                    WrapperArg.ValueArg(SqlValue.Literal("nameLen")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(parser.parse(chain))
        assertEquals(
            """
            SELECT LENGTH(name) AS nameLen
            FROM car_device_info
            """.trimIndent(),
            sql,
        )
    }

    // ------------------------------------------------------------------
    // T-M21-09：inIfNotEmpty / notInIfNotEmpty（MPJ 1.5.6+）
    // ------------------------------------------------------------------

    @Test
    fun `inIfNotEmpty归一化为IN条件`() {
        // T-M21-09：inIfNotEmpty/notInIfNotEmpty（集合非空才生效的运行时语义）→ 剥离后缀归一为 in/notIn
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("inIfNotEmpty", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                    WrapperArg.ValueArg(SqlValue.Literal("1")),
                    WrapperArg.ValueArg(SqlValue.Literal("2")),
                )),
                WrapperCall("notInIfNotEmpty", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                    WrapperArg.ValueArg(SqlValue.Literal("3")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(parser.parse(chain))
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, name
            FROM car_device_info
            WHERE id IN (1, 2)
                AND station_id NOT IN (3)
            """.trimIndent(),
            sql,
        )
    }
}
