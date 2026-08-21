package cn.song.mybatisplus.visualizer.reverse

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator
import cn.song.mybatisplus.visualizer.reverse.SqlReverseParser
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反向链路 round-trip 闭环测试（P0）：SqlGenerator 规范形 SQL → [SqlReverseParser] 反解
 * → 重组 [SelectStatement] → SqlGenerator 再渲染，两次输出逐字符相等。
 *
 * 这验证解析器对插件自身规范形 SQL 的完整覆盖（语义自洽），是反向功能正确性的核心保障。
 */
class SqlToJavaRoundTripTest {

    /** 测试用表（selectColumns 显式指定，列清单不参与条件断言）。 */
    private val table = TableInfo(entityClassName = "User", tableName = "user", columns = emptyList())

    /** 模型 → SQL → 反解 → 模型 → SQL，断言两次 SQL 相等。 */
    private fun roundTrip(statement: SelectStatement): String {
        val sql1 = SqlGenerator.generate(statement)
        val parsed = SqlReverseParser.parse(sql1)
        val rebuilt = SelectStatement(
            table = table,
            selectColumns = parsed.selectColumns?.map { ColumnRef(columnName = it.expr) } ?: listOf(ColumnRef(columnName = "id")),
            conditions = parsed.conditions,
            orderBy = parsed.orderBy,
            limit = parsed.limit,
        )
        val sql2 = SqlGenerator.generate(rebuilt)
        assertEquals("round-trip SQL 不一致", sql1, sql2)
        return sql1
    }

    private fun statement(vararg conditions: Condition): SelectStatement = SelectStatement(
        table = table,
        selectColumns = listOf(ColumnRef(columnName = "id")),
        conditions = conditions.toList(),
    )

    @Test
    fun `无WHERE_空语句闭环`() {
        roundTrip(SelectStatement(table = table, selectColumns = listOf(ColumnRef(columnName = "id"))))
    }

    @Test
    fun `比较操作符闭环`() {
        roundTrip(
            statement(
                Condition.Simple(ColumnRef(columnName = "a"), "=", SqlValue.Literal("1")),
                Condition.Simple(ColumnRef(columnName = "b"), "!=", SqlValue.Literal("'x'", "x")),
                Condition.Simple(ColumnRef(columnName = "c"), ">", SqlValue.Literal("2")),
                Condition.Simple(ColumnRef(columnName = "d"), ">=", SqlValue.Literal("3")),
                Condition.Simple(ColumnRef(columnName = "e"), "<", SqlValue.Literal("4")),
                Condition.Simple(ColumnRef(columnName = "f"), "<=", SqlValue.Literal("5")),
            ),
        )
    }

    @Test
    fun `一元与集合条件闭环`() {
        roundTrip(
            statement(
                Condition.Unary(ColumnRef(columnName = "a"), "IS NULL"),
                Condition.Unary(ColumnRef(columnName = "b"), "IS NOT NULL"),
                Condition.In(ColumnRef(columnName = "c"), negated = false, listOf(SqlValue.Literal("1"), SqlValue.Literal("'s'", "s"))),
                Condition.In(ColumnRef(columnName = "d"), negated = true, listOf(SqlValue.Literal("'p'", "p"))),
                Condition.Between(ColumnRef(columnName = "e"), negated = false, SqlValue.Literal("'2024-01-01'", "2024-01-01"), SqlValue.Literal("'2024-01-31'", "2024-01-31")),
                Condition.Between(ColumnRef(columnName = "f"), negated = true, SqlValue.Literal("1"), SqlValue.Literal("2")),
            ),
        )
    }

    @Test
    fun `LIKE四位置闭环_正反向等价`() {
        val sql1 = roundTrip(
            statement(
                Condition.Like(ColumnRef(columnName = "a"), negated = false, SqlValue.Literal("'%abc%'", "abc"), LikePosition.ANYWHERE),
                Condition.Like(ColumnRef(columnName = "b"), negated = false, SqlValue.Literal("'abc%'", "abc"), LikePosition.RIGHT),
                Condition.Like(ColumnRef(columnName = "c"), negated = false, SqlValue.Literal("'%abc'", "abc"), LikePosition.LEFT),
                Condition.Like(ColumnRef(columnName = "d"), negated = true, SqlValue.Literal("'%abc%'", "abc"), LikePosition.ANYWHERE),
            ),
        )
        // LIKE 渲染语义抽查：ANYWHERE 双侧 %、RIGHT 右侧 %（user 为 MySQL 保留字 → 反引号引用）
        assertEquals("SELECT id\nFROM `user`\nWHERE a LIKE '%abc%'\n    AND b LIKE 'abc%'\n    AND c LIKE '%abc'\n    AND d NOT LIKE '%abc%'", sql1)
    }

