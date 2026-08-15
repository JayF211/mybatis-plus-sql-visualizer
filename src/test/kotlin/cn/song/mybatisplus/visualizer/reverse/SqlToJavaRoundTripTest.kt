package cn.song.mybatisplus.visualizer.reverse

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import org.junit.Assert.assertEquals
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
            selectColumns = parsed.selectColumns?.map { ColumnRef(columnName = it) } ?: listOf(ColumnRef(columnName = "id")),
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
}
