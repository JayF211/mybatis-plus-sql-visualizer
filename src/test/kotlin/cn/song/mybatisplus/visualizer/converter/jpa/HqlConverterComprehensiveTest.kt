package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HqlConverter 全方位测试——覆盖 demo 项目所有 HQL/JPQL 模式。
 * 注意：转换器保留表别名（如 FROM user u），这是正确的 SQL 行为。
 */
class HqlConverterComprehensiveTest {

    private lateinit var userTable: TableInfo
    private lateinit var orderTable: TableInfo
    private lateinit var departmentTable: TableInfo

    @Before
    fun setup() {
        userTable = TableInfo(
            entityClassName = "UserEntity",
            tableName = "user",
            columns = listOf(
                ColumnInfo("id", "id", isId = true),
                ColumnInfo("username", "username"),
                ColumnInfo("email", "email"),
                ColumnInfo("firstName", "first_name"),
                ColumnInfo("lastName", "last_name"),
                ColumnInfo("age", "age"),
                ColumnInfo("status", "status"),
                ColumnInfo("departmentId", "department_id"),
                ColumnInfo("createTime", "create_time"),
            ),
        )
        orderTable = TableInfo(
            entityClassName = "OrderEntity",
            tableName = "t_order",
            columns = listOf(
                ColumnInfo("id", "id", isId = true),
                ColumnInfo("orderNo", "order_no"),
                ColumnInfo("customerId", "customer_id"),
                ColumnInfo("totalAmount", "total_amount"),
                ColumnInfo("createTime", "create_time"),
            ),
        )
        departmentTable = TableInfo(
            entityClassName = "DepartmentEntity",
            tableName = "department",
            columns = listOf(
                ColumnInfo("id", "id", isId = true),
                ColumnInfo("name", "name"),
                ColumnInfo("code", "code"),
                ColumnInfo("parentId", "parent_id"),
            ),
        )
    }

    // ------------------------------------------------------------------
    // 1. 基本查询
    // ------------------------------------------------------------------

    @Test
    fun testBasicNamedParamQuery() {
        val hql = "SELECT u FROM UserEntity u WHERE u.username = :username"
        val result = HqlConverter.convert(hql, userTable)
        // 别名 u 保留（正确 SQL 行为）
        assertEquals("SELECT u FROM user u WHERE u.username = ?", result.sql)
        assertTrue("应含 1 个 ?", result.sql.count { it == '?' } == 1)
    }

    @Test
    fun testBasicPositionalParamQuery() {
        val hql = "SELECT u FROM UserEntity u WHERE u.username = ?1 AND u.age >= ?2"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("SELECT u FROM user u WHERE u.username = ? AND u.age >= ?", result.sql)
        assertTrue("应含 2 个 ?", result.sql.count { it == '?' } == 2)
    }

    // ------------------------------------------------------------------
    // 2. JOIN FETCH
    // ------------------------------------------------------------------

    @Test
    fun testJoinFetchWithNamedParam() {
        val hql = "SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = :deptName"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("应只有一个 SELECT", 1, result.sql.split("SELECT").size - 1)
        assertTrue("应含 LEFT JOIN", result.sql.contains("LEFT JOIN"))
        assertTrue("应含 ON", result.sql.contains(" ON "))
        assertFalse("不应含 JOIN FETCH", result.sql.contains("JOIN FETCH"))
        assertFalse("不应含 uLEFT（缺少空格）", result.sql.contains("uLEFT"))
    }

    @Test
    fun testJoinFetchWithPositionalParams() {
        val hql = "SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = ?1 AND u.status = ?2"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("应只有一个 SELECT", 1, result.sql.split("SELECT").size - 1)
        assertTrue("应含 LEFT JOIN", result.sql.contains("LEFT JOIN"))
        assertTrue("应含 2 个 ?", result.sql.count { it == '?' } == 2)
    }

    @Test
    fun testLeftJoinFetchDistinct() {
        val hql = "SELECT DISTINCT u FROM UserEntity u LEFT JOIN FETCH u.department d WHERE d.name = :deptName"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("应含 DISTINCT", result.sql.contains("DISTINCT"))
        assertTrue("应含 LEFT JOIN", result.sql.contains("LEFT JOIN"))
        assertFalse("不应含 JOIN FETCH", result.sql.contains("JOIN FETCH"))
    }

    // ------------------------------------------------------------------
    // 3. 聚合查询
    // ------------------------------------------------------------------

    @Test
    fun testAggregateQuery() {
        val hql = "SELECT COUNT(u), MAX(u.age), AVG(u.age) FROM UserEntity u WHERE u.status = :status"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("应含 COUNT", result.sql.contains("COUNT"))
        assertTrue("应含 MAX", result.sql.contains("MAX"))
        assertTrue("应含 AVG", result.sql.contains("AVG"))
        assertFalse("不应含 :status", result.sql.contains(":status"))
    }

