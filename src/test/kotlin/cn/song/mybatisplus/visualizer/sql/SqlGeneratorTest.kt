package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SQL 生成器单元测试（M1-04）：验证模型 → SQL 文本渲染。
 */
class SqlGeneratorTest {

    /** 测试用实体表信息（对齐 docs/项目介绍.md 的 car_device_info）。 */
    private val table = TableInfo(
        entityClassName = "DeviceInfoEntity",
        tableName = "car_device_info",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("stationId", "station_id"),
            ColumnInfo("shareSchemeId", "share_scheme_id"),
            ColumnInfo("sort", "sort"),
        ),
    )

    @Test
    fun `基本查询_无条件无排序`() {
        val statement = SelectStatement(table = table)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, sort
            FROM car_device_info
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `带条件_多条件AND缩进`() {
        val statement = SelectStatement(
            table = table,
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1001")),
                Condition.Unary(ColumnRef(columnName = "share_scheme_id"), "IS NOT NULL"),
            ),
        )
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, sort
            FROM car_device_info
            WHERE id = 1001
                AND share_scheme_id IS NOT NULL
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `占位符参数_变量渲染为问号`() {
        val statement = SelectStatement(
            table = table,
            conditions = listOf(
                Condition.Simple(
                    ColumnRef(columnName = "station_id"),
                    "=",
                    SqlValue.Placeholder("stationId"),
                ),
            ),
        )
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, sort
            FROM car_device_info
            WHERE station_id = ?
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `in与between条件`() {
        val statement = SelectStatement(
            table = table,
            conditions = listOf(
                Condition.In(
                    ColumnRef(columnName = "id"),
                    negated = false,
                    values = listOf(SqlValue.Literal("1"), SqlValue.Literal("2"), SqlValue.Literal("3")),
                ),
                Condition.Between(
                    ColumnRef(columnName = "sort"),
                    negated = false,
                    lo = SqlValue.Literal("1"),
                    hi = SqlValue.Literal("10"),
                ),
            ),
        )
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, sort
            FROM car_device_info
            WHERE id IN (1, 2, 3)
                AND sort BETWEEN 1 AND 10
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `like条件_不同位置百分号`() {
        val tableWithName = TableInfo(
            entityClassName = "UserEntity",
            tableName = "user",
            columns = listOf(ColumnInfo("name", "name")),
        )
        fun likeSql(position: LikePosition, negated: Boolean = false): String {
            val statement = SelectStatement(
                table = tableWithName,
                conditions = listOf(
                    Condition.Like(ColumnRef(columnName = "name"), negated, SqlValue.Literal("张"), position),
                ),
            )
            return SqlGenerator.generate(statement).substringAfter("WHERE ")
        }
        assertEquals("name LIKE %张%", likeSql(LikePosition.ANYWHERE))
        assertEquals("name LIKE %张", likeSql(LikePosition.LEFT))
        assertEquals("name LIKE 张%", likeSql(LikePosition.RIGHT))
        assertEquals("name NOT LIKE %张%", likeSql(LikePosition.ANYWHERE, negated = true))
    }

    @Test
    fun `逻辑分组_嵌套括号`() {
        val statement = SelectStatement(
            table = table,
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1")),
                Condition.Group(
                    connector = cn.song.mybatisplus.visualizer.model.Connector.OR,
                    children = listOf(
                        Condition.Simple(ColumnRef(columnName = "station_id"), "=", SqlValue.Literal("101")),
                        Condition.Simple(ColumnRef(columnName = "share_scheme_id"), "=", SqlValue.Literal("5")),
                    ),
                ),
            ),
        )
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, sort
            FROM car_device_info
            WHERE id = 1
                AND (station_id = 101 OR share_scheme_id = 5)
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `完整查询_分组排序分页`() {
        val statement = SelectStatement(
            table = table,
            selectColumns = listOf(
                ColumnRef(columnName = "station_id"),
                ColumnRef(columnName = "share_scheme_id"),
            ),
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Placeholder("deviceId")),
            ),
            groupBy = listOf(ColumnRef(columnName = "station_id")),
            orderBy = listOf(
                OrderByItem(ColumnRef(columnName = "sort"), asc = true),
                OrderByItem(ColumnRef(columnName = "id"), asc = false),
            ),
            limit = LimitClause(count = 10),
        )
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT station_id, share_scheme_id
            FROM car_device_info
            WHERE id = ?
            GROUP BY station_id
            ORDER BY sort ASC, id DESC
            LIMIT 10
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `带偏移量分页`() {
        val statement = SelectStatement(
            table = table,
            limit = LimitClause(offset = 20, count = 10),
        )
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, sort
            FROM car_device_info
            LIMIT 20, 10
            """.trimIndent(),
            sql,
        )
    }
}
