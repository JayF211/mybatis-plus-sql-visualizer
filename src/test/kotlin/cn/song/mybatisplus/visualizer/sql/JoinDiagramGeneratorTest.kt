package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun `single table no join returns empty model`() {
        val stmt = SelectStatement(table = userTable)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertTrue("model should be empty", model.isEmpty)
        assertEquals(0, model.entities.size)
        assertEquals(0, model.relationships.size)
    }

    @Test
    fun `empty joins list returns empty model`() {
        val stmt = SelectStatement(table = userTable, joins = emptyList())
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertTrue("model should be empty", model.isEmpty)
    }

    @Test
    fun `two table left join`() {
        val stmt = SelectStatement(
            table = userTable, tableAlias = "u",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT, tableName = "order", alias = "o",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "u", columnName = "id"),
                            right = ColumnRef(tableAlias = "o", columnName = "user_id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertFalse("model should not be empty", model.isEmpty)
        assertEquals(2, model.entities.size)
        assertTrue(model.entities[0].isMain)
        assertEquals("user", model.entities[0].tableName)
        assertEquals("u", model.entities[0].alias)
        assertFalse(model.entities[1].isMain)
        assertEquals("order", model.entities[1].tableName)
        assertEquals("o", model.entities[1].alias)
        assertEquals(1, model.relationships.size)
        assertEquals("u", model.relationships[0].fromAlias)
        assertEquals("o", model.relationships[0].toAlias)
        assertEquals(JoinType.LEFT, model.relationships[0].joinType)
        assertTrue(model.relationships[0].label.contains("u.id = o.user_id"))
        assertTrue(model.entities[0].columns.isNotEmpty())
        assertTrue(model.entities[0].columns[0].isPk)
        assertEquals("id", model.entities[0].columns[0].name)
    }

    @Test
    fun `three table mixed join`() {
        val stmt = SelectStatement(
            table = userTable, tableAlias = "u",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT, tableName = "order", alias = "o",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "u", columnName = "id"),
                            right = ColumnRef(tableAlias = "o", columnName = "user_id"),
                        ),
                    ),
                ),
                JoinClause(
                    type = JoinType.INNER, tableName = "dept", alias = "d",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "u", columnName = "dept_id"),
                            right = ColumnRef(tableAlias = "d", columnName = "id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(3, model.entities.size)
        assertEquals(2, model.relationships.size)
        assertEquals(JoinType.LEFT, model.relationships[0].joinType)
        assertTrue(model.relationships[0].label.contains("u.id = o.user_id"))
        assertEquals(JoinType.INNER, model.relationships[1].joinType)
        assertTrue(model.relationships[1].label.contains("u.dept_id = d.id"))
    }

    @Test
    fun `right join relation type`() {
        val stmt = SelectStatement(
            table = userTable, tableAlias = "u",
            joins = listOf(
                JoinClause(
                    type = JoinType.RIGHT, tableName = "address", alias = "a",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "u", columnName = "id"),
                            right = ColumnRef(tableAlias = "a", columnName = "user_id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(JoinType.RIGHT, model.relationships[0].joinType)
    }

    @Test
    fun `full join relation type`() {
        val stmt = SelectStatement(
            table = userTable, tableAlias = "u",
            joins = listOf(
                JoinClause(
                    type = JoinType.FULL, tableName = "address", alias = "a",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "u", columnName = "id"),
                            right = ColumnRef(tableAlias = "a", columnName = "user_id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(JoinType.FULL, model.relationships[0].joinType)
    }

    @Test
    fun `rawSql join still renders`() {
        val stmt = SelectStatement(
            table = userTable, tableAlias = "u",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT, tableName = "order", alias = "o",
                    onConditions = emptyList(),
                    rawSql = "LEFT JOIN (SELECT * FROM order WHERE amount > 100) o ON u.id = o.user_id",
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertFalse(model.isEmpty)
        assertEquals(2, model.entities.size)
        assertTrue(model.relationships[0].label.contains("rawSql"))
    }

    @Test
    fun `table with over 50 columns shows only PK`() {
        val manyColumns = (1..60).map { i -> ColumnInfo("field$i", "col_$i", isId = i == 1) }
        val bigTable = TableInfo(entityClassName = "BigEntity", tableName = "big_table", columns = manyColumns)
        val stmt = SelectStatement(
            table = bigTable, tableAlias = "t",
            joins = listOf(
                JoinClause(
                    type = JoinType.INNER, tableName = "order", alias = "o",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "t", columnName = "col_1"),
                            right = ColumnRef(tableAlias = "o", columnName = "id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(1, model.entities[0].columns.size)
        assertTrue(model.entities[0].columns[0].isPk)
        assertEquals("col_1", model.entities[0].columns[0].name)
    }

    @Test
    fun `join without alias uses default alias`() {
        val stmt = SelectStatement(
            table = userTable, tableAlias = "t",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT, tableName = "order", alias = "",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "t", columnName = "id"),
                            right = ColumnRef(columnName = "user_id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertFalse(model.isEmpty)
        assertEquals(2, model.entities.size)
        assertEquals("t1", model.entities[1].alias)
        assertEquals("order", model.entities[1].tableName)
    }

    @Test
    fun `multiple on conditions joined with AND`() {
        val stmt = SelectStatement(
            table = userTable, tableAlias = "u",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT, tableName = "order", alias = "o",
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
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        val label = model.relationships[0].label
        assertTrue(label.contains("u.id = o.user_id"))
        assertTrue(label.contains("u.dept_id = o.dept_id"))
        assertTrue(label.contains(" AND "))
    }

    @Test
    fun `join table shows columns from ON conditions and selectAs`() {
        val stmt = SelectStatement(
            table = userTable, tableAlias = "u",
            selectColumns = listOf(
                ColumnRef(tableAlias = "o", columnName = "station_name", type = "java.lang.String"),
            ),
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT, tableName = "order", alias = "o",
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
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        val joinEntity = model.entities[1]
        assertEquals("order", joinEntity.tableName)
        val colNames = joinEntity.columns.map { it.name }
        // From selectAs (with type)
        assertTrue("Should contain station_name from selectAs", colNames.contains("station_name"))
        val stationCol = joinEntity.columns.find { it.name == "station_name" }
        assertNotNull("station_name should exist", stationCol)
        assertEquals("VARCHAR", stationCol!!.type)
        // From ON conditions (no type)
        assertTrue("Should contain user_id from ON", colNames.contains("user_id"))
        assertTrue("Should contain dept_id from ON", colNames.contains("dept_id"))
    }

    // ---- F-1.1 新增测试 ----

    @Test
    fun `entity class name alias falls back to default alias`() {
        val stmt = SelectStatement(
            table = userTable, tableAlias = "t",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT, tableName = "order", alias = "orderEntity",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "t", columnName = "id"),
                            right = ColumnRef(tableAlias = "orderEntity", columnName = "user_id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(2, model.entities.size)
        // 实体类名含大写，fallback 为默认别名 t1
        assertEquals("t1", model.entities[1].alias)
        assertEquals("order", model.entities[1].tableName)
        assertTrue(model.relationships[0].label.contains("t.id = t1.user_id"))
    }

    @Test
    fun `sql alias with lowercase preserved`() {
        val stmt = SelectStatement(
            table = userTable, tableAlias = "t",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT, tableName = "order", alias = "tt",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "t", columnName = "id"),
                            right = ColumnRef(tableAlias = "tt", columnName = "user_id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals("tt", model.entities[1].alias)
    }

    @Test
    fun `subquery join shows Subquery as table name`() {
        val stmt = SelectStatement(
            table = userTable, tableAlias = "t",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT,
                    tableName = "(SELECT tt.id, tt.station_id, tt.station_name FROM station_info tt)",
                    alias = "tt",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "t", columnName = "id"),
                            right = ColumnRef(tableAlias = "tt", columnName = "station_id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals("Subquery", model.entities[1].tableName)
    }

    @Test
    fun `java types converted to sql types in columns`() {
        val tableWithTypes = TableInfo(
            entityClassName = "UserEntity",
            tableName = "user",
            columns = listOf(
                ColumnInfo("id", "id", isId = true, fieldType = "java.lang.Long"),
                ColumnInfo("name", "name", fieldType = "java.lang.String"),
                ColumnInfo("age", "age", fieldType = "java.lang.Integer"),
                ColumnInfo("created", "created_at", fieldType = "java.time.LocalDateTime"),
            ),
        )
        val stmt = SelectStatement(
            table = tableWithTypes, tableAlias = "t",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT, tableName = "order", alias = "o",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "t", columnName = "id"),
                            right = ColumnRef(tableAlias = "o", columnName = "user_id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        val cols = model.entities[0].columns
        assertEquals("BIGINT", cols[0].type)
        assertEquals("VARCHAR", cols[1].type)
        assertEquals("INT", cols[2].type)
        assertEquals("DATETIME", cols[3].type)
    }

    @Test
    fun `join entity has joinType set`() {
        val stmt = SelectStatement(
            table = userTable, tableAlias = "u",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT, tableName = "order", alias = "o",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "u", columnName = "id"),
                            right = ColumnRef(tableAlias = "o", columnName = "user_id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(JoinType.LEFT, model.entities[1].joinType)
        assertEquals(null, model.entities[0].joinType)
    }

    // ---- T-NativeUI-01 表注释数据链路 ----

    @Test
    fun `main table comment flows to entity`() {
        val commentedTable = tableWithComment("user", "用户表", userTable)
        val stmt = SelectStatement(
            table = commentedTable, tableAlias = "u",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT, tableName = "order", alias = "o",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "u", columnName = "id"),
                            right = ColumnRef(tableAlias = "o", columnName = "user_id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals("用户表", model.entities[0].tableComment)
        assertNull(model.entities[1].tableComment)
    }

    @Test
    fun `join table comment flows from join tableInfo`() {
        val deptWithComment = tableWithComment("dept", "部门表", deptTable)
        val stmt = SelectStatement(
            table = userTable, tableAlias = "u",
            joins = listOf(
                JoinClause(
                    type = JoinType.INNER, tableName = "dept", alias = "d", tableInfo = deptWithComment,
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "u", columnName = "dept_id"),
                            right = ColumnRef(tableAlias = "d", columnName = "id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals("部门表", model.entities[1].tableComment)
    }

    @Test
    fun `column comment flows through columns`() {
        val commentedTable = TableInfo(
            entityClassName = "UserEntity", tableName = "user",
            columns = listOf(
                ColumnInfo("id", "id", isId = true),
                ColumnInfo("name", "name", comment = "姓名"),
                ColumnInfo("email", "email", comment = "邮箱"),
            ),
        )
        val stmt = SelectStatement(
            table = commentedTable, tableAlias = "u",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT, tableName = "order", alias = "o",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "u", columnName = "id"),
                            right = ColumnRef(tableAlias = "o", columnName = "user_id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        val nameCol = model.entities[0].columns.first { it.name == "name" }
        assertEquals("姓名", nameCol.comment)
        assertEquals("邮箱", model.entities[0].columns.first { it.name == "email" }.comment)
    }

    // ---- T-Embed-07 JOIN 表字段空缺陷（转换器原始别名） ----

    @Test
    fun `join table columns extracted with converter original alias`() {
        // MPJ 真机形态：join.alias = 类名小驼峰（stationInfoEntity），显示别名归一为 t1；
        // selectAs/ON 的列引用携带原始别名，ON 标签经 aliasMap 归一显示正常，
        // 列提取须双别名匹配——否则 JOIN 表字段全部落空（2026-09-04 真机截图实证）
        val stmt = SelectStatement(
            table = userTable, tableAlias = "t",
            selectColumns = listOf(
                ColumnRef(tableAlias = "stationInfoEntity", columnName = "station_name", type = "java.lang.String"),
            ),
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT, tableName = "station_info", alias = "stationInfoEntity",
                    tableInfo = deptTable,
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "stationInfoEntity", columnName = "station_id"),
                            right = ColumnRef(tableAlias = "t", columnName = "station_id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        val joinEntity = model.entities[1]
        assertEquals("t1", joinEntity.alias)
        assertEquals("station_info", joinEntity.tableName)
        val colNames = joinEntity.columns.map { it.name }
        assertTrue("selectAs 列（原始别名）应提取，实际=$colNames", colNames.contains("station_name"))
        assertTrue("ON 列（原始别名）应提取，实际=$colNames", colNames.contains("station_id"))
    }

    /** 生成带表注释的副本。 */
    private fun tableWithComment(tableName: String, comment: String?, base: TableInfo): TableInfo =
        base.copy(tableName = tableName, comment = comment)

}