    @Test
    fun testCountQuery() {
        val hql = "SELECT COUNT(u) FROM UserEntity u WHERE u.status = :status"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("应含 COUNT", result.sql.contains("COUNT"))
        assertEquals("SELECT COUNT(u) FROM user u WHERE u.status = ?", result.sql)
    }

    // ------------------------------------------------------------------
    // 4. UPDATE / DELETE
    // ------------------------------------------------------------------

    @Test
    fun testUpdateQuery() {
        val hql = "UPDATE UserEntity u SET u.status = :newStatus WHERE u.status = :oldStatus"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("应含 UPDATE", result.sql.contains("UPDATE"))
        assertTrue("应含 SET", result.sql.contains("SET"))
        assertTrue("应含 2 个 ?", result.sql.count { it == '?' } == 2)
    }

    @Test
    fun testDeleteQuery() {
        val hql = "DELETE FROM UserEntity u WHERE u.age < :maxAge"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("应含 DELETE", result.sql.contains("DELETE"))
        assertEquals("DELETE FROM user u WHERE u.age < ?", result.sql)
    }

    // ------------------------------------------------------------------
    // 5. IN 子句
    // ------------------------------------------------------------------

    @Test
    fun testInClauseQuery() {
        val hql = "SELECT u FROM UserEntity u WHERE u.status IN :statuses"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("应含 IN (?)", result.sql.contains("IN (?)"))
        assertTrue("IN 参数应被标记", result.inClauseParams.contains("statuses"))
    }

    @Test
    fun testNotInQuery() {
        val hql = "SELECT u FROM UserEntity u WHERE u.status NOT IN :excluded"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("应含 NOT IN", result.sql.contains("NOT IN"))
        assertTrue("NOT IN 参数应被标记", result.inClauseParams.contains("excluded"))
    }

    // ------------------------------------------------------------------
    // 6. BETWEEN
    // ------------------------------------------------------------------

    @Test
    fun testBetweenQuery() {
        val hql = "SELECT u FROM UserEntity u WHERE u.age BETWEEN :start AND :end"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("应含 BETWEEN", result.sql.contains("BETWEEN"))
        assertTrue("应含 2 个 ?", result.sql.count { it == '?' } == 2)
    }

    // ------------------------------------------------------------------
    // 7. LIKE CONCAT
    // ------------------------------------------------------------------

    @Test
    fun testLikeConcatQuery() {
        val hql = "SELECT u FROM UserEntity u WHERE u.username LIKE CONCAT('%', :keyword, '%')"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("应含 LIKE", result.sql.contains("LIKE"))
        assertTrue("应含 CONCAT", result.sql.contains("CONCAT"))
        assertFalse("不应含 :keyword", result.sql.contains(":keyword"))
    }

    // ------------------------------------------------------------------
    // 8. 子查询 EXISTS
    // ------------------------------------------------------------------

    @Test
    fun testExistsSubquery() {
        val hql = "SELECT u FROM UserEntity u WHERE EXISTS (SELECT o FROM OrderEntity o WHERE o.customerId = u.id AND o.totalAmount >= :minAmount)"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("应含 EXISTS", result.sql.contains("EXISTS"))
        assertTrue("应含子查询 SELECT", result.sql.contains("SELECT o"))
        assertFalse("不应含 :minAmount", result.sql.contains(":minAmount"))
    }

    // ------------------------------------------------------------------
    // 9. NEW DTO
    // ------------------------------------------------------------------

    @Test
    fun testNewDtoQuery() {
        val hql = "SELECT NEW cn.song.jpademo.dto.OrderStatDTO(o.customerId, COUNT(o), SUM(o.totalAmount)) FROM OrderEntity o GROUP BY o.customerId HAVING COUNT(o) > 1"
        val result = HqlConverter.convert(hql, orderTable)
        assertFalse("不应含 NEW", result.sql.contains("NEW "))
        // customerId → customer_id（属性名→列名映射）
        assertTrue("应含 customer_id", result.sql.contains("customer_id"))
        assertTrue("应含 COUNT", result.sql.contains("COUNT"))
        assertTrue("应含 SUM", result.sql.contains("SUM"))
        assertTrue("应含 GROUP BY", result.sql.contains("GROUP BY"))
        assertTrue("应含 HAVING", result.sql.contains("HAVING"))
    }

    // ------------------------------------------------------------------
    // 10. DISTINCT + 多条件组合
    // ------------------------------------------------------------------

