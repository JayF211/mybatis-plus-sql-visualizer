package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.AssociationInfo
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DerivedQueryConverter JOIN 构建测试（JPA 嵌套对象 JOIN 查询支持）。
 *
 * 覆盖：
 * - findByAddressCity 生成 LEFT JOIN
 * - findByAddressCityAndName 混合条件
 * - findByDepartmentName 单嵌套条件
 * - findByAddressCityAndAddressStreet 同表多字段
 * - findByAddressCityAndAddressCountry 同关联去重
 * - findByAddressCityAndDepartmentName 多表 JOIN
 * - findByCity 非嵌套属性不生成 JOIN
 * - findByUnknownField 关联未找到 → 降级
 */
class DerivedQueryJoinConverterTest {

    /** 含 address 关联的 TableInfo。 */
    private val tableWithAssociations = TableInfo(
        entityClassName = "UserEntity",
        tableName = "user",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("firstName", "first_name"),
            ColumnInfo("lastName", "last_name"),
            ColumnInfo("name", "name"),
            ColumnInfo("age", "age"),
            ColumnInfo("status", "status"),
            ColumnInfo("addressId", "address_id"),
            ColumnInfo("departmentId", "department_id"),
        ),
        associations = listOf(
            AssociationInfo(
                fieldName = "address",
                joinColumn = "address_id",
                targetTable = "address",
                targetAlias = "address",
            ),
            AssociationInfo(
                fieldName = "department",
                joinColumn = "department_id",
                targetTable = "department",
                targetAlias = "department",
            ),
        ),
    )

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 便捷构造：简单属性条件（无嵌套路径）。 */
    private fun simpleProp(
        property: String,
        type: PartType = PartType.SIMPLE_PROPERTY,
        ignoreCase: Boolean = false,
    ) = Condition(property, type, ignoreCase)

    /** 便捷构造：嵌套属性条件。 */
    private fun nestedProp(
        property: String,
        associationPath: String,
        remainingPath: String,
        type: PartType = PartType.SIMPLE_PROPERTY,
    ) = Condition(
        propertyPath = property,
        type = type,
        ignoreCase = false,
        nestedPath = NestedPropertyPath(associationPath, remainingPath),
    )

    /** 便捷构造：FIND 类型的 PartTree。 */
    private fun findTree(
        vararg predicates: PredicatePart,
        distinct: Boolean = false,
        limit: Int? = null,
        orderBy: List<OrderByPart> = emptyList(),
    ) = PartTree(
        subject = Subject(Operation.FIND, distinct, limit),
        predicates = predicates.toList(),
        orderBy = orderBy,
    )

    // ------------------------------------------------------------------
    // JOIN 构建测试
    // ------------------------------------------------------------------

    @Test
    fun `findByAddressCity 生成 LEFT JOIN`() {
        // 使用 PartTreeParser 解析
        val partTree = PartTreeParser.parse("findByAddressCity")
        val statement = DerivedQueryConverter.convert(partTree, tableWithAssociations) as SelectStatement

        // 验证 JOIN 生成
        assertEquals("应生成 1 个 JOIN", 1, statement.joins.size)
        val join = statement.joins[0]
        assertEquals("address", join.tableName)
        assertEquals("address", join.alias)
        assertEquals(cn.song.mybatisplus.visualizer.model.JoinType.LEFT, join.type)

        // 验证 ON 条件
        assertEquals(1, join.onConditions.size)
        val onCondition = join.onConditions[0] as cn.song.mybatisplus.visualizer.model.Condition.ColumnComparison
        assertEquals("address", onCondition.left.tableAlias)
        assertEquals("id", onCondition.left.columnName)
        assertEquals("address_id", onCondition.right.columnName)

        // 验证 SQL 渲染
        val sql = SqlGenerator.generate(statement)
        assertTrue("应包含 LEFT JOIN", sql.contains("LEFT JOIN"))
        assertTrue("应包含 address 表", sql.contains("address"))
        assertTrue("应包含 ON 条件", sql.contains("address.id = address_id"))
        assertTrue("应包含 WHERE 条件", sql.contains("address.city = ?"))
    }

    @Test
    fun `findByAddressCityAndName 混合条件`() {
        val partTree = PartTreeParser.parse("findByAddressCityAndName")
        val statement = DerivedQueryConverter.convert(partTree, tableWithAssociations) as SelectStatement

        // 验证只生成一个 JOIN（address）
        assertEquals("应生成 1 个 JOIN", 1, statement.joins.size)
        assertEquals("address", statement.joins[0].tableName)

        // 验证 SQL
        val sql = SqlGenerator.generate(statement)
        assertTrue("应包含 LEFT JOIN", sql.contains("LEFT JOIN"))
        assertTrue("应包含 address.city 条件", sql.contains("address.city = ?"))
        assertTrue("应包含 name 条件", sql.contains("name = ?"))
        assertTrue("应使用 AND 连接", sql.contains("AND"))
    }

    @Test
    fun `findByDepartmentName 单嵌套条件`() {
        val partTree = PartTreeParser.parse("findByDepartmentName")
        val statement = DerivedQueryConverter.convert(partTree, tableWithAssociations) as SelectStatement

        // 验证 JOIN
        assertEquals("应生成 1 个 JOIN", 1, statement.joins.size)
        assertEquals("department", statement.joins[0].tableName)
        assertEquals("department", statement.joins[0].alias)

        // 验证 SQL
        val sql = SqlGenerator.generate(statement)
        assertTrue("应包含 LEFT JOIN department", sql.contains("LEFT JOIN department"))
        assertTrue("应包含 ON 条件", sql.contains("department.id = department_id"))
        assertTrue("应包含 WHERE 条件", sql.contains("department.name = ?"))
    }

    @Test
    fun `findByAddressCityAndAddressStreet 同表多字段`() {
        val partTree = PartTreeParser.parse("findByAddressCityAndAddressStreet")
        val statement = DerivedQueryConverter.convert(partTree, tableWithAssociations) as SelectStatement

        // 验证只生成一个 JOIN（去重）
        assertEquals("同表多字段应只生成 1 个 JOIN", 1, statement.joins.size)
        assertEquals("address", statement.joins[0].tableName)

        // 验证 SQL
        val sql = SqlGenerator.generate(statement)
        assertTrue("应包含 address.city", sql.contains("address.city = ?"))
        assertTrue("应包含 address.street", sql.contains("address.street = ?"))
    }

    @Test
    fun `findByAddressCityAndAddressCountry 同关联去重`() {
        val partTree = PartTreeParser.parse("findByAddressCityAndAddressCountry")
        val statement = DerivedQueryConverter.convert(partTree, tableWithAssociations) as SelectStatement

        // 验证只生成一个 JOIN（去重）
        assertEquals("同关联多次使用应只生成 1 个 JOIN", 1, statement.joins.size)
        assertEquals("address", statement.joins[0].tableName)

        // 验证 SQL
        val sql = SqlGenerator.generate(statement)
        assertTrue("应包含 address.city", sql.contains("address.city = ?"))
        assertTrue("应包含 address.country", sql.contains("address.country = ?"))
    }

    @Test
    fun `findByAddressCityAndDepartmentName 多表 JOIN`() {
        val partTree = PartTreeParser.parse("findByAddressCityAndDepartmentName")
        val statement = DerivedQueryConverter.convert(partTree, tableWithAssociations) as SelectStatement

        // 验证生成两个 JOIN
        assertEquals("应生成 2 个 JOIN", 2, statement.joins.size)

        val tableNames = statement.joins.map { it.tableName }
        assertTrue("应包含 address 表", tableNames.contains("address"))
        assertTrue("应包含 department 表", tableNames.contains("department"))

        // 验证 SQL
        val sql = SqlGenerator.generate(statement)
        assertTrue("应包含 address 的 JOIN", sql.contains("LEFT JOIN address"))
        assertTrue("应包含 department 的 JOIN", sql.contains("LEFT JOIN department"))
    }

    @Test
    fun `findByCity 非嵌套属性不生成 JOIN`() {
        val partTree = PartTreeParser.parse("findByCity")
        val statement = DerivedQueryConverter.convert(partTree, tableWithAssociations) as SelectStatement

        // 验证不生成 JOIN
        assertTrue("非嵌套属性不应生成 JOIN", statement.joins.isEmpty())

        // 验证 SQL
        val sql = SqlGenerator.generate(statement)
        assertTrue("应包含 city 条件", sql.contains("city = ?"))
        assertTrue("不应包含 JOIN", !sql.contains("JOIN"))
    }

    @Test
    fun `findByUnknownField 关联未找到 → 降级`() {
        // 创建一个 TableInfo，没有 address 关联
        val tableNoAssoc = TableInfo(
            entityClassName = "UserEntity",
            tableName = "user",
            columns = listOf(
                ColumnInfo("id", "id", isId = true),
                ColumnInfo("name", "name"),
            ),
            associations = emptyList(),
        )

        val partTree = PartTreeParser.parse("findByAddressCity")
        val statement = DerivedQueryConverter.convert(partTree, tableNoAssoc) as SelectStatement

        // 验证不生成 JOIN
        assertTrue("无关联时应生成空 JOIN 列表", statement.joins.isEmpty())

        // 验证降级为普通列解析（不生成 Raw，避免误判驼峰属性名如 lastName 为嵌套关联）
        val condition = statement.conditions[0]
        assertTrue("关联未找到时应降级为普通列解析", condition !is cn.song.mybatisplus.visualizer.model.Condition.Raw)
    }

    // ------------------------------------------------------------------
    // 辅助测试：splitNestedPath 验证
    // ------------------------------------------------------------------

    @Test
    fun `splitNestedPath AddressCity → address + City`() {
        val result = PartTreeParser.splitNestedPath("AddressCity")
        assertEquals("address", result.associationPath)
        assertEquals("City", result.remainingPath)
        assertTrue(result.isNested)
    }

    @Test
    fun `splitNestedPath Name → 非嵌套`() {
        val result = PartTreeParser.splitNestedPath("Name")
        assertNull(result.associationPath)
        assertEquals("Name", result.remainingPath)
        assertTrue(!result.isNested)
    }

    @Test
    fun `splitNestedPath DepartmentName → department + Name`() {
        val result = PartTreeParser.splitNestedPath("DepartmentName")
        assertEquals("department", result.associationPath)
        assertEquals("Name", result.remainingPath)
        assertTrue(result.isNested)
    }

    @Test
    fun `splitNestedPath 空字符串 → 非嵌套`() {
        val result = PartTreeParser.splitNestedPath("")
        assertNull(result.associationPath)
        assertEquals("", result.remainingPath)
        assertTrue(!result.isNested)
    }

    @Test
    fun `splitNestedPath 单字符 → 非嵌套`() {
        val result = PartTreeParser.splitNestedPath("A")
        assertNull(result.associationPath)
        assertEquals("A", result.remainingPath)
        assertTrue(!result.isNested)
    }
}