    @Test
    fun `LIKE降级Raw_原样透传闭环`() {
        val sql1 = SqlGenerator.generate(statement(Condition.Raw("a LIKE 'a_b'")))
        val parsed = SqlReverseParser.parse(sql1)
        assertEquals("a LIKE 'a_b'", (parsed.conditions.single() as Condition.Raw).sqlText)
        assertEquals(1, parsed.warnings.size)
        val sql2 = SqlGenerator.generate(
            SelectStatement(
                table = table,
                selectColumns = listOf(ColumnRef(columnName = "id")),
                conditions = parsed.conditions,
            ),
        )
        assertEquals(sql1, sql2)
    }

    @Test
    fun `ORDER BY与LIMIT闭环`() {
        val sql1 = roundTrip(
            SelectStatement(
                table = table,
                selectColumns = listOf(ColumnRef(columnName = "id")),
                conditions = listOf(Condition.Simple(ColumnRef(columnName = "a"), "=", SqlValue.Literal("1"))),
                orderBy = listOf(
                    OrderByItem(ColumnRef(columnName = "create_time"), asc = false),
                    OrderByItem(ColumnRef(columnName = "id"), asc = true),
                ),
                limit = LimitClause(count = SqlValue.Literal("10")),
            ),
        )
        assertEquals("SELECT id\nFROM `user`\nWHERE a = 1\nORDER BY create_time DESC, id ASC\nLIMIT 10", sql1)
    }

    @Test
    fun `LIMIT偏移形态闭环`() {
        roundTrip(
            SelectStatement(
                table = table,
                selectColumns = listOf(ColumnRef(columnName = "id")),
                limit = LimitClause(offset = SqlValue.Literal("5"), count = SqlValue.Literal("10")),
            ),
        )
    }

    // ------------------------------------------------------------------
    // 组闭环（T-M36.1）
    // ------------------------------------------------------------------

    @Test
    fun `括号组_逐字符闭环`() {
        val sql1 = roundTrip(
            SelectStatement(
                table = table,
                selectColumns = listOf(ColumnRef(columnName = "id")),
                conditions = listOf(
                    Condition.Group(
                        Connector.OR,
                        listOf(
                            Condition.Simple(ColumnRef(columnName = "a"), "=", SqlValue.Literal("1")),
                            Condition.Simple(ColumnRef(columnName = "b"), "=", SqlValue.Literal("2")),
                        ),
                    ),
                    Condition.Simple(ColumnRef(columnName = "c"), "=", SqlValue.Literal("'x'", "x")),
                ),
            ),
        )
        assertEquals("SELECT id\nFROM `user`\nWHERE (a = 1 OR b = 2)\n    AND c = 'x'", sql1)
    }

    @Test
    fun `AND组与NOT组_逐字符闭环`() {
        val sql1 = roundTrip(
            SelectStatement(
                table = table,
                selectColumns = listOf(ColumnRef(columnName = "id")),
                conditions = listOf(
                    Condition.Group(
                        Connector.AND,
                        listOf(
                            Condition.Simple(ColumnRef(columnName = "a"), "=", SqlValue.Literal("1")),
                            Condition.Simple(ColumnRef(columnName = "b"), "=", SqlValue.Literal("2")),
                        ),
                    ),
                    Condition.Group(
                        Connector.OR,
                        listOf(
                            Condition.Simple(ColumnRef(columnName = "d"), "=", SqlValue.Literal("4")),
                            Condition.Simple(ColumnRef(columnName = "e"), "=", SqlValue.Literal("5")),
                        ),
                        negated = true,
                    ),
                ),
            ),
        )
        assertEquals("SELECT id\nFROM `user`\nWHERE (a = 1 AND b = 2)\n    AND NOT (d = 4 OR e = 5)", sql1)
    }

