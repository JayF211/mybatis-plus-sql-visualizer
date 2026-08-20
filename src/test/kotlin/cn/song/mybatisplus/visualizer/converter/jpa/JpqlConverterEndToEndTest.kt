package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JpqlConverter 端到端测试：JPQL → SQL → 参数回填完整链路。
 *
 * 验证 ? 占位符存在且可被回填，覆盖多种 JPQL 模式：
 *  - 命名参数
 *  - BETWEEN
 *  - DELETE
 *  - NEW DTO
 *  - LIKE CONCAT
 */
class JpqlConverterEndToEndTest {

    private val userTable = TableInfo(
        entityClassName = "UserEntity",
        tableName = "user",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("username", "username"),
            ColumnInfo("email", "email"),
            ColumnInfo("age", "age"),
            ColumnInfo("status", "status"),
            ColumnInfo("departmentId", "department_id"),
            ColumnInfo("createTime", "create_time"),
        ),
    )

    private val orderTable = TableInfo(
        entityClassName = "OrderEntity",
        tableName = "t_order",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("orderNo", "order_no"),
            ColumnInfo("customerId", "customer_id"),
            ColumnInfo("totalAmount", "total_amount"),
        ),
    )

    /**
     * 端到端：JPQL → 转换后 SQL → 模拟面板参数回填。
     * 验证 ? 占位符存在且数量 == 预期参数数量。
     */
    @Test
    fun `e2e JPQL SQL placeholders match method params`() {
        data class Case(val jpql: String, val table: TableInfo, val expectedParamCount: Int)
        val cases = listOf(
            Case("SELECT u FROM UserEntity u WHERE u.username = :username", userTable, 1),
            Case("SELECT u FROM UserEntity u WHERE u.username = :username AND u.age BETWEEN :minAge AND :maxAge", userTable, 3),
            Case("DELETE FROM UserEntity u WHERE u.age < :maxAge", userTable, 1),
            Case("SELECT o FROM OrderEntity o WHERE o.totalAmount >= :minAmount", orderTable, 1),
            Case("SELECT NEW cn.song.jpademo.dto.OrderStatDTO(o.customerId, COUNT(o), SUM(o.totalAmount)) FROM OrderEntity o GROUP BY o.customerId HAVING COUNT(o) > 1", orderTable, 0),
            Case("SELECT * FROM user WHERE username LIKE CONCAT('%', :keyword, '%') AND status = :status", userTable, 2),
        )
        for (c in cases) {
            val result = JpqlConverter.convert(c.jpql, c.table)
            assertTrue("SQL 不应为空白: ${c.jpql}", result.sql.isNotBlank())
            assertEquals("占位符数量不匹配: ${c.jpql}",
                c.expectedParamCount, result.sql.count { it == '?' })
            // 不应残留 :paramName
            assertFalse("不应残留命名参数: ${result.sql}", result.sql.contains(":"))
        }
    }

    /** 端到端：DELETE JPQL 不应返回 SelectStatement */
    @Test
    fun `e2e DELETE JPQL statement type is DeleteStatement`() {
        val result = JpqlConverter.convert("DELETE FROM UserEntity u WHERE u.age < :maxAge", userTable)
        assertTrue("DELETE 应返回 DeleteStatement: ${result.statement::class.simpleName}",
            result.statement is DeleteStatement)
        // DELETE SQL 应含 ?
        assertTrue("DELETE SQL 应含 ?: ${result.sql}", result.sql.contains("?"))
        assertEquals("占位符数量应为 1", 1, result.sql.count { it == '?' })
    }

    /** 端到端：NEW DTO 复杂语法应被正确处理 */
    @Test
    fun `e2e NEW DTO with GROUP BY HAVING`() {
        val result = JpqlConverter.convert(
            "SELECT NEW cn.song.jpademo.dto.OrderStatDTO(o.customerId, COUNT(o), SUM(o.totalAmount)) " +
                "FROM OrderEntity o GROUP BY o.customerId HAVING COUNT(o) > 1",
            orderTable,
        )
        val selectStmt = result.statement as SelectStatement
        // 复杂语法应触发注释
        assertNotNull("NEW DTO + GROUP BY + HAVING 应生成复杂语法注释", selectStmt.comment)
        // SQL 应含 SELECT 和 GROUP BY
        assertTrue("SQL 应含 SELECT: ${result.sql}", result.sql.contains("SELECT", ignoreCase = true))
        assertTrue("SQL 应含 GROUP BY: ${result.sql}", result.sql.contains("GROUP BY", ignoreCase = true))
    }

    /** 端到端：BETWEEN 参数应生成两个占位符 */
    @Test
    fun `e2e BETWEEN generates two placeholders`() {
        val result = JpqlConverter.convert(
            "SELECT u FROM UserEntity u WHERE u.age BETWEEN :minAge AND :maxAge",
            userTable,
        )
        assertEquals("BETWEEN 应生成 2 个 ?", 2, result.sql.count { it == '?' })
        // 不应残留命名参数
        assertFalse("不应残留 :minAge: ${result.sql}", result.sql.contains(":minAge"))
        assertFalse("不应残留 :maxAge: ${result.sql}", result.sql.contains(":maxAge"))
    }

    /** 端到端：LIKE CONCAT 模式参数应被正确替换 */
    @Test
    fun `e2e LIKE CONCAT named params replaced`() {
        val result = JpqlConverter.convert(
            "SELECT * FROM user WHERE username LIKE CONCAT('%', :keyword, '%') AND status = :status",
            userTable,
        )
        assertEquals("LIKE CONCAT 模式应生成 2 个 ?", 2, result.sql.count { it == '?' })
        assertFalse("不应残留 :keyword: ${result.sql}", result.sql.contains(":keyword"))
        assertFalse("不应残留 :status: ${result.sql}", result.sql.contains(":status"))
    }
}
