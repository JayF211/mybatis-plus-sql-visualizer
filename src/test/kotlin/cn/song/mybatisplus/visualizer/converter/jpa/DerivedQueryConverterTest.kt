package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.ConditionToggle
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DerivedQueryConverter 单元测试（F-14，JPA 派生查询预览）。
 *
 * 验证 PartTree → SqlStatement 转换的正确性，覆盖：
 *  - 简单属性条件（eq/ne/gt/lt/between/in/like）
 *  - 聚合类型（COUNT/EXISTS）
 *  - DELETE 语句
 *  - ORDER BY / DISTINCT / LIMIT
 *  - 降级场景（嵌套属性/空间查询/EMPTY 检查）
 */
class DerivedQueryConverterTest {

    private val table = TableInfo(
        entityClassName = "UserEntity",
        tableName = "user",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("firstName", "first_name"),
            ColumnInfo("lastName", "last_name"),
            ColumnInfo("age", "age"),
            ColumnInfo("name", "name"),
            ColumnInfo("status", "status"),
            ColumnInfo("departmentId", "department_id"),
            ColumnInfo("createTime", "create_time"),
        ),
    )

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 便捷构造：简单属性条件。 */
    private fun simpleProp(
        property: String,
        type: PartType = PartType.SIMPLE_PROPERTY,
        ignoreCase: Boolean = false,
    ) = Condition(property, type, ignoreCase)

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

    /** 便捷构造：COUNT 类型的 PartTree。 */
    private fun countTree(vararg predicates: PredicatePart) = PartTree(
        subject = Subject(Operation.COUNT, false, null),
        predicates = predicates.toList(),
        orderBy = emptyList(),
    )

    /** 便捷构造：EXISTS 类型的 PartTree。 */
    private fun existsTree(vararg predicates: PredicatePart) = PartTree(
        subject = Subject(Operation.EXISTS, false, null),
        predicates = predicates.toList(),
        orderBy = emptyList(),
    )

    /** 便捷构造：DELETE 类型的 PartTree。 */
    private fun deleteTree(vararg predicates: PredicatePart) = PartTree(
        subject = Subject(Operation.DELETE, false, null),
        predicates = predicates.toList(),
        orderBy = emptyList(),
    )

    // ------------------------------------------------------------------
    // 基础条件测试
    // ------------------------------------------------------------------

    @Test
    fun `findByLastName_生成等值条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("lastName")))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement
        val sql = SqlGenerator.generate(statement)

        assertEquals(
            """
            SELECT id, first_name, last_name, age, name, status, department_id, create_time
            FROM `user`
            WHERE last_name = ?
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `findByLastNameAndFirstName_生成AND连接条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(
                simpleProp("lastName"),
                simpleProp("firstName"),
            ))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement
        val sql = SqlGenerator.generate(statement)

        // 验证条件结构：单个 PredicatePart 内 AND 条件平铺（由 SqlGenerator 处理多行缩进）
        assertEquals(2, statement.conditions.size)
        val cond1 = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        val cond2 = statement.conditions[1] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals("=", cond1.op)
        assertEquals("=", cond2.op)

        // 验证 SQL 渲染
        assertEquals(
            """
            SELECT id, first_name, last_name, age, name, status, department_id, create_time
            FROM `user`
            WHERE last_name = ?
                AND first_name = ?
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `findByAgeBetween_生成BETWEEN条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("age", PartType.BETWEEN)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        // 验证条件类型
        val condition = statement.conditions[0]
        assertTrue("应为 Between 条件", condition is cn.song.mybatisplus.visualizer.model.Condition.Between)
        val between = condition as cn.song.mybatisplus.visualizer.model.Condition.Between
        assertEquals("age", between.column.columnName)
        assertFalse(between.negated)

        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, first_name, last_name, age, name, status, department_id, create_time
            FROM `user`
            WHERE age BETWEEN ? AND ?
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `findByNameLike_生成LIKE条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("name", PartType.LIKE)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        // 验证条件类型
        val condition = statement.conditions[0]
        assertTrue("应为 Like 条件", condition is cn.song.mybatisplus.visualizer.model.Condition.Like)
        val like = condition as cn.song.mybatisplus.visualizer.model.Condition.Like
        assertEquals("name", like.column.columnName)
        assertFalse(like.negated)
        assertEquals(LikePosition.ANYWHERE, like.position)

        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, first_name, last_name, age, name, status, department_id, create_time
            FROM `user`
            WHERE name LIKE ?
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `findByStatusIn_生成IN条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("status", PartType.IN)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        // 验证条件类型
        val condition = statement.conditions[0]
        assertTrue("应为 In 条件", condition is cn.song.mybatisplus.visualizer.model.Condition.In)
        val inCondition = condition as cn.song.mybatisplus.visualizer.model.Condition.In
        assertEquals("status", inCondition.column.columnName)
        assertFalse(inCondition.negated)

        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, first_name, last_name, age, name, status, department_id, create_time
            FROM `user`
            WHERE status IN (?)
            """.trimIndent(),
            sql,
        )
    }

    // ------------------------------------------------------------------
    // 聚合类型测试
    // ------------------------------------------------------------------

    @Test
    fun `countByDepartmentId_生成COUNT聚合`() {
        val partTree = countTree(
            PredicatePart(Connector.AND, listOf(simpleProp("departmentId")))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        // 验证 SELECT 列为 COUNT(*)
        assertEquals(1, statement.selectColumns.size)
        val countCol = statement.selectColumns[0]
        assertEquals("COUNT", countCol.function)
        assertEquals("*", countCol.columnName)

        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT COUNT(*)
            FROM `user`
            WHERE department_id = ?
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `existsByDepartmentId_生成SELECT 1`() {
        val partTree = existsTree(
            PredicatePart(Connector.AND, listOf(simpleProp("departmentId")))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        // 验证 SELECT 列为 1
        assertEquals(1, statement.selectColumns.size)
        assertEquals("1", statement.selectColumns[0].columnName)

        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT 1
            FROM `user`
            WHERE department_id = ?
            """.trimIndent(),
            sql,
        )
    }

    // ------------------------------------------------------------------
    // DELETE 测试
    // ------------------------------------------------------------------

    @Test
    fun `deleteByStatus_生成DELETE语句`() {
        val partTree = deleteTree(
            PredicatePart(Connector.AND, listOf(simpleProp("status")))
        )
        val statement = DerivedQueryConverter.convert(partTree, table)

        assertTrue("应为 DeleteStatement", statement is DeleteStatement)
        val deleteStmt = statement as DeleteStatement

        val sql = SqlGenerator.generate(deleteStmt)
        assertEquals(
            """
            DELETE FROM `user`
            WHERE status = ?
            """.trimIndent(),
            sql,
        )
    }

    // ------------------------------------------------------------------
    // ORDER BY / LIMIT / DISTINCT 测试
    // ------------------------------------------------------------------

    @Test
    fun `findTop10ByOrderByCreateTimeDesc_生成ORDER BY和LIMIT`() {
        val partTree = PartTree(
            subject = Subject(Operation.FIND, false, 10),
            predicates = emptyList(),
            orderBy = listOf(
                OrderByPart("createTime", SortDirection.DESC),
            ),
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        // 验证 ORDER BY
        assertEquals(1, statement.orderBy.size)
        assertEquals("create_time", statement.orderBy[0].column.columnName)
        assertFalse(statement.orderBy[0].asc)

        // 验证 LIMIT
        assertNotNull(statement.limit)
        assertEquals(SqlValue.Literal("10"), statement.limit?.count)
        // LIMIT 值应为 10（Top N 场景）
        assertEquals("10", (statement.limit?.count as SqlValue.Literal).raw)

        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, first_name, last_name, age, name, status, department_id, create_time
            FROM `user`
            ORDER BY create_time DESC
            LIMIT 10
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `findDistinctByStatus_生成DISTINCT查询`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("status"))),
            distinct = true,
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        // 验证 DISTINCT
        assertTrue(statement.distinct)

        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT DISTINCT id, first_name, last_name, age, name, status, department_id, create_time
            FROM `user`
            WHERE status = ?
            """.trimIndent(),
            sql,
        )
    }

    // ------------------------------------------------------------------
    // 复杂条件测试
    // ------------------------------------------------------------------

    @Test
    fun `findByStatusAndAgeIn_生成复合条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(
                simpleProp("status"),
                simpleProp("age", PartType.IN),
            ))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement
        val sql = SqlGenerator.generate(statement)

        assertTrue("应包含 status 条件", sql.contains("status = ?"))
        assertTrue("应包含 age IN 条件", sql.contains("age IN (?)"))
        assertTrue("应使用 AND 连接", sql.contains("AND"))
    }

    @Test
    fun `findByFirstNameOrLastName_生成OR连接条件`() {
        val partTree = PartTree(
            subject = Subject(Operation.FIND, false, null),
            predicates = listOf(
                PredicatePart(Connector.AND, listOf(simpleProp("firstName"))),
                PredicatePart(Connector.OR, listOf(simpleProp("lastName"))),
            ),
            orderBy = emptyList(),
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        // 验证条件结构：顶层应为 OR 组
        assertEquals(1, statement.conditions.size)
        val orGroup = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Group
        assertEquals(cn.song.mybatisplus.visualizer.model.Connector.OR, orGroup.connector)
        assertEquals(2, orGroup.children.size)

        val sql = SqlGenerator.generate(statement)
        assertTrue("应包含 OR 连接", sql.contains("OR"))
    }

    @Test
    fun `findByAgeGreaterThan_生成大于条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("age", PartType.GREATER_THAN)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals(">", condition.op)
        assertEquals("age", condition.column.columnName)

        val sql = SqlGenerator.generate(statement)
        assertTrue("应包含 > 操作符", sql.contains("age > ?"))
    }

    @Test
    fun `findByStatusIsNull_生成IS NULL条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("status", PartType.IS_NULL)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Unary
        assertEquals("IS NULL", condition.op)

        val sql = SqlGenerator.generate(statement)
        assertTrue("应包含 IS NULL", sql.contains("status IS NULL"))
    }

    @Test
    fun `findByNameStartingWith_生成前缀匹配条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("name", PartType.STARTING_WITH)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Like
        assertEquals(LikePosition.RIGHT, condition.position)
        assertFalse(condition.negated)
    }

    @Test
    fun `findByNameEndingWith_生成后缀匹配条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("name", PartType.ENDING_WITH)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Like
        assertEquals(LikePosition.LEFT, condition.position)
        assertFalse(condition.negated)
    }

    @Test
    fun `findByNameContaining_生成包含匹配条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("name", PartType.CONTAINING)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Like
        assertEquals(LikePosition.ANYWHERE, condition.position)
        assertFalse(condition.negated)
    }

    @Test
    fun `findByNameNotContaining_生成不包含匹配条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("name", PartType.NOT_CONTAINING)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Like
        assertEquals(LikePosition.ANYWHERE, condition.position)
        assertTrue(condition.negated)
    }

    @Test
    fun `findByStatusNotEqual_生成不等条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("status", PartType.NEGATING_SIMPLE_PROPERTY)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals("!=", condition.op)

        val sql = SqlGenerator.generate(statement)
        assertTrue("应包含 != 操作符", sql.contains("status != ?"))
    }

    @Test
    fun `findByStatusNotIn_生成NOT IN条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("status", PartType.NOT_IN)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.In
        assertTrue(condition.negated)

        val sql = SqlGenerator.generate(statement)
        assertTrue("应包含 NOT IN", sql.contains("status NOT IN (?)"))
    }

    @Test
    fun `findByActiveTrue_生成布尔true条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("active", PartType.TRUE)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals("=", condition.op)
        assertEquals(SqlValue.Literal("true"), condition.value)
    }

    @Test
    fun `findByActiveFalse_生成布尔false条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("active", PartType.FALSE)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals("=", condition.op)
        assertEquals(SqlValue.Literal("false"), condition.value)
    }

    @Test
    fun `findByCreateTimeBefore_生成BEFORE条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("createTime", PartType.BEFORE)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals("<", condition.op)
    }

    @Test
    fun `findByCreateTimeAfter_生成AFTER条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("createTime", PartType.AFTER)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals(">", condition.op)
    }

    @Test
    fun `findByAgeLessThan_生成小于条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("age", PartType.LESS_THAN)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals("<", condition.op)
    }

    @Test
    fun `findByAgeLessThanEqual_生成小于等于条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("age", PartType.LESS_THAN_EQUAL)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals("<=", condition.op)
    }

    @Test
    fun `findByAgeGreaterThanEqual_生成大于等于条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("age", PartType.GREATER_THAN_EQUAL)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals(">=", condition.op)
    }

    @Test
    fun `findByStatusIsNotNull_生成IS NOT NULL条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("status", PartType.IS_NOT_NULL)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Unary
        assertEquals("IS NOT NULL", condition.op)
    }

    @Test
    fun `findByNameNotLike_生成NOT LIKE条件`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("name", PartType.NOT_LIKE)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Like
        assertTrue(condition.negated)
        assertEquals(LikePosition.ANYWHERE, condition.position)
    }

    // ------------------------------------------------------------------
    // 降级场景测试
    // ------------------------------------------------------------------

    @Test
    fun `嵌套属性路径_生成降级提示`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("address.city")))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0]
        assertTrue("嵌套属性应生成 Raw 条件", condition is cn.song.mybatisplus.visualizer.model.Condition.Raw)
        val raw = condition as cn.song.mybatisplus.visualizer.model.Condition.Raw
        assertTrue("应提示关联查询", raw.sqlText.contains("关联查询"))
    }

    @Test
    fun `NEAR空间查询_生成降级提示`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("location", PartType.NEAR)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Raw
        assertEquals("空间查询不支持", condition.sqlText)
    }

    @Test
    fun `WITHIN空间查询_生成降级提示`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("location", PartType.WITHIN)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Raw
        assertEquals("空间查询不支持", condition.sqlText)
    }

    @Test
    fun `IS_EMPTY_生成降级提示`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("tags", PartType.IS_EMPTY)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Raw
        assertEquals("EMPTY 检查不支持", condition.sqlText)
    }

    @Test
    fun `IS_NOT_EMPTY_生成降级提示`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("tags", PartType.IS_NOT_EMPTY)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Raw
        assertEquals("EMPTY 检查不支持", condition.sqlText)
    }

    @Test
    fun `REGEX正则匹配_生成降级REGEXP`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("name", PartType.REGEX)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Raw
        assertEquals("REGEXP(?, ?)", condition.sqlText)
    }

    @Test
    fun `嵌套属性EXISTS_生成IS NOT NULL`() {
        // EXISTS 类型的 Part 用于嵌套属性存在性检查
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("department", PartType.EXISTS)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Unary
        assertEquals("IS NOT NULL", condition.op)
    }

    // ------------------------------------------------------------------
    // 空谓词测试
    // ------------------------------------------------------------------

    @Test
    fun `无条件FIND_生成无条件SELECT`() {
        val partTree = findTree()
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        assertTrue(statement.conditions.isEmpty())
        assertNull(statement.limit)

        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, first_name, last_name, age, name, status, department_id, create_time
            FROM `user`
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `无条件DELETE_生成无条件DELETE`() {
        val partTree = deleteTree()
        val statement = DerivedQueryConverter.convert(partTree, table) as DeleteStatement

        assertTrue(statement.conditions.isEmpty())

        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            DELETE FROM `user`
            """.trimIndent(),
            sql,
        )
    }

    // ------------------------------------------------------------------
    // 列名映射测试
    // ------------------------------------------------------------------

    @Test
    fun `驼峰属性名_正确映射为下划线列名`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("departmentId")))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        // 验证列名从实体字段名映射为数据库列名
        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals("department_id", condition.column.columnName)

        val sql = SqlGenerator.generate(statement)
        assertTrue("应使用下划线列名", sql.contains("department_id = ?"))
    }

    @Test
    fun `未知属性名_使用驼峰转下划线兜底`() {
        // 使用一个不在 TableInfo 列映射中的属性名
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("unknownField")))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement

        // 验证使用驼峰转下划线兜底
        val condition = statement.conditions[0] as cn.song.mybatisplus.visualizer.model.Condition.Simple
        assertEquals("unknown_field", condition.column.columnName)
    }

    // ------------------------------------------------------------------
    // 集成测试：与 PartTreeParser 联动
    // ------------------------------------------------------------------

    @Test
    fun `PartTreeParser解析findByLastName_转换为正确SQL`() {
        val partTree = PartTreeParser.parse("findByLastName")
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement
        val sql = SqlGenerator.generate(statement)

        assertTrue("应包含 last_name 列", sql.contains("last_name = ?"))
        assertTrue("应包含 WHERE 子句", sql.contains("WHERE"))
    }

    @Test
    fun `PartTreeParser解析findByLastNameAndFirstName_转换为正确SQL`() {
        val partTree = PartTreeParser.parse("findByLastNameAndFirstName")
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement
        val sql = SqlGenerator.generate(statement)

        assertTrue("应包含 last_name 条件", sql.contains("last_name = ?"))
        assertTrue("应包含 first_name 条件", sql.contains("first_name = ?"))
        assertTrue("应使用 AND 连接", sql.contains("AND"))
    }

    @Test
    fun `PartTreeParser解析findByAgeBetween_转换为正确SQL`() {
        val partTree = PartTreeParser.parse("findByAgeBetween")
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement
        val sql = SqlGenerator.generate(statement)

        assertTrue("应包含 BETWEEN", sql.contains("BETWEEN"))
        assertTrue("应包含 age 列", sql.contains("age"))
    }

    @Test
    fun `PartTreeParser解析countByDepartmentId_转换为正确SQL`() {
        val partTree = PartTreeParser.parse("countByDepartmentId")
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement
        val sql = SqlGenerator.generate(statement)

        assertTrue("应包含 COUNT(*)", sql.contains("COUNT(*)"))
        assertTrue("应包含 department_id 条件", sql.contains("department_id = ?"))
    }

    @Test
    fun `PartTreeParser解析deleteByStatus_转换为正确SQL`() {
        val partTree = PartTreeParser.parse("deleteByStatus")
        val statement = DerivedQueryConverter.convert(partTree, table)
        val sql = SqlGenerator.generate(statement)

        assertTrue("应为 DELETE 语句", sql.startsWith("DELETE"))
        assertTrue("应包含 status 条件", sql.contains("status = ?"))
    }

    @Test
    fun `PartTreeParser解析findTop10ByAgeOrderByCreateTimeDesc_转换为正确SQL`() {
        // Spring Data JPA：OrderBy 前必须有谓词条件（findTop10By{OrderBy} 不合法）
        val partTree = PartTreeParser.parse("findTop10ByAgeOrderByCreateTimeDesc")
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement
        val sql = SqlGenerator.generate(statement)

        assertTrue("应包含 ORDER BY", sql.contains("ORDER BY"))
        assertTrue("应包含 DESC", sql.contains("DESC"))
        assertTrue("应包含 LIMIT", sql.contains("LIMIT"))
    }

    @Test
    fun `PartTreeParser解析findDistinctByStatus_转换为正确SQL`() {
        val partTree = PartTreeParser.parse("findDistinctByStatus")
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement
        val sql = SqlGenerator.generate(statement)

        assertTrue("应包含 DISTINCT", sql.contains("DISTINCT"))
        assertTrue("应包含 status 条件", sql.contains("status = ?"))
    }

    // ------------------------------------------------------------------
    // T-J4：ignoreCase 生效（等值族 LOWER 包装）
    // ------------------------------------------------------------------

    @Test
    fun `TJ4_ignoreCase等值_生成LOWER包裹`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("lastName", ignoreCase = true)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement
        val sql = SqlGenerator.generate(statement)

        assertTrue(
            "等值条件应生成 LOWER(col) = LOWER(?)",
            sql.contains("LOWER(last_name) = LOWER(?)"),
        )
        assertFalse("不应再出现未包装的裸等值", sql.contains("last_name = ?"))
    }

    @Test
    fun `TJ4_ignoreCase不等_生成LOWER包裹`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("status", PartType.NEGATING_SIMPLE_PROPERTY, ignoreCase = true)))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement
        val sql = SqlGenerator.generate(statement)

        assertTrue("不等条件应生成 LOWER(col) != LOWER(?)", sql.contains("LOWER(status) != LOWER(?)"))
    }

    @Test
    fun `TJ4_ignoreCase为false_保持裸列`() {
        val partTree = findTree(
            PredicatePart(Connector.AND, listOf(simpleProp("lastName")))
        )
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement
        val sql = SqlGenerator.generate(statement)

        assertTrue("无 ignoreCase 不应包 LOWER", sql.contains("last_name = ?"))
        assertFalse(sql.contains("LOWER"))
    }

    @Test
    fun `TJ4_与PartTreeParser联动_ignoreCase全链路`() {
        // findByNameIgnoreCase → name IGNORE CASE → LOWER(name) = LOWER(?)
        val partTree = PartTreeParser.parse("findByNameIgnoreCase")
        val statement = DerivedQueryConverter.convert(partTree, table) as SelectStatement
        val sql = SqlGenerator.generate(statement)

        assertTrue("端到端应生成 LOWER(name) = LOWER(?)", sql.contains("LOWER(name) = LOWER(?)"))
    }

    // ------------------------------------------------------------------
    // T-J4：IN 条件检测（SqlPreviewService 接线预留）
    // ------------------------------------------------------------------

    @Test
    fun `TJ4_hasInClause_各类形态`() {
        assertTrue("findByStatusIn 应检测到 IN", DerivedQueryConverter.hasInClause("findByStatusIn"))
        assertTrue("findByStatusNotIn 应检测到 NOT IN", DerivedQueryConverter.hasInClause("findByStatusNotIn"))
        assertTrue("findByStatusIsIn 应检测到 IN", DerivedQueryConverter.hasInClause("findByStatusIsIn"))
        assertFalse("findByStatus 不应检测到 IN", DerivedQueryConverter.hasInClause("findByStatus"))
        assertFalse("findAll 不应检测到 IN", DerivedQueryConverter.hasInClause("findAll"))
    }

    @Test
    fun `TJ4_inClauseParameterOrdinals_跳过无参条件`() {
        // IsNull 不消耗参数 → StatusIn 仍是第 0 个参数
        assertEquals(
            listOf(0),
            DerivedQueryConverter.inClauseParameterOrdinals("findByNameIsNullAndStatusIn"),
        )
        assertEquals(
            listOf(0),
            DerivedQueryConverter.inClauseParameterOrdinals("findByStatusIn"),
        )
        // Between 消耗两个参数（age lo/hi 占 0、1）→ StatusIn 是第 3 个参数（序号 2）
        assertEquals(
            listOf(2),
            DerivedQueryConverter.inClauseParameterOrdinals("findByAgeBetweenAndStatusIn"),
        )
    }

    @Test
    fun `TJ4_inClauseParameterOrdinals_多In条件`() {
        assertEquals(
            listOf(0, 1),
            DerivedQueryConverter.inClauseParameterOrdinals("findByStatusInAndDeptIn"),
        )
    }
}
