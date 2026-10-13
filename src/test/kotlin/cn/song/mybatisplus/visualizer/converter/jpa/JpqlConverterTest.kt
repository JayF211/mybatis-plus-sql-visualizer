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
        // 验证转换成功：SQL 中含 ? 占位符，statement 表名正确
        assertNotNull(result)
        assertEquals("user", result.statement.table.tableName)
        assertTrue("转换后 SQL 应含 ? 占位符: ${result.sql}", result.sql.contains("?"))
        assertFalse("转换后 SQL 不应含 :username: ${result.sql}", result.sql.contains(":username"))
        // 占位符数量应 == 参数数量
        assertEquals("占位符数量应为 1", 1, result.sql.count { it == '?' })
        // statement 类型验证
        assertTrue("statement 类型应为 SelectStatement",
            result.statement is SelectStatement)
    }

    @Test
    fun `namedParameter_multipleParams`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.username = :username AND u.age = :age"
        val result = JpqlConverter.convert(jpql, userTable)
        // 两个命名参数都应被替换为 ?
        assertEquals("两个参数应生成两个 ?", 2, result.sql.count { it == '?' })
    }

    @Test
    fun `namedParameter_noParams`() {
        val jpql = "SELECT u FROM UserEntity u"
        val result = JpqlConverter.convert(jpql, userTable)
        assertFalse("无参数 SQL 不应含 ?: ${result.sql}", result.sql.contains("?"))
    }

    // ------------------------------------------------------------------
    // 位置参数替换测试
    // ------------------------------------------------------------------

    @Test
    fun `positionalParameter_replacedWithPlaceholder`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.username = ?1"
        val result = JpqlConverter.convert(jpql, userTable)
        assertTrue("位置参数应转换为 ?: ${result.sql}", result.sql.contains("?"))
        assertFalse("不应含 ?1: ${result.sql}", result.sql.contains("?1"))
        // 占位符数量应 == 参数数量
        assertEquals("占位符数量应为 1", 1, result.sql.count { it == '?' })
        // statement 表名验证
        assertEquals("user", result.statement.table.tableName)
    }

    @Test
    fun `positionalParameter_multipleParams`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.username = ?1 AND u.age = ?2"
        val result = JpqlConverter.convert(jpql, userTable)
        assertEquals("两个位置参数应生成两个 ?", 2, result.sql.count { it == '?' })
    }

    // ------------------------------------------------------------------
    // 实体名 → 表名映射测试
    // ------------------------------------------------------------------

    @Test
    fun `entityName_mappedToTableName`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.username = :username"
        val result = JpqlConverter.convert(jpql, userTable)
        assertEquals("user", result.statement.table.tableName)
        assertEquals("UserEntity", result.statement.table.entityClassName)
    }

    @Test
    fun `entityName_withCustomTableName`() {
        val jpql = "SELECT o FROM OrderEntity o WHERE o.orderNo = :orderNo"
        val result = JpqlConverter.convert(jpql, orderTable)
        assertEquals("t_order", result.statement.table.tableName)
        // 参数应被替换为 ?
        assertTrue("SQL 应含 ?: ${result.sql}", result.sql.contains("?"))
        assertFalse("不应残留 :orderNo: ${result.sql}", result.sql.contains(":orderNo"))
        assertEquals("占位符数量应为 1", 1, result.sql.count { it == '?' })
    }

    // ------------------------------------------------------------------
    // 属性名 → 列名映射测试
    // ------------------------------------------------------------------

    @Test
    fun `propertyName_mappedToColumnName`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.departmentId = :deptId"
        val result = JpqlConverter.convert(jpql, userTable)
        // departmentId → department_id 映射应生效
        assertEquals("user", result.statement.table.tableName)
        // 参数应被替换为 ?
        assertTrue("SQL 应含 ?: ${result.sql}", result.sql.contains("?"))
        assertFalse("不应残留 :deptId: ${result.sql}", result.sql.contains(":deptId"))
        assertEquals("占位符数量应为 1", 1, result.sql.count { it == '?' })
    }

    @Test
    fun `propertyName_snakeCaseMapping`() {
        val jpql = "SELECT o FROM OrderEntity o WHERE o.totalAmount > :minAmount"
        val result = JpqlConverter.convert(jpql, orderTable)
        assertEquals("t_order", result.statement.table.tableName)
        // 参数应被替换为 ?
        assertTrue("SQL 应含 ?: ${result.sql}", result.sql.contains("?"))
        assertFalse("不应残留 :minAmount: ${result.sql}", result.sql.contains(":minAmount"))
        assertEquals("占位符数量应为 1", 1, result.sql.count { it == '?' })
    }

    // ------------------------------------------------------------------
    // JOIN FETCH 处理测试
    // ------------------------------------------------------------------

    @Test
    fun `joinFetch_convertedToLeftJoin`() {
        val jpql = "SELECT u FROM UserEntity u JOIN FETCH u.department d"
        val result = JpqlConverter.convert(jpql, userTable)
        assertNotNull(result)
        // JOIN FETCH 应转换为 LEFT JOIN
        assertTrue("SQL 应含 JOIN: ${result.sql}", result.sql.contains("JOIN", ignoreCase = true))
        assertEquals("user", result.statement.table.tableName)
    }

    @Test
    fun `leftJoinFetch_convertedToLeftJoin`() {
        val jpql = "SELECT u FROM UserEntity u LEFT JOIN FETCH u.department d"
        val result = JpqlConverter.convert(jpql, userTable)
        assertNotNull(result)
        // LEFT JOIN FETCH 应转换为 LEFT JOIN
        assertTrue("SQL 应含 LEFT JOIN: ${result.sql}", result.sql.contains("LEFT", ignoreCase = true))
        assertEquals("user", result.statement.table.tableName)
    }

    @Test
    fun `innerJoinFetch_convertedToInnerJoin`() {
        val jpql = "SELECT u FROM UserEntity u INNER JOIN FETCH u.department d"
        val result = JpqlConverter.convert(jpql, userTable)
        assertNotNull(result)
        // INNER JOIN FETCH 应转换为 INNER JOIN
        assertTrue("SQL 应含 INNER JOIN: ${result.sql}", result.sql.contains("INNER", ignoreCase = true))
        assertEquals("user", result.statement.table.tableName)
    }

    // ------------------------------------------------------------------
    // NEW DTO 表达式处理测试
    // ------------------------------------------------------------------

    @Test
    fun `newExpression_extracted`() {
        val jpql = "SELECT NEW com.example.UserDTO(u.username, u.email) FROM UserEntity u"
        val result = JpqlConverter.convert(jpql, userTable)
        val selectStmt = result.statement as SelectStatement
        // NEW DTO 应触发复杂语法注释
        assertNotNull("NEW DTO 应生成注释提示", selectStmt.comment)
        assertTrue(
            "注释应提示复杂语法",
            selectStmt.comment!!.contains("复杂语法"),
        )
        // NEW DTO 构造表达式应被提取为列引用
        assertTrue("NEW DTO 参数应被提取: ${result.sql}",
            result.sql.contains("u.username") && result.sql.contains("u.email"))
    }

    /**
     * T-HqlBufConcat：小写 `new Map(...)` 同样必须剥离（旧实现字面量 "NEW" 嗅探摘不掉小写形态）。
     */
    @Test
    fun `lowercaseNewMap_extracted`() {
        val jpql = "SELECT new Map(u.username AS un, u.email AS em) FROM UserEntity u"
        val result = JpqlConverter.convert(jpql, userTable)

        assertFalse("不应含 new Map: ${result.sql}", result.sql.contains("new Map"))
        assertTrue("构造实参应被提取: ${result.sql}", result.sql.contains("u.username"))
        assertTrue("自定义别名应保留: ${result.sql}", result.sql.contains("un"))
        assertTrue("应含 FROM user: ${result.sql}", result.sql.contains("FROM user"))
    }

    // ------------------------------------------------------------------
    // MEMBER OF 降级处理测试
    // ------------------------------------------------------------------

    @Test
    fun `memberOf_degradedWithComment`() {
        val jpql = "SELECT u FROM UserEntity u WHERE :role MEMBER OF u.roles"
        val result = JpqlConverter.convert(jpql, userTable)
        val selectStmt = result.statement as SelectStatement
        // MEMBER OF 应触发复杂语法注释
        assertNotNull("MEMBER OF 应生成注释提示", selectStmt.comment)
    }

    // ------------------------------------------------------------------
    // 复杂语法检测测试
    // ------------------------------------------------------------------

    @Test
    fun `complexSyntax_subquery_detected`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.id IN (SELECT o.customerId FROM OrderEntity o)"
        val result = JpqlConverter.convert(jpql, userTable)
        val selectStmt = result.statement as SelectStatement
        // 子查询应触发复杂语法注释
        assertNotNull("子查询应生成注释提示", selectStmt.comment)
    }

    @Test
    fun `simpleSyntax_noComment`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.username = :username"
        val result = JpqlConverter.convert(jpql, userTable)
        val selectStmt = result.statement as SelectStatement
        // 简单 JPQL 不应有注释
        assertNull("简单 JPQL 不应有注释", selectStmt.comment)
    }

    // ------------------------------------------------------------------
    // 无 TableInfo 兜底测试
    // ------------------------------------------------------------------

    @Test
    fun `noTableInfo_fallbackTableUsed`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.username = :username"
        val result = JpqlConverter.convert(jpql, null)
        assertNotNull(result)
        assertEquals("unknown_table", result.statement.table.tableName)
    }

    // ------------------------------------------------------------------
    // 综合场景测试
    // ------------------------------------------------------------------

    @Test
    fun `complexQuery_multipleTransformations`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.username = :username AND u.age > ?1 AND u.departmentId = :deptId"
        val result = JpqlConverter.convert(jpql, userTable)
        assertEquals("user", result.statement.table.tableName)
        // 3 个参数（2 命名 + 1 位置）应生成 3 个 ?
        assertEquals("应生成 3 个 ?", 3, result.sql.count { it == '?' })
    }

    @Test
    fun `countQuery_converted`() {
        val jpql = "SELECT COUNT(u) FROM UserEntity u WHERE u.departmentId = :deptId"
        val result = JpqlConverter.convert(jpql, userTable)
        assertEquals("user", result.statement.table.tableName)
        // COUNT 查询参数应被替换为 ?
        assertTrue("SQL 应含 ?: ${result.sql}", result.sql.contains("?"))
        assertFalse("不应残留 :deptId: ${result.sql}", result.sql.contains(":deptId"))
        assertEquals("占位符数量应为 1", 1, result.sql.count { it == '?' })
    }

    @Test
    fun `joinQuery_withWhereClause`() {
        val jpql = "SELECT u FROM UserEntity u JOIN u.department d WHERE d.name = :deptName"
        val result = JpqlConverter.convert(jpql, userTable)
        assertNotNull(result)
        // JOIN 应存在，参数应被替换为 ?
        assertTrue("SQL 应含 JOIN: ${result.sql}", result.sql.contains("JOIN", ignoreCase = true))
        assertTrue("SQL 应含 ?: ${result.sql}", result.sql.contains("?"))
        assertFalse("不应残留 :deptName: ${result.sql}", result.sql.contains(":deptName"))
        assertEquals("占位符数量应为 1", 1, result.sql.count { it == '?' })
    }

    @Test
    fun `orderByQuery_converted`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.departmentId = :deptId ORDER BY u.createTime DESC"
        val result = JpqlConverter.convert(jpql, userTable)
        assertNotNull(result)
        // ORDER BY 应存在，参数应被替换为 ?
        assertTrue("SQL 应含 ORDER BY: ${result.sql}", result.sql.contains("ORDER BY", ignoreCase = true))
        assertTrue("SQL 应含 ?: ${result.sql}", result.sql.contains("?"))
        assertFalse("不应残留 :deptId: ${result.sql}", result.sql.contains(":deptId"))
        assertEquals("user", result.statement.table.tableName)
    }

    // ------------------------------------------------------------------
    // SelectStatement 结构验证
    // ------------------------------------------------------------------

    @Test
    fun `selectStatement_tableInfoCorrect`() {
        val jpql = "SELECT u FROM UserEntity u"
        val result = JpqlConverter.convert(jpql, userTable)
        assertEquals("UserEntity", result.statement.table.entityClassName)
        assertEquals("user", result.statement.table.tableName)
        assertEquals(6, result.statement.table.columns.size)
    }

    @Test
    fun `selectStatement_defaultValues`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.username = :username"
        val result = JpqlConverter.convert(jpql, userTable)
        val selectStmt = result.statement as SelectStatement
        // 默认值验证
        assertFalse(selectStmt.distinct)
        assertTrue(selectStmt.selectColumns.isEmpty())
        assertTrue(selectStmt.joins.isEmpty())
        assertTrue(selectStmt.conditions.isEmpty())
        assertTrue(selectStmt.groupBy.isEmpty())
        assertTrue(selectStmt.orderBy.isEmpty())
        assertNull(selectStmt.limit)
    }

    // ------------------------------------------------------------------
    // DELETE JPQL（P2 修复）
    // ------------------------------------------------------------------

    @Test
    fun `deleteJpql_returnsDeleteStatement`() {
        val jpql = "DELETE FROM UserEntity u WHERE u.age < :maxAge"
        val result = JpqlConverter.convert(jpql, userTable)
        // DELETE JPQL 应返回 DeleteStatement（而非 SelectStatement）
        assertTrue("DELETE JPQL 应返回 DeleteStatement, 实际: ${result.statement::class.simpleName}",
            result.statement is cn.song.mybatisplus.visualizer.model.DeleteStatement)
        // 转换后 SQL 应含 ? 占位符
        assertTrue("DELETE SQL 应含 ?: ${result.sql}", result.sql.contains("?"))
    }

    // ------------------------------------------------------------------
    // NEW DTO + GROUP BY + HAVING（statByCustomer 用例）
    // ------------------------------------------------------------------

    @Test
    fun `newDtoGroupByHaving_complexSyntax`() {
        val jpql = "SELECT NEW cn.song.jpademo.dto.OrderStatDTO(o.customerId, COUNT(o), SUM(o.totalAmount)) " +
            "FROM OrderEntity o GROUP BY o.customerId HAVING COUNT(o) > 1"
        val result = JpqlConverter.convert(jpql, orderTable)
        val selectStmt = result.statement as SelectStatement
        // 复杂语法应触发注释
        assertNotNull("NEW DTO + GROUP BY + HAVING 应生成复杂语法注释", selectStmt.comment)
        // NEW DTO 构造表达式应被提取（属性名已映射为列名：customerId → customer_id）
        assertTrue("NEW DTO 参数应被提取: ${result.sql}",
            result.sql.contains("o.customer_id") && result.sql.contains("COUNT(o)"))
    }

    // ------------------------------------------------------------------
    // IN 子句测试
    // ------------------------------------------------------------------

    @Test
    fun `inClause_namedParameter_inParens`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.status IN :statuses"
        val result = JpqlConverter.convert(jpql, userTable)
        // IN 子句应展开为 IN (?) 而非 IN ?
        assertTrue("IN 子句应为 IN (?): ${result.sql}", result.sql.contains("IN (?)"))
    }

    @Test
    fun `inClause_positionalParameter_inParens`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.status IN ?1"
        val result = JpqlConverter.convert(jpql, userTable)
        // 位置参数 IN ?1 → IN (?)
        assertTrue("位置参数 IN 应为 IN (?): ${result.sql}", result.sql.contains("IN (?)"))
    }

    @Test
    fun `inClause_multipleInClauses_allExpanded`() {
        val jpql = "SELECT u FROM UserEntity u WHERE u.status IN :statuses AND u.role IN :roles"
        val result = JpqlConverter.convert(jpql, userTable)
        // 两个 IN 子句都应展开
        val count = result.sql.count { it == '?' }
        assertEquals("应有 2 个 IN (?) 占位符", 2, count)
        assertFalse("不应有裸 IN ?: ${result.sql}", result.sql.contains("IN ?)"))
    }

    // ------------------------------------------------------------------
    // SELECT 子句展开（T-BareFromSelect）：与 HqlConverter 共用 SelectClauseExpander
    // ------------------------------------------------------------------

    @Test
    fun `bareFrom_implicitSelect_expandsColumnList`() {
        // @NamedQuery 常见 `from Entity` 形态，此前只输出 FROM 表名
        val result = JpqlConverter.convert("from UserEntity", userTable)
        assertEquals(
            "SELECT user.id, user.username, user.email, user.age, user.department_id, user.create_time from user",
            result.sql,
        )
    }

    @Test
    fun `selectAlias_expandsColumnList`() {
        // HQL/JPQL 语义 `SELECT u` = 取整个实体，应展开为全部映射列（对齐 HqlConverter）
        val result = JpqlConverter.convert("SELECT u FROM UserEntity u WHERE u.username = :username", userTable)
        assertEquals(
            "SELECT u.id, u.username, u.email, u.age, u.department_id, u.create_time " +
                "FROM user u WHERE u.username = ?",
            result.sql,
        )
    }

    @Test
    fun `explicitColumns_notExpandedBySharedExpander`() {
        // 列引用（含点号）不在展开白名单，应原样保留
        val result = JpqlConverter.convert("SELECT u.username, u.email FROM UserEntity u", userTable)
        assertEquals("SELECT u.username, u.email FROM user u", result.sql)
    }
}