    @Test
    fun testDistinctMultiCondition() {
        val hql = "SELECT DISTINCT u FROM UserEntity u WHERE u.status IN :statuses AND u.age BETWEEN :minAge AND :maxAge AND u.username LIKE CONCAT('%', :keyword, '%') ORDER BY u.id"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("应含 DISTINCT", result.sql.contains("DISTINCT"))
        assertTrue("应含 IN (?)", result.sql.contains("IN (?)"))
        assertTrue("应含 BETWEEN", result.sql.contains("BETWEEN"))
        assertTrue("应含 LIKE", result.sql.contains("LIKE"))
        assertTrue("应含 ORDER BY", result.sql.contains("ORDER BY"))
        assertTrue("IN 参数应被标记", result.inClauseParams.contains("statuses"))
    }

    // ------------------------------------------------------------------
    // 11. 原生 SQL
    // ------------------------------------------------------------------

    @Test
    fun testNativeQuery() {
        val hql = "SELECT * FROM user WHERE username LIKE CONCAT('%', :keyword, '%') AND status = :status"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("应含 SELECT *", result.sql.contains("SELECT *"))
        assertTrue("应含 LIKE", result.sql.contains("LIKE"))
        assertFalse("不应含 :keyword", result.sql.contains(":keyword"))
        assertFalse("不应含 :status", result.sql.contains(":status"))
    }

    // ------------------------------------------------------------------
    // 12. 无 TableInfo（fallback 场景）
    // ------------------------------------------------------------------

    @Test
    fun testFallbackNoDuplication() {
        val hql = "SELECT u FROM UserEntity u WHERE u.username = :username"
        val result = HqlConverter.convert(hql, null)

        // 不应重复
        assertEquals("应只有一个 SELECT", 1, result.sql.split("SELECT").size - 1)

        // FROM 子句中的实体名应被替换为 unknown_table（不区分大小写）
        assertTrue("SQL 应含 unknown_table", result.sql.lowercase().contains("unknown_table"))
        assertFalse("不应含 UserEntity", result.sql.contains("UserEntity"))
    }

    @Test
    fun testFallbackJoinFetch() {
        val hql = "SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = :deptName"
        val result = HqlConverter.convert(hql, null)

        // 不应重复
        assertEquals("应只有一个 SELECT", 1, result.sql.split("SELECT").size - 1)

        // 验证格式
        assertTrue("应含 LEFT JOIN", result.sql.contains("LEFT JOIN"))
        assertTrue("应含 ON", result.sql.contains(" ON "))
        assertFalse("不应含 uLEFT", result.sql.contains("uLEFT"))
    }

    // ------------------------------------------------------------------
    // 13. 属性名→列名映射
    // ------------------------------------------------------------------

    @Test
    fun testPropertyToColumnMapping() {
        val hql = "SELECT u FROM UserEntity u WHERE u.firstName = :firstName AND u.lastName = :lastName AND u.departmentId = :deptId"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("应含 first_name", result.sql.contains("first_name"))
        assertTrue("应含 last_name", result.sql.contains("last_name"))
        assertTrue("应含 department_id", result.sql.contains("department_id"))
    }

    // ------------------------------------------------------------------
    // 14. IS NULL / IS NOT NULL
    // ------------------------------------------------------------------

    @Test
    fun testIsNullQuery() {
        val hql = "SELECT u FROM UserEntity u WHERE u.email IS NULL"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("SELECT u FROM user u WHERE u.email IS NULL", result.sql)
    }

    @Test
    fun testIsNotNullQuery() {
        val hql = "SELECT u FROM UserEntity u LEFT JOIN u.department d WHERE d.id IS NOT NULL"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("应含 IS NOT NULL", result.sql.contains("IS NOT NULL"))
    }

    // ------------------------------------------------------------------
    // 15. 综合断言：所有查询的 SQL 不应重复
    // ------------------------------------------------------------------

    @Test
    fun testNoQueryProducesDuplicatedSql() {
        val queries = listOf(
            "SELECT u FROM UserEntity u WHERE u.username = :username",
            "SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = :deptName",
            "SELECT DISTINCT u FROM UserEntity u WHERE u.status IN :statuses AND u.age BETWEEN :minAge AND :maxAge",
            "SELECT COUNT(u) FROM UserEntity u WHERE u.status = :status",
        )
        for (hql in queries) {
            val result = HqlConverter.convert(hql, userTable)
            // 验证主表名只出现一次（在 FROM 子句中）
            val fromCount = Regex("\\bFROM\\s+user\\b", RegexOption.IGNORE_CASE).findAll(result.sql).count()
            assertTrue("SQL 不应重复: $hql", fromCount <= 1)
        }
    }
}