    @Test
    fun `顶层OR_语义等价闭环`() {
        // 顶层裸 OR 建模为 Group(OR)（渲染会带括号——SQL 语义等价，round-trip 断言结构一致）
        val parsed = SqlReverseParser.parse("SELECT * FROM `user` WHERE a = 1 OR b = 2")
        val group = parsed.conditions.single() as Condition.Group
        assertEquals(Connector.OR, group.connector)
        // 模型 → SQL 渲染（带括号形态）
        val sql = SqlGenerator.generate(
            SelectStatement(
                table = table,
                selectColumns = listOf(ColumnRef(columnName = "id")),
                conditions = parsed.conditions,
            ),
        )
        assertEquals("SELECT id\nFROM `user`\nWHERE (a = 1 OR b = 2)", sql)
        // 再反解 → 结构一致（稳定不动点）
        val reparsed = SqlReverseParser.parse(sql)
        assertEquals(parsed.conditions, reparsed.conditions)
    }

    @Test
    fun `降级Raw_函数与子查询透传闭环`() {
        for (sqlText in listOf(
            "SELECT * FROM `user` WHERE DATE_FORMAT(create_time, '%Y') = '2024'",
            "SELECT * FROM `user` WHERE a IN (SELECT id FROM u)",
        )) {
            val parsed = SqlReverseParser.parse(sqlText)
            val raw = parsed.conditions.single() as Condition.Raw
            assertEquals(1, parsed.warnings.size)
            val sql2 = SqlGenerator.generate(
                SelectStatement(
                    table = table,
                    selectColumns = listOf(ColumnRef(columnName = "id")),
                    conditions = parsed.conditions,
                ),
            )
            assertEquals("SELECT id\nFROM `user`\nWHERE ${raw.sqlText}", sql2)
        }
    }

