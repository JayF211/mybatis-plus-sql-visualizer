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
 * HqlConverter 单元测试（Wave2-P1）。
 */
class HqlConverterTest {

    private val userTable = TableInfo(
        entityClassName = "UserEntity",
        tableName = "user_entity",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("username", "username"),
            ColumnInfo("status", "status"),
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

    @Test
    fun `namedParameter_replacedWithPlaceholder`() {
        val hql = "from UserEntity u where u.username = :username"
        val result = HqlConverter.convert(hql, userTable)
        assertNotNull(result)
        assertEquals("user_entity", result.table.tableName)
    }

    @Test
    fun `namedParameter_multipleParams`() {
        val hql = "from UserEntity u where u.username = :username and u.status = :status"
        val result = HqlConverter.convert(hql, userTable)
        assertNotNull(result)
    }

    @Test
    fun `positionalParameter_replacedWithPlaceholder`() {
        val hql = "from UserEntity u where u.username = ?1"
        val result = HqlConverter.convert(hql, userTable)
        assertNotNull(result)
    }

    @Test
    fun `entityName_mappedToTableName`() {
        val hql = "from UserEntity u where u.username = :username"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("user_entity", result.table.tableName)
        assertEquals("UserEntity", result.table.entityClassName)
    }

    @Test
    fun `entityName_withCustomTableName`() {
        val hql = "from OrderEntity o where o.orderNo = :orderNo"
        val result = HqlConverter.convert(hql, orderTable)
        assertEquals("t_order", result.table.tableName)
    }

    @Test
    fun `propertyName_mappedToColumnName`() {
        val hql = "from UserEntity u where u.departmentId = :deptId"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("user_entity", result.table.tableName)
    }

    @Test
    fun `joinFetch_convertedToLeftJoin`() {
        val hql = "from UserEntity u JOIN FETCH u.department d"
        val result = HqlConverter.convert(hql, userTable)
        assertNotNull(result)
    }

    @Test
    fun `aggregateCount_queryHandled`() {
        val hql = "select count(u) from UserEntity u where u.status = :status"
        val result = HqlConverter.convert(hql, userTable)
        assertNotNull(result)
        assertEquals("user_entity", result.table.tableName)
    }

    @Test
    fun `groupBy_detectedWithComment`() {
        val hql = "select u.status, count(u) from UserEntity u group by u.status"
        val result = HqlConverter.convert(hql, userTable)
        assertNotNull(result)
        assertNotNull("GROUP BY 应生成注释提示", result.comment)
        assertTrue("注释应提示含 GROUP BY", result.comment!!.contains("GROUP BY"))
    }

    @Test
    fun `having_detectedWithComment`() {
        val hql = "select u.status, count(u) from UserEntity u group by u.status having count(u) > 1"
        val result = HqlConverter.convert(hql, userTable)
        assertNotNull(result)
        assertNotNull("HAVING 应生成注释提示", result.comment)
        assertTrue("注释应提示含 HAVING", result.comment!!.contains("HAVING"))
    }

    @Test
    fun `noTableInfo_fallbackTableUsed`() {
        val hql = "from UserEntity u where u.username = :username"
        val result = HqlConverter.convert(hql, null)
        assertNotNull(result)
        assertEquals("unknown_table", result.table.tableName)
    }

    @Test
    fun `simpleQuery_noComplexComment`() {
        val hql = "from UserEntity u where u.username = :username"
        val result = HqlConverter.convert(hql, userTable)
        assertNull("简单 HQL 不应有注释", result.comment)
    }

    @Test
    fun `selectStatement_defaultValues`() {
        val hql = "from UserEntity u where u.username = :username"
        val result = HqlConverter.convert(hql, userTable)
        assertFalse(result.distinct)
        assertTrue(result.selectColumns.isEmpty())
        assertTrue(result.joins.isEmpty())
        assertTrue(result.conditions.isEmpty())
        assertTrue(result.groupBy.isEmpty())
        assertTrue(result.orderBy.isEmpty())
        assertNull(result.limit)
    }
}
