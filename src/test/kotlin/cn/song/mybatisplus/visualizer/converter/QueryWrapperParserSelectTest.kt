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
 * QueryWrapperParser 的 SELECT 列/字符串列名/eqOrIsNull/allEq/setEntity 行为单元测试（自 QueryWrapperParserTest 拆分）。
 * （T-M53-II 2026-10-10：逐字符原样搬运，零逻辑改动。）
 */
class QueryWrapperParserSelectTest {

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
    fun `selectFunc_枚举形态关键字列引用`() {
        // T-M23.5：selectFunc 枚举形态（解析期拼 rawExpr）——关键字列按方言引用（SUM(`table`)）
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "DeviceInfoEntity",
            listOf(
                WrapperCall("selectFunc", listOf(
                    WrapperArg.ValueArg(SqlValue.Placeholder("DefaultFuncEnum.SUM")),
                    WrapperArg.FuncArg(columns = listOf(ColumnRef(columnName = "table")), values = emptyList()),
                    WrapperArg.ValueArg(SqlValue.Literal("totalCount")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(parser.parse(chain))
        assertEquals(
            """
            SELECT SUM(`table`) AS totalCount
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
}
