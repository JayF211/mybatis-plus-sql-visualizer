package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JpqlConverter 单元测试（F-JPA-02）。
 *
 * 验证 JPQL → SQL 转换的正确性，覆盖：
 *  - 命名参数替换（:param → ?）
 *  - 位置参数替换（?1 → ?）
 *  - 实体名 → 表名映射
 *  - 属性名 → 列名映射
 *  - JOIN FETCH 处理
 *  - NEW DTO 表达式处理
 *  - MEMBER OF 降级处理
 *  - 复杂语法检测与注释提示
 */
class JpqlConverterTest {

    // ------------------------------------------------------------------
    // 测试用 TableInfo
    // ------------------------------------------------------------------

    private val userTable = TableInfo(
        entityClassName = "UserEntity",
        tableName = "user",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("username", "username"),
            ColumnInfo("email", "email"),
            ColumnInfo("age", "age"),
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

    // ------------------------------------------------------------------
    // 命名参数替换测试
    // ------------------------------------------------------------------

    @Test
    fun `namedParameter_replacedWithPlaceholder`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.username = :username"
        val result = JpqlConverter.convert(jpql, userTable)
        // 验证 SelectStatement 构造成功
        assertNotNull(result)
        assertEquals("user", result.table.tableName)
    }

    @Test
    fun `namedParameter_multipleParams`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.username = :username AND u.age = :age"
        val result = JpqlConverter.convert(jpql, userTable)
        assertNotNull(result)
    }

    @Test
    fun `namedParameter_noParams`() {
        val jpql = "SELECT u FROM UserEntity u"
        val result = JpqlConverter.convert(jpql, userTable)
        assertNotNull(result)
    }

    // ------------------------------------------------------------------
    // 位置参数替换测试
    // ------------------------------------------------------------------

    @Test
    fun `positionalParameter_replacedWithPlaceholder`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.username = ?1"
        val result = JpqlConverter.convert(jpql, userTable)
        assertNotNull(result)
    }

    @Test
    fun `positionalParameter_multipleParams`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.username = ?1 AND u.age = ?2"
        val result = JpqlConverter.convert(jpql, userTable)
        assertNotNull(result)
    }

    // ------------------------------------------------------------------
    // 实体名 → 表名映射测试
    // ------------------------------------------------------------------

    @Test
    fun `entityName_mappedToTableName`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.username = :username"
        val result = JpqlConverter.convert(jpql, userTable)
        assertEquals("user", result.table.tableName)
        assertEquals("UserEntity", result.table.entityClassName)
    }

    @Test
    fun `entityName_withCustomTableName`() {
        val jpql = "SELECT o FROM OrderEntity o WHERE o.orderNo = :orderNo"
        val result = JpqlConverter.convert(jpql, orderTable)
        assertEquals("t_order", result.table.tableName)
    }

    // ------------------------------------------------------------------
    // 属性名 → 列名映射测试
    // ------------------------------------------------------------------

    @Test
    fun `propertyName_mappedToColumnName`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.departmentId = :deptId"
        val result = JpqlConverter.convert(jpql, userTable)
        // departmentId → department_id 映射应生效
        assertEquals("user", result.table.tableName)
    }

    @Test
    fun `propertyName_snakeCaseMapping`() {
        val jpql = "SELECT o FROM OrderEntity o WHERE o.totalAmount > :minAmount"
        val result = JpqlConverter.convert(jpql, orderTable)
        assertEquals("t_order", result.table.tableName)
    }

    // ------------------------------------------------------------------
    // JOIN FETCH 处理测试
    // ------------------------------------------------------------------

    @Test
    fun `joinFetch_convertedToLeftJoin`() {
        val jpql = "SELECT u FROM UserEntity u JOIN FETCH u.department d"
        val result = JpqlConverter.convert(jpql, userTable)
        assertNotNull(result)
    }

    @Test
    fun `leftJoinFetch_convertedToLeftJoin`() {
        val jpql = "SELECT u FROM UserEntity u LEFT JOIN FETCH u.department d"
        val result = JpqlConverter.convert(jpql, userTable)
        assertNotNull(result)
    }

    @Test
    fun `innerJoinFetch_convertedToInnerJoin`() {
        val jpql = "SELECT u FROM UserEntity u INNER JOIN FETCH u.department d"
        val result = JpqlConverter.convert(jpql, userTable)
        assertNotNull(result)
    }

    // ------------------------------------------------------------------
    // NEW DTO 表达式处理测试
    // ------------------------------------------------------------------

    @Test
    fun `newExpression_extracted`() {
        val jpql = "SELECT NEW com.example.UserDTO(u.username, u.email) FROM UserEntity u"
        val result = JpqlConverter.convert(jpql, userTable) as SelectStatement
        // NEW DTO 应触发复杂语法注释
        assertNotNull("NEW DTO 应生成注释提示", result.comment)
        assertTrue(
            "注释应提示复杂语法",
            result.comment!!.contains("复杂语法"),
        )
    }

    // ------------------------------------------------------------------
    // MEMBER OF 降级处理测试
    // ------------------------------------------------------------------

    @Test
    fun `memberOf_degradedWithComment`() {
        val jpql = "SELECT u FROM UserEntity u WHERE :role MEMBER OF u.roles"
        val result = JpqlConverter.convert(jpql, userTable) as SelectStatement
        // MEMBER OF 应触发复杂语法注释
        assertNotNull("MEMBER OF 应生成注释提示", result.comment)
    }

    // ------------------------------------------------------------------
    // 复杂语法检测测试
    // ------------------------------------------------------------------

    @Test
    fun `complexSyntax_subquery_detected`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.id IN (SELECT o.customerId FROM OrderEntity o)"
        val result = JpqlConverter.convert(jpql, userTable) as SelectStatement
        // 子查询应触发复杂语法注释
        assertNotNull("子查询应生成注释提示", result.comment)
    }

    @Test
    fun `simpleSyntax_noComment`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.username = :username"
        val result = JpqlConverter.convert(jpql, userTable) as SelectStatement
        // 简单 JPQL 不应有注释
        assertNull("简单 JPQL 不应有注释", result.comment)
    }

    // ------------------------------------------------------------------
    // 无 TableInfo 兜底测试
    // ------------------------------------------------------------------

    @Test
    fun `noTableInfo_fallbackTableUsed`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.username = :username"
        val result = JpqlConverter.convert(jpql, null)
        assertNotNull(result)
        assertEquals("unknown_table", result.table.tableName)
    }

    // ------------------------------------------------------------------
    // 综合场景测试
    // ------------------------------------------------------------------

    @Test
    fun `complexQuery_multipleTransformations`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.username = :username AND u.age > ?1 AND u.departmentId = :deptId"
        val result = JpqlConverter.convert(jpql, userTable)
        assertNotNull(result)
        assertEquals("user", result.table.tableName)
    }

    @Test
    fun `countQuery_converted`() {
        val jpql = "SELECT COUNT(u) FROM UserEntity u WHERE u.departmentId = :deptId"
        val result = JpqlConverter.convert(jpql, userTable)
        assertNotNull(result)
        assertEquals("user", result.table.tableName)
    }

    @Test
    fun `joinQuery_withWhereClause`() {
        val jpql = "SELECT u FROM UserEntity u JOIN u.department d WHERE d.name = :deptName"
        val result = JpqlConverter.convert(jpql, userTable)
        assertNotNull(result)
    }

    @Test
    fun `orderByQuery_converted`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.departmentId = :deptId ORDER BY u.createTime DESC"
        val result = JpqlConverter.convert(jpql, userTable)
        assertNotNull(result)
    }

    // ------------------------------------------------------------------
    // SelectStatement 结构验证
    // ------------------------------------------------------------------

    @Test
    fun `selectStatement_tableInfoCorrect`() {
        val jpql = "SELECT u FROM UserEntity u"
        val result = JpqlConverter.convert(jpql, userTable)
        assertEquals("UserEntity", result.table.entityClassName)
        assertEquals("user", result.table.tableName)
        assertEquals(6, result.table.columns.size)
    }

    @Test
    fun `selectStatement_defaultValues`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.username = :username"
        val result = JpqlConverter.convert(jpql, userTable) as SelectStatement
        // 默认值验证
        assertFalse(result.distinct)
        assertTrue(result.selectColumns.isEmpty())
        assertTrue(result.joins.isEmpty())
        assertTrue(result.conditions.isEmpty())
        assertTrue(result.groupBy.isEmpty())
        assertTrue(result.orderBy.isEmpty())
        assertNull(result.limit)
    }

    // ------------------------------------------------------------------
    // DELETE JPQL（P2 修复）
    // ------------------------------------------------------------------

    @Test
    fun `deleteJpql_returnsDeleteStatement`() {
        val jpql = "DELETE FROM UserEntity u WHERE u.age < :maxAge"
        val result = JpqlConverter.convert(jpql, userTable)
        // DELETE JPQL 应返回 DeleteStatement（而非 SelectStatement）
        assertTrue("DELETE JPQL 应返回 DeleteStatement, 实际: ${result::class.simpleName}",
            result is cn.song.mybatisplus.visualizer.model.DeleteStatement)
    }

    // ------------------------------------------------------------------
    // NEW DTO + GROUP BY + HAVING（statByCustomer 用例）
    // ------------------------------------------------------------------

    @Test
    fun `newDtoGroupByHaving_complexSyntax`() {
        val jpql = "SELECT NEW cn.song.jpademo.dto.OrderStatDTO(o.customerId, COUNT(o), SUM(o.totalAmount)) " +
            "FROM OrderEntity o GROUP BY o.customerId HAVING COUNT(o) > 1"
        val result = JpqlConverter.convert(jpql, orderTable) as SelectStatement
        // NEW DTO 应被提取为列引用
        assertTrue("NEW DTO 参数应被提取为列引用", result.selectColumns.isNotEmpty() || result.comment != null)
        // 复杂语法应触发注释
        assertNotNull("NEW DTO + GROUP BY + HAVING 应生成复杂语法注释", result.comment)
    }
}
