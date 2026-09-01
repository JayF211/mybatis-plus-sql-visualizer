package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HqlConverter 单元测试。
 *
 * 核心验证：转换后的 SQL 字符串完整保留原始 HQL 结构（SELECT 列、FROM 表、WHERE 条件），
 * 仅做参数替换和实体/属性映射。
 */
class HqlConverterTest {

    private val userTable = TableInfo("UserEntity", "user_entity", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("username", "username"),
            ColumnInfo("status", "status"),
            ColumnInfo("age", "age"),
            ColumnInfo("departmentId", "department_id"),
            ColumnInfo("createTime", "create_time"),
        ))

    private val orderTable = TableInfo("OrderEntity", "t_order", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("orderNo", "order_no"),
            ColumnInfo("customerId", "customer_id"),
            ColumnInfo("totalAmount", "total_amount"),
        ))

    // ------------------------------------------------------------------
    // 参数替换
    // ------------------------------------------------------------------

    @Test
    fun `namedParameter_replacedWithPlaceholder`() {
        val hql = "from UserEntity u where u.username = :username"
        val result = HqlConverter.convert(hql, userTable)
        assertNotNull(result)
        // SQL 结构完整保留
        assertTrue("SQL 应含 from", result.sql.contains("from"))
        assertTrue("SQL 应含 user_entity", result.sql.contains("user_entity"))
        assertTrue("SQL 应含 where", result.sql.contains("where"))
        assertTrue("SQL 应含 ? 占位符", result.sql.contains("?"))
    }

    @Test
    fun `namedParameter_multipleParams`() {
        val hql = "from UserEntity u where u.username = :username and u.status = :status"
        val result = HqlConverter.convert(hql, userTable)
        val placeholderCount = result.sql.count { it == '?' }
        assertEquals("应有两个 ? 占位符", 2, placeholderCount)
    }

    @Test
    fun `positionalParameter_replacedWithPlaceholder`() {
        val hql = "from UserEntity u where u.username = ?1"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("SQL 应含 ?", result.sql.contains("?"))
        assertFalse("SQL 不应含 ?1", result.sql.contains("?1"))
    }

    // ------------------------------------------------------------------
    // 实体名 → 表名映射
    // ------------------------------------------------------------------

    @Test
    fun `entityName_mappedToTableName`() {
        val hql = "from UserEntity u where u.username = :username"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("user_entity", result.statement.table.tableName)
        assertEquals("UserEntity", result.statement.table.entityClassName)
        // SQL 中实体名已被替换为表名
        assertTrue("SQL 应含 from user_entity", result.sql.contains("from user_entity"))
        assertFalse("SQL 不应含 UserEntity", result.sql.contains("UserEntity"))
    }

    @Test
    fun `entityName_withCustomTableName`() {
        val hql = "from OrderEntity o where o.orderNo = :orderNo"
        val result = HqlConverter.convert(hql, orderTable)
        assertEquals("t_order", result.statement.table.tableName)
        assertTrue("SQL 应含 from t_order", result.sql.contains("from t_order"))
    }

    @Test
    fun `propertyName_mappedToColumnName`() {
        val hql = "from UserEntity u where u.departmentId = :deptId"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("SQL 应含 department_id", result.sql.contains("department_id"))
    }

    // ------------------------------------------------------------------
    // JOIN FETCH
    // ------------------------------------------------------------------

    @Test
    fun `joinFetch_convertedToLeftJoin`() {
        val hql = "from UserEntity u JOIN FETCH u.department d"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("SQL 应含 LEFT JOIN", result.sql.contains("LEFT JOIN"))
        assertTrue("SQL 应含 department", result.sql.contains("department"))
        assertTrue("SQL 应含 ON", result.sql.contains("ON"))
        // 不应残留原始 JOIN FETCH
        assertFalse("SQL 不应含 JOIN FETCH", result.sql.contains("JOIN FETCH"))
    }

    @Test
    fun `joinFetch_withAlias_aliasPreserved`() {
        // 这是用户反馈的核心问题：JOIN FETCH 转换后不应残留别名 d
        val hql = "SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = :deptName"
        val result = HqlConverter.convert(hql, userTable)
        // SQL 结构应完整
        assertTrue("SQL 应含 SELECT u", result.sql.contains("SELECT u"))
        assertTrue("SQL 应含 LEFT JOIN", result.sql.contains("LEFT JOIN"))
        assertTrue("SQL 应含 WHERE", result.sql.contains("WHERE"))
        assertTrue("SQL 应含 ?", result.sql.contains("?"))
        // 不应出现重复
        val selectCount = result.sql.split("SELECT").size - 1
        assertEquals("应只有一个 SELECT", 1, selectCount)
        val fromCount = result.sql.split("FROM").size - 1
        assertEquals("应只有一个 FROM", 1, fromCount)
    }

    // ------------------------------------------------------------------
    // IN 子句
    // ------------------------------------------------------------------

    @Test
    fun `inClause_fixedParentheses`() {
        val hql = "from UserEntity u where u.status IN :statuses"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("SQL 应含 IN (?)", result.sql.contains("IN (?)"))
    }

    // ------------------------------------------------------------------
    // 无 TableInfo（fallback）
    // ------------------------------------------------------------------

    @Test
    fun `noTableInfo_preserveOriginalStructure`() {
        val hql = "from UserEntity u where u.username = :username"
        val result = HqlConverter.convert(hql, null)
        assertNotNull(result)
        assertEquals("unknown_table", result.statement.table.tableName)
        // Fallback：实体不可解析时，FROM 子句中的实体名替换为 unknown_table
        assertTrue("SQL 应含 from unknown_table", result.sql.contains("from unknown_table"))
        assertTrue("SQL 应含 where", result.sql.contains("where"))
        assertTrue("SQL 应含 ?", result.sql.contains("?"))
    }

    // ------------------------------------------------------------------
    // 自定义 DAO 场景
    // ------------------------------------------------------------------

    @Test
    fun `customDao_joinWithNamedParam`() {
        val hql = "select count(1) from WmsZone z " +
            "left join WmsLocation loc on loc.zoneId = z.id " +
            "where loc.id in (:locIdList) and z.beAutoZone = true"
        val wmsZoneTable = TableInfo("WmsZone", "wms_zone", listOf(
                ColumnInfo("id", "id", true),
                ColumnInfo("beAutoZone", "be_auto_zone"),
            ))
        val result = HqlConverter.convert(hql, wmsZoneTable)
        assertEquals("wms_zone", result.statement.table.tableName)
        assertTrue("SQL 应含 IN (?)", result.sql.contains("in (?)"))
    }

    @Test
    fun `customDao_updateHql`() {
        val hql = "update UserEntity u set status = :newStatus where status = :oldStatus"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("user_entity", result.statement.table.tableName)
        assertTrue("SQL 应含 update user_entity", result.sql.contains("update user_entity"))
        assertTrue("SQL 应含 set", result.sql.contains("set"))
    }

    @Test
    fun `customDao_deleteHql`() {
        val hql = "delete from UserEntity u where status = :status"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("user_entity", result.statement.table.tableName)
        assertTrue("SQL 应含 delete from user_entity", result.sql.contains("delete from user_entity"))
    }

    // ------------------------------------------------------------------
    // 核心回归测试：SQL 结构完整性
    // ------------------------------------------------------------------

    @Test
    fun `jpqlFullStructure_preserved`() {
        // 用户反馈的核心问题：SQL 预览显示 "SELECT FROM unknown_table"
        // 验证转换后的 SQL 完整保留 SELECT 列、FROM 表、WHERE 条件
        val hql = "SELECT u FROM UserEntity u WHERE u.username = :username"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("SQL 应含 SELECT u", result.sql.contains("SELECT u"))
        assertTrue("SQL 应含 FROM user_entity", result.sql.contains("FROM user_entity"))
        assertTrue("SQL 应含 WHERE", result.sql.contains("WHERE"))
        assertTrue("SQL 应含 ?", result.sql.contains("?"))
        // 不应出现 "SELECT FROM" 这种缺失列的情况
        assertFalse("SQL 不应含 SELECT FROM", result.sql.contains("SELECT FROM"))
    }

    @Test
    fun `jpqlMultipleConditions_preserved`() {
        val hql = "SELECT u FROM UserEntity u WHERE u.username = ?1 AND u.age >= ?2"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("SQL 应含 SELECT u", result.sql.contains("SELECT u"))
        assertTrue("SQL 应含 FROM user_entity", result.sql.contains("FROM user_entity"))
        assertTrue("SQL 应含 WHERE", result.sql.contains("WHERE"))
        val placeholderCount = result.sql.count { it == '?' }
        assertEquals("应有两个 ? 占位符", 2, placeholderCount)
    }

    @Test
    fun `aggregateCount_preserved`() {
        val hql = "select count(u) from UserEntity u where u.status = :status"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("SQL 应含 count", result.sql.contains("count"))
        assertTrue("SQL 应含 from user_entity", result.sql.contains("from user_entity"))
    }

    @Test
    fun `newDtoExpression_handled`() {
        val hql = "SELECT NEW cn.song.dto.OrderStatDTO(o.customerId, COUNT(o), SUM(o.totalAmount)) " +
            "FROM OrderEntity o GROUP BY o.customerId HAVING COUNT(o) > 1"
        val result = HqlConverter.convert(hql, orderTable)
        // NEW DTO 应被替换为构造参数，属性名→列名映射生效
        assertTrue("SQL 应含 customer_id: [${result.sql}]", result.sql.contains("customer_id"))
        assertTrue("SQL 应含 COUNT: [${result.sql}]", result.sql.contains("COUNT"))
        assertFalse("SQL 不应含 NEW: [${result.sql}]", result.sql.contains("NEW "))
    }
}
