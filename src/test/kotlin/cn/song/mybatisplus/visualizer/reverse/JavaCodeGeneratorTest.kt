package cn.song.mybatisplus.visualizer.reverse

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SqlValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QueryWrapper Java 代码生成器单元测试（P0）：[SqlReverseResult] → 代码文本。
 */
class JavaCodeGeneratorTest {

    @Test
    fun `完整链路_条件全家与排序分页`() {
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = listOf("id", "name"),
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Literal("'张三'", "张三")),
                Condition.Simple(ColumnRef(columnName = "age"), ">", SqlValue.Literal("18")),
                Condition.Simple(ColumnRef(columnName = "flag"), "=", SqlValue.Literal("true")),
                Condition.Unary(ColumnRef(columnName = "deleted"), "IS NULL"),
                Condition.In(ColumnRef(columnName = "status"), negated = false, listOf(SqlValue.Literal("1"), SqlValue.Literal("2"))),
                Condition.In(ColumnRef(columnName = "exclude"), negated = true, listOf(SqlValue.Literal("'a'", "a"))),
                Condition.Between(ColumnRef(columnName = "create_time"), negated = false, SqlValue.Literal("'2024-01-01'", "2024-01-01"), SqlValue.Literal("'2024-01-31'", "2024-01-31")),
                Condition.Like(ColumnRef(columnName = "remark"), negated = false, SqlValue.Literal("'%abc%'", "abc"), LikePosition.ANYWHERE),
                Condition.Like(ColumnRef(columnName = "nick"), negated = true, SqlValue.Literal("'张%'", "张"), LikePosition.RIGHT),
            ),
            orderBy = listOf(
                OrderByItem(ColumnRef(columnName = "create_time"), asc = false),
                OrderByItem(ColumnRef(columnName = "id"), asc = false),
            ),
            limit = LimitClause(count = SqlValue.Literal("10")),
        )
        val code = JavaCodeGenerator.generate(result)
        assertEquals(
            """
            // SQL → MyBatis-Plus QueryWrapper 反向生成（MyBatis-Plus SQL Visualizer）
            // 实体类名 User 由表名 user 推断，请按实际类名调整
            new QueryWrapper<User>()
                .select("id", "name")
                .eq("name", "张三")
                .gt("age", 18)
                .eq("flag", true)
                .isNull("deleted")
                .in("status", 1, 2)
                .notIn("exclude", "a")
                .between("create_time", "2024-01-01", "2024-01-31")
                .like("remark", "abc")
                .notLikeRight("nick", "张")
                .orderByDesc("create_time", "id")
                .last("LIMIT 10");
            """.trimIndent(),
            code,
        )
    }

    @Test
    fun `无条件_最小形态`() {
        val code = JavaCodeGenerator.generate(SqlReverseResult(tableName = "user", selectColumns = null))
        assertEquals(
            """
            // SQL → MyBatis-Plus QueryWrapper 反向生成（MyBatis-Plus SQL Visualizer）
            // 实体类名 User 由表名 user 推断，请按实际类名调整
            new QueryWrapper<User>();
            """.trimIndent(),
            code,
        )
    }

    @Test
    fun `字符串值转义_双引号与反斜杠`() {
        val result = SqlReverseResult(
            tableName = "t",
            selectColumns = null,
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "path"), "=", SqlValue.Literal("'a\"b\\c'", "a\"b\\c")),
            ),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code.contains(".eq(\"path\", \"a\\\"b\\\\c\")"))
    }

    @Test
    fun `LIKE降级Raw_渲染apply`() {
        val result = SqlReverseResult(
            tableName = "t",
            selectColumns = null,
            conditions = listOf(Condition.Raw("a LIKE 'a_b'")),
            warnings = listOf("LIKE 模式 a_b 无法映射为 like 家族方法，已用 apply() 透传"),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code.contains(".apply(\"a LIKE 'a_b'\")"))
        assertTrue(code.contains("// 注意：LIKE 模式 a_b 无法映射为 like 家族方法，已用 apply() 透传"))
    }

    @Test
    fun `ORDER BY_异向拆分与同向合并`() {
        val result = SqlReverseResult(
            tableName = "t",
            selectColumns = null,
            orderBy = listOf(
                OrderByItem(ColumnRef(columnName = "a"), asc = true),
                OrderByItem(ColumnRef(columnName = "b"), asc = true),
                OrderByItem(ColumnRef(columnName = "c"), asc = false),
                OrderByItem(ColumnRef(columnName = "d"), asc = true),
            ),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code.contains(".orderByAsc(\"a\", \"b\")"))
        assertTrue(code.contains(".orderByDesc(\"c\")"))
        assertTrue(code.contains(".orderByAsc(\"d\")"))
    }

    @Test
    fun `LIMIT偏移形态_渲染last`() {
        val result = SqlReverseResult(
            tableName = "t",
            selectColumns = null,
            limit = LimitClause(offset = SqlValue.Literal("5"), count = SqlValue.Literal("10")),
        )
        assertTrue(JavaCodeGenerator.generate(result).contains(".last(\"LIMIT 5, 10\")"))
    }

    @Test
    fun `实体类名推断_下划线转驼峰`() {
        assertEquals("CarDeviceInfo", JavaCodeGenerator.entityClassName("car_device_info"))
        assertEquals("User", JavaCodeGenerator.entityClassName("user"))
        assertEquals("UserInfo", JavaCodeGenerator.entityClassName("userInfo"))
        assertEquals("OrderItem2", JavaCodeGenerator.entityClassName("order_item_2"))
    }

    @Test
    fun `javaEscape_转义集合`() {
        assertEquals("a\\\"b\\\\c\\n", JavaCodeGenerator.javaEscape("a\"b\\c\n"))
    }
}
