package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-1：JOIN 关系图 PlantUML 文本生成器单元测试（纯逻辑，无渲染）。
 *
 * 覆盖：
 *  - 单表无 JOIN → 空输出
 *  - 2 表 LEFT JOIN
 *  - 3 表混合 JOIN（LEFT + INNER）
 *  - 子查询 JOIN（rawSql 非空）
 *  - 列数 > 50 只显示 PK + FK
 */
class JoinDiagramGeneratorTest {

    private val userTable = TableInfo(
        entityClassName = "UserEntity",
        tableName = "user",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("name", "name"),
            ColumnInfo("email", "email"),
            ColumnInfo("deptId", "dept_id"),
        ),
    )

    private val orderTable = TableInfo(
        entityClassName = "OrderEntity",
        tableName = "order",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("userId", "user_id"),
            ColumnInfo("amount", "amount"),
        ),
    )

    private val deptTable = TableInfo(
        entityClassName = "DeptEntity",
        tableName = "dept",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("name", "name"),
        ),
    )

    private val addressTable = TableInfo(
        entityClassName = "AddressEntity",
        tableName = "address",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("userId", "user_id"),
            ColumnInfo("city", "city"),
        ),
    )

    // ------------------------------------------------------------------
    // 单表无 JOIN → 空输出
    // ------------------------------------------------------------------

    @Test
    fun `single table no join returns empty`() {
        val stmt = SelectStatement(table = userTable)
        val result = JoinDiagramGenerator.generatePlantUml(stmt)
        assertEquals("", result)
    }

    @Test
    fun `empty joins list returns empty`() {
        val stmt = SelectStatement(table = userTable, joins = emptyList())
        val result = JoinDiagramGenerator.generatePlantUml(stmt)
        assertEquals("", result)
    }

    // ------------------------------------------------------------------
    // 2 表 LEFT JOIN
    // ------------------------------------------------------------------

    @Test
    fun `two table left join`() {
        val stmt = SelectStatement(
            table = userTable,
            tableAlias = "u",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT,
                    tableName = "order",
                    alias = "o",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "u", columnName = "id"),
                            right = ColumnRef(tableAlias = "o", columnName = "user_id"),
                        ),
                    ),
                ),
            ),
        )
        val result = JoinDiagramGenerator.generatePlantUml(stmt)
        assertTrue("should contain @startuml", result.contains("@startuml"))
        assertTrue("should contain @enduml", result.contains("@enduml"))
        assertTrue("should contain main table", result.contains("user"))
        assertTrue("should contain join table", result.contains("order"))
        assertTrue("should contain LEFT JOIN relation", result.contains("||--o{"))
        assertTrue("should contain ON condition", result.contains("u.id = o.user_id"))
        assertTrue("should mark PK", result.contains("* id"))
    }

    // ------------------------------------------------------------------
    // 3 表混合 JOIN（LEFT + INNER）
    // ------------------------------------------------------------------

    @Test
    fun `three table mixed join`() {
        val stmt = SelectStatement(
            table = userTable,
            tableAlias = "u",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT,
                    tableName = "order",
                    alias = "o",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "u", columnName = "id"),
                            right = ColumnRef(tableAlias = "o", columnName = "user_id"),
                        ),
                    ),
                ),
                JoinClause(
                    type = JoinType.INNER,
                    tableName = "dept",
                    alias = "d",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "u", columnName = "dept_id"),
                            right = ColumnRef(tableAlias = "d", columnName = "id"),
                        ),
                    ),
                ),
            ),
        )
        val result = JoinDiagramGenerator.generatePlantUml(stmt)
        assertTrue("should contain order table", result.contains("order"))
        assertTrue("should contain dept table", result.contains("dept"))
        assertTrue("should contain LEFT relation", result.contains("||--o{"))
        assertTrue("should contain INNER relation", result.contains("||--||"))
        assertTrue("should contain first ON condition", result.contains("u.id = o.user_id"))
        assertTrue("should contain second ON condition", result.contains("u.dept_id = d.id"))
    }

    // ------------------------------------------------------------------
    // RIGHT JOIN + FULL JOIN 关系线验证
    // ------------------------------------------------------------------

    @Test
    fun `right join relation line`() {
        val stmt = SelectStatement(
            table = userTable,
            tableAlias = "u",
            joins = listOf(
                JoinClause(
                    type = JoinType.RIGHT,
                    tableName = "address",
                    alias = "a",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "u", columnName = "id"),
                            right = ColumnRef(tableAlias = "a", columnName = "user_id"),
                        ),
                    ),
                ),
            ),
        )
        val result = JoinDiagramGenerator.generatePlantUml(stmt)
        assertTrue("should contain RIGHT relation", result.contains("}o--||"))
    }

    @Test
    fun `full join relation line`() {
        val stmt = SelectStatement(
            table = userTable,
            tableAlias = "u",
            joins = listOf(
                JoinClause(
                    type = JoinType.FULL,
                    tableName = "address",
                    alias = "a",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "u", columnName = "id"),
                            right = ColumnRef(tableAlias = "a", columnName = "user_id"),
                        ),
                    ),
                ),
            ),
        )
        val result = JoinDiagramGenerator.generatePlantUml(stmt)
        assertTrue("should contain FULL relation", result.contains("}o--o{"))
    }

    // ------------------------------------------------------------------
    // 子查询 JOIN（rawSql 非空）
    // ------------------------------------------------------------------

    @Test
    fun `rawSql join still renders`() {
        val stmt = SelectStatement(
            table = userTable,
            tableAlias = "u",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT,
                    tableName = "order",
                    alias = "o",
                    onConditions = emptyList(),
                    rawSql = "LEFT JOIN (SELECT * FROM order WHERE amount > 100) o ON u.id = o.user_id",
                ),
            ),
        )
        val result = JoinDiagramGenerator.generatePlantUml(stmt)
        assertTrue("should contain @startuml", result.contains("@startuml"))
        assertTrue("should contain join table", result.contains("order"))
        assertTrue("should contain rawSql indicator", result.contains("rawSql"))
    }

    // ------------------------------------------------------------------
    // 列数 > 50 只显示 PK
    // ------------------------------------------------------------------

    @Test
    fun `table with over 50 columns shows only PK`() {
        val manyColumns = (1..60).map { i ->
            ColumnInfo("field$i", "col_$i", isId = i == 1)
        }
        val bigTable = TableInfo(
            entityClassName = "BigEntity",
            tableName = "big_table",
            columns = manyColumns,
        )
        val stmt = SelectStatement(
            table = bigTable,
            tableAlias = "t",
            joins = listOf(
                JoinClause(
                    type = JoinType.INNER,
                    tableName = "order",
                    alias = "o",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "t", columnName = "col_1"),
                            right = ColumnRef(tableAlias = "o", columnName = "id"),
                        ),
                    ),
                ),
            ),
        )
        val result = JoinDiagramGenerator.generatePlantUml(stmt)
        assertTrue("should contain PK column col_1", result.contains("* col_1"))
        // 非 PK 列不应出现
        assertTrue("should not contain non-PK column col_2", !result.contains("col_2"))
        assertTrue("should not contain non-PK column col_3", !result.contains("col_3"))
    }

    // ------------------------------------------------------------------
    // 无别名时使用默认别名
    // ------------------------------------------------------------------

    @Test
    fun `join without alias uses default alias`() {
        val stmt = SelectStatement(
            table = userTable,
            tableAlias = "t",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT,
                    tableName = "order",
                    alias = "",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "t", columnName = "id"),
                            right = ColumnRef(columnName = "user_id"),
                        ),
                    ),
                ),
            ),
        )
        val result = JoinDiagramGenerator.generatePlantUml(stmt)
        assertTrue("should contain @startuml", result.contains("@startuml"))
        assertTrue("should contain table name order", result.contains("order"))
    }

    // ------------------------------------------------------------------
    // 多条件 ON（AND 连接）
    // ------------------------------------------------------------------

    @Test
    fun `multiple on conditions joined with AND`() {
        val stmt = SelectStatement(
            table = userTable,
            tableAlias = "u",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT,
                    tableName = "order",
                    alias = "o",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "u", columnName = "id"),
                            right = ColumnRef(tableAlias = "o", columnName = "user_id"),
                        ),
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "u", columnName = "dept_id"),
                            right = ColumnRef(tableAlias = "o", columnName = "dept_id"),
                        ),
                    ),
                ),
            ),
        )
        val result = JoinDiagramGenerator.generatePlantUml(stmt)
        assertTrue("should contain first condition", result.contains("u.id = o.user_id"))
        assertTrue("should contain second condition", result.contains("u.dept_id = o.dept_id"))
        assertTrue("should join with AND", result.contains(" AND "))
    }
}