    @Test
    fun `参数占位符_逐字符闭环`() {
        // `?` 占位符：反向建模 Placeholder（不再降级 Raw），正向渲染 `?` → 反解结构一致（稳定不动点）
        val sql1 = SqlGenerator.generate(
            SelectStatement(
                table = table,
                selectColumns = listOf(ColumnRef(columnName = "id")),
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "a"), "=", SqlValue.Placeholder("?")),
                    Condition.In(ColumnRef(columnName = "b"), false, listOf(SqlValue.Placeholder("?"), SqlValue.Placeholder("?"))),
                ),
            ),
        )
        assertEquals("SELECT id\nFROM `user`\nWHERE a = ?\n    AND b IN (?, ?)", sql1)
        val reparsed = SqlReverseParser.parse(sql1)
        val first = reparsed.conditions[0] as Condition.Simple
        assertTrue(first.value is SqlValue.Placeholder)
        val second = reparsed.conditions[1] as Condition.In
        assertTrue(second.values.all { it is SqlValue.Placeholder })
        assertEquals(0, reparsed.warnings.size)
    }

    // ------------------------------------------------------------------
    // 多表 JOIN 闭环（T-M37）
    // ------------------------------------------------------------------

    @Test
    fun `LEFT JOIN单等值_逐字符闭环`() {
        val sql1 = SqlGenerator.generate(
            SelectStatement(
                table = table,
                selectColumns = listOf(ColumnRef(columnName = "id")),
                tableAlias = "t",
                joins = listOf(
                    JoinClause(
                        type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                        tableName = "address",
                        alias = "t1",
                        onConditions = listOf(
                            Condition.ColumnComparison(
                                ColumnRef(tableAlias = "t1", columnName = "user_id"),
                                ColumnRef(tableAlias = "t", columnName = "id"),
                            ),
                        ),
                    ),
                ),
                conditions = listOf(
                    Condition.Simple(ColumnRef(tableAlias = "t", columnName = "status"), "=", SqlValue.Literal("1")),
                ),
            ),
        )
        val parsed = SqlReverseParser.parse(sql1)
        assertEquals(1, parsed.joins.size)
        assertEquals("address", parsed.joins[0].tableName)
        // 重建 → 再渲染等价
        val rebuilt = SelectStatement(
            table = table,
            selectColumns = listOf(ColumnRef(columnName = "id")),
            tableAlias = "t",
            joins = parsed.joins.map { j ->
                JoinClause(type = j.type, tableName = j.tableName, alias = j.alias ?: "", onConditions = j.onConditions)
            },
            conditions = parsed.conditions,
        )
        assertEquals(sql1, SqlGenerator.generate(rebuilt))
    }

    // ------------------------------------------------------------------
    // 子查询 JOIN / FROM 子查询端到端测试（对齐 demo 项目用例）
    // ------------------------------------------------------------------

    @Test
    fun `demo_joinExtAndSubTable_子查询JOIN不报错`() {
        val sql = "SELECT\n" +
            "    t.id,\n" +
            "    t.station_id,\n" +
            "    t.share_scheme_id,\n" +
            "    t.device_name\n" +
            "FROM car_device_info t\n" +
            "LEFT JOIN station_info stationInfoEntity ON stationInfoEntity.id = t.station_id\n" +
            "    AND stationInfoEntity.station_name = '中心站'\n" +
            "LEFT JOIN (\n" +
            "    SELECT\n" +
            "        tt.id, tt.station_id, tt.station_name, tt.merchant_id\n" +
            "    FROM station_info tt\n" +
            "    WHERE tt.merchant_id >= 0\n" +
            ") tt ON tt.station_id = t.station_id\n" +
            "WHERE t.id = 1"
        val result = SqlReverseParser.parse(sql)
        assertEquals(2, result.joins.size)
        val secondJoinName = result.joins[1].tableName
        assertTrue("第二个 JOIN 应为子查询, 实际: $secondJoinName", secondJoinName.contains("(") && secondJoinName.contains("SELECT"))
        val code = JavaCodeGenerator.generate(result)
        assertTrue("生成代码应包含 leftJoin", code.contains("leftJoin"))
    }

    @Test
    fun `demo_selectJoinSubQuery_子查询JOIN不报错`() {
        val sql = "SELECT t.id, t.name, addr.tel\n" +
            "FROM user t\n" +
            "LEFT JOIN (\n" +
            "    SELECT user_id, tel, address FROM user_address WHERE status >= 0\n" +
            ") addr ON addr.user_id = t.id\n" +
            "WHERE t.id = 1"
        val result = SqlReverseParser.parse(sql)
        assertEquals(1, result.joins.size)
        val joinName = result.joins[0].tableName
        assertTrue("JOIN 应为子查询, 实际: $joinName", joinName.contains("(") && joinName.contains("SELECT"))
        val code = JavaCodeGenerator.generate(result)
        assertTrue("生成代码应包含 leftJoin", code.contains("leftJoin"))
    }

    @Test
    fun `demo_joinFromAndPage_FROM子查询不报错`() {
        val sql = "SELECT t.id, t.device_name\n" +
            "FROM (\n" +
            "    SELECT id, device_name FROM car_device_info WHERE id >= 0 LIMIT 100\n" +
            ") t\n" +
            "LEFT JOIN station_info ON station_info.id = t.station_id"
        val result = SqlReverseParser.parse(sql)
        val fromTable = result.tableName
        assertTrue("FROM 子查询 tableName 应包含子查询文本, 实际: $fromTable", fromTable.contains("(") && fromTable.contains("SELECT"))
        assertEquals(1, result.joins.size)
    }

    @Test
    fun `demo_selectJoinFrom_FROM子查询不报错`() {
        val sql = "SELECT t.id, t.name, addr.tel\n" +
            "FROM (\n" +
            "    SELECT id, name FROM user WHERE id >= 0 LIMIT 10\n" +
            ") t\n" +
            "LEFT JOIN user_address addr ON addr.user_id = t.id\n" +
            "WHERE t.id >= 0"
        val result = SqlReverseParser.parse(sql)
        val fromTable = result.tableName
        assertTrue("FROM 子查询 tableName 应包含子查询文本, 实际: $fromTable", fromTable.contains("(") && fromTable.contains("SELECT"))
        assertEquals(1, result.joins.size)
        assertEquals("user_address", result.joins[0].tableName)
    }

    // ------------------------------------------------------------------
    // ext 条件 lambda 化端到端测试
    // ------------------------------------------------------------------

    @Test
    fun `demo_joinExtAndSubTable_ext条件生成4参lambda`() {
        val sql = "SELECT\n" +
            "    t.id,\n" +
            "    t.station_id,\n" +
            "    t.share_scheme_id,\n" +
            "    t.device_name\n" +
            "FROM car_device_info t\n" +
            "LEFT JOIN station_info stationInfoEntity ON stationInfoEntity.id = t.station_id\n" +
            "    AND stationInfoEntity.station_name = '中心站'\n" +
            "LEFT JOIN (\n" +
            "    SELECT\n" +
            "        tt.id, tt.station_id, tt.station_name, tt.merchant_id\n" +
            "    FROM station_info tt\n" +
            "    WHERE tt.merchant_id >= 0\n" +
            ") tt ON tt.station_id = t.station_id\n" +
            "WHERE t.id = 1"
        val result = SqlReverseParser.parse(sql)
        assertEquals(2, result.joins.size)
        // 第一个 JOIN 有 ext 条件（station_name = '中心站'）→ 应生成 4 参 lambda
        // 第二个 JOIN 是子查询 → 仍回落字符串

        // 构造实体上下文
        val stationSub = JavaCodeGenerator.EntityTarget(
            className = "StationInfoEntity",
            columnToField = mapOf(
                "id" to "id",
                "station_id" to "stationId",
                "station_name" to "stationName",
                "merchant_id" to "merchantId",
            ),
        )
        val entity = JavaCodeGenerator.EntityTarget(
            className = "DeviceInfoEntity",
            columnToField = mapOf(
                "id" to "id",
                "station_id" to "stationId",
                "share_scheme_id" to "shareSchemeId",
                "device_name" to "deviceName",
            ),
            subEntities = mapOf("stationInfoEntity" to stationSub),
        )
        val code = JavaCodeGenerator.generate(result, entity)
        // 第一个 JOIN：ext 条件 lambda 化
        assertTrue("第一个 JOIN 应生成 4 参 leftJoin", code.contains("leftJoin(StationInfoEntity.class,"))
        assertTrue("应包含 ext lambda", code.contains("ext -> ext.eq(StationInfoEntity::getStationName,"))
        // 第二个 JOIN：MConsumer 子查询形态（按表名推导匹配到副实体）
        assertTrue("第二个 JOIN 应生成 MConsumer 形态", code.contains("t -> t.setAlias(\"tt\")"))
        assertTrue("应包含 ge 条件", code.contains(".ge(StationInfoEntity::getMerchantId,"))
    }

    @Test
    fun `demo_selectJoinWithOnCondition_ext条件生成4参lambda`() {
        // 对齐 UserServiceImpl.selectJoinWithOnCondition
        val sql = "SELECT t.id, t.name, t1.tel, t1.address\n" +
            "FROM user t\n" +
            "LEFT JOIN user_address t1 ON t1.user_id = t.id AND t1.status = 1\n" +
            "LEFT JOIN area t2 ON t2.id = t1.area_id AND t2.city = '杭州'\n" +
            "WHERE t.sex = 1"
        val result = SqlReverseParser.parse(sql)
        assertEquals(2, result.joins.size)
        // 两个 JOIN 都有 ext 条件

        val addressSub = JavaCodeGenerator.EntityTarget(
            className = "AddressDO",
            columnToField = mapOf("user_id" to "userId", "tel" to "tel", "address" to "address", "status" to "status", "area_id" to "areaId"),
        )
        val areaSub = JavaCodeGenerator.EntityTarget(
            className = "AreaDO",
            columnToField = mapOf("id" to "id", "city" to "city"),
        )
        val entity = JavaCodeGenerator.EntityTarget(
            className = "UserDO",
            columnToField = mapOf("id" to "id", "name" to "name", "sex" to "sex"),
            subEntities = mapOf("t1" to addressSub, "t2" to areaSub),
        )
        val code = JavaCodeGenerator.generate(result, entity)
        // 第一个 JOIN：ext 条件 lambda 化
        assertTrue("第一个 JOIN 应生成 4 参 leftJoin", code.contains("leftJoin(AddressDO.class,"))
        assertTrue("第一个 JOIN 应包含 status ext", code.contains("ext.eq(AddressDO::getStatus,"))
        // 第二个 JOIN：right 字段在 address 副实体（非主实体）→ canLambdaJoin 回落字符串
        assertTrue("第二个 JOIN 应为字符串形态", code.contains("(SELECT") || code.contains("leftJoin(\"area t2 ON"))
    }

    // ------------------------------------------------------------------
    // MConsumer 子查询 JOIN 端到端测试
    // ------------------------------------------------------------------

    @Test
    fun `demo_joinExtAndSubTable_MConsumer子查询形态`() {
        val sql = "SELECT\n" +
            "    t.id,\n" +
            "    t.station_id,\n" +
            "    t.share_scheme_id,\n" +
            "    t.device_name\n" +
            "FROM car_device_info t\n" +
            "LEFT JOIN station_info stationInfoEntity ON stationInfoEntity.id = t.station_id\n" +
            "    AND stationInfoEntity.station_name = '中心站'\n" +
            "LEFT JOIN (\n" +
            "    SELECT\n" +
            "        tt.id, tt.station_id, tt.station_name, tt.merchant_id\n" +
            "    FROM station_info tt\n" +
            "    WHERE tt.merchant_id >= 0\n" +
            ") tt ON tt.station_id = t.station_id\n" +
            "WHERE t.id = 1"
        val result = SqlReverseParser.parse(sql)
        assertEquals(2, result.joins.size)
        // 第二个 JOIN 应解析出 MConsumer 结构
        assertNotNull("第二个 JOIN 应有 subQuery", result.joins[1].subQuery)
        val subQuery = result.joins[1].subQuery!!
        assertEquals("station_info", subQuery.tableName)
        assertEquals("tt", subQuery.alias)
        assertFalse(subQuery.selectAll)
        assertEquals(1, subQuery.whereConditions.size)

        // 构造实体上下文
        val stationSub = JavaCodeGenerator.EntityTarget(
            className = "StationInfoEntity",
            columnToField = mapOf(
                "id" to "id",
                "station_id" to "stationId",
                "station_name" to "stationName",
                "merchant_id" to "merchantId",
            ),
        )
        val entity = JavaCodeGenerator.EntityTarget(
            className = "DeviceInfoEntity",
            columnToField = mapOf(
                "id" to "id",
                "station_id" to "stationId",
                "share_scheme_id" to "shareSchemeId",
                "device_name" to "deviceName",
            ),
            subEntities = mapOf("stationInfoEntity" to stationSub, "tt" to stationSub),
        )
        val code = JavaCodeGenerator.generate(result, entity)
        // 第二个 JOIN 应生成 MConsumer 形态
        assertTrue("第二个 JOIN 应生成 MConsumer 形态", code.contains("t -> t.setAlias(\"tt\")"))
        assertTrue("应包含 select（显式列）", code.contains(".select("))
        assertTrue("应包含 ge 条件", code.contains(".ge(StationInfoEntity::getMerchantId,"))
        // 第一个 JOIN 应生成 ext lambda 形态
        assertTrue("第一个 JOIN 应生成 ext lambda", code.contains("ext -> ext.eq(StationInfoEntity::getStationName,"))
    }

    @Test
    fun `demo_selectJoinSubQuery_MConsumer形态`() {
        // 对齐 UserServiceImpl.selectJoinSubQuery
        val sql = "SELECT t.id, t.name, addr.tel\n" +
            "FROM user t\n" +
            "LEFT JOIN (\n" +
            "    SELECT user_id, tel, address FROM user_address WHERE status >= 0\n" +
            ") addr ON addr.user_id = t.id\n" +
            "WHERE t.id = 1"
        val result = SqlReverseParser.parse(sql)
        assertEquals(1, result.joins.size)
        assertNotNull("应有 subQuery", result.joins[0].subQuery)

        val addressSub = JavaCodeGenerator.EntityTarget(
            className = "AddressDO",
            columnToField = mapOf("user_id" to "userId", "tel" to "tel", "address" to "address", "status" to "status"),
        )
        val entity = JavaCodeGenerator.EntityTarget(
            className = "UserDO",
            columnToField = mapOf("id" to "id", "name" to "name"),
            subEntities = mapOf("addr" to addressSub),
        )
        val code = JavaCodeGenerator.generate(result, entity)
        assertTrue("应生成 MConsumer 形态", code.contains("t -> t.setAlias(\"addr\")"))
        assertTrue("应包含 select 列", code.contains(".select("))
        assertTrue("应包含 ge 条件", code.contains(".ge(AddressDO::getStatus,"))
    }
}
