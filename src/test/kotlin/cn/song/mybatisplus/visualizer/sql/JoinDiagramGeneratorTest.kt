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
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinDiagramGeneratorTest {

    private val userTable = TableInfo("UserEntity", "user", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("name", "name"),
            ColumnInfo("email", "email"),
            ColumnInfo("deptId", "dept_id"),
        ))

    private val orderTable = TableInfo("OrderEntity", "order", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("userId", "user_id"),
            ColumnInfo("amount", "amount"),
        ))

    private val deptTable = TableInfo("DeptEntity", "dept", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("name", "name"),
        ))

    private val addressTable = TableInfo("AddressEntity", "address", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("userId", "user_id"),
            ColumnInfo("city", "city"),
        ))

    @Test
    fun `single table no join returns empty model`() {
        val stmt = SelectStatement(userTable, emptyList(), null, emptyList(), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertTrue("model should be empty", model.isEmpty)
        assertEquals(0, model.entities.size)
        assertEquals(0, model.relationships.size)
    }

    @Test
    fun `empty joins list returns empty model`() {
        val stmt = SelectStatement(userTable, emptyList(), null, emptyList(), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertTrue("model should be empty", model.isEmpty)
    }

    @Test
    fun `two table left join`() {
        val stmt = SelectStatement(userTable, emptyList(), "u", listOf(
                JoinClause(JoinType.LEFT, "order", "o", listOf(
                        Condition.ColumnComparison(ColumnRef("u", "id"), ColumnRef("o", "user_id"), null),
                    )),
            ), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
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
        val stmt = SelectStatement(userTable, emptyList(), "u", listOf(
                JoinClause(JoinType.LEFT, "order", "o", listOf(
                        Condition.ColumnComparison(ColumnRef("u", "id"), ColumnRef("o", "user_id"), null),
                    )),
                JoinClause(JoinType.INNER, "dept", "d", listOf(
                        Condition.ColumnComparison(ColumnRef("u", "dept_id"), ColumnRef("d", "id"), null),
                    )),
            ), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
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
        val stmt = SelectStatement(userTable, emptyList(), "u", listOf(
                JoinClause(JoinType.RIGHT, "address", "a", listOf(
                        Condition.ColumnComparison(ColumnRef("u", "id"), ColumnRef("a", "user_id"), null),
                    )),
            ), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(JoinType.RIGHT, model.relationships[0].joinType)
    }

    @Test
    fun `full join relation type`() {
        val stmt = SelectStatement(userTable, emptyList(), "u", listOf(
                JoinClause(JoinType.FULL, "address", "a", listOf(
                        Condition.ColumnComparison(ColumnRef("u", "id"), ColumnRef("a", "user_id"), null),
                    )),
            ), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(JoinType.FULL, model.relationships[0].joinType)
    }

    @Test
    fun `rawSql join still renders`() {
        val stmt = SelectStatement(userTable, emptyList(), "u", listOf(
                JoinClause(JoinType.LEFT, "order", "o", emptyList(), "LEFT JOIN (SELECT * FROM order WHERE amount > 100) o ON u.id = o.user_id", null, null, null),
            ), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertFalse(model.isEmpty)
        assertEquals(2, model.entities.size)
        assertTrue(model.relationships[0].label.contains("rawSql"))
    }

    @Test
    fun `table with over 50 columns shows only PK`() {
        val manyColumns = (1..60).map { i -> ColumnInfo("field$i", "col_$i", i == 1) }
        val bigTable = TableInfo("BigEntity", "big_table", manyColumns)
        val stmt = SelectStatement(bigTable, emptyList(), "t", listOf(
                JoinClause(JoinType.INNER, "order", "o", listOf(
                        Condition.ColumnComparison(ColumnRef("t", "col_1"), ColumnRef("o", "id"), null),
                    )),
            ), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(1, model.entities[0].columns.size)
        assertTrue(model.entities[0].columns[0].isPk)
        assertEquals("col_1", model.entities[0].columns[0].name)
    }

    @Test
    fun `join without alias uses default alias`() {
        val stmt = SelectStatement(userTable, emptyList(), "t", listOf(
                JoinClause(JoinType.LEFT, "order", "", listOf(
                        Condition.ColumnComparison(ColumnRef("t", "id"), ColumnRef("user_id"), null),
                    )),
            ), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertFalse(model.isEmpty)
        assertEquals(2, model.entities.size)
        assertEquals("t1", model.entities[1].alias)
        assertEquals("order", model.entities[1].tableName)
    }

    @Test
    fun `multiple on conditions joined with AND`() {
        val stmt = SelectStatement(userTable, emptyList(), "u", listOf(
                JoinClause(JoinType.LEFT, "order", "o", listOf(
                        Condition.ColumnComparison(ColumnRef("u", "id"), ColumnRef("o", "user_id"), null),
                        Condition.ColumnComparison(ColumnRef("u", "dept_id"), ColumnRef("o", "dept_id"), null),
                    )),
            ), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        val label = model.relationships[0].label
        assertTrue(label.contains("u.id = o.user_id"))
        assertTrue(label.contains("u.dept_id = o.dept_id"))
        assertTrue(label.contains(" AND "))
    }

    @Test
    fun `join table shows columns from ON conditions and selectAs`() {
        val stmt = SelectStatement(userTable, listOf(
                ColumnRef("o", "station_name", null, null, null, null, "java.lang.String"),
            ), "u", listOf(
                JoinClause(JoinType.LEFT, "order", "o", listOf(
                        Condition.ColumnComparison(ColumnRef("u", "id"), ColumnRef("o", "user_id"), null),
                        Condition.ColumnComparison(ColumnRef("u", "dept_id"), ColumnRef("o", "dept_id"), null),
                    )),
            ), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
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
        val stmt = SelectStatement(userTable, emptyList(), "t", listOf(
                JoinClause(JoinType.LEFT, "order", "orderEntity", listOf(
                        Condition.ColumnComparison(ColumnRef("t", "id"), ColumnRef("orderEntity", "user_id"), null),
                    )),
            ), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(2, model.entities.size)
        // 实体类名含大写，fallback 为默认别名 t1
        assertEquals("t1", model.entities[1].alias)
        assertEquals("order", model.entities[1].tableName)
        assertTrue(model.relationships[0].label.contains("t.id = t1.user_id"))
    }

    @Test
    fun `sql alias with lowercase preserved`() {
        val stmt = SelectStatement(userTable, emptyList(), "t", listOf(
                JoinClause(JoinType.LEFT, "order", "tt", listOf(
                        Condition.ColumnComparison(ColumnRef("t", "id"), ColumnRef("tt", "user_id"), null),
                    )),
            ), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals("tt", model.entities[1].alias)
    }

    @Test
    fun `subquery join shows Subquery as table name`() {
        val stmt = SelectStatement(userTable, emptyList(), "t", listOf(
                JoinClause(JoinType.LEFT, "(SELECT tt.id, tt.station_id, tt.station_name FROM station_info tt)", "tt", listOf(
                        Condition.ColumnComparison(ColumnRef("t", "id"), ColumnRef("tt", "station_id"), null),
                    )),
            ), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals("Subquery", model.entities[1].tableName)
    }

    @Test
    fun `java types converted to sql types in columns`() {
        val tableWithTypes = TableInfo("UserEntity", "user", listOf(
                ColumnInfo("id", "id", true, false, "java.lang.Long"),
                ColumnInfo("name", "name", false, false, "java.lang.String"),
                ColumnInfo("age", "age", false, false, "java.lang.Integer"),
                ColumnInfo("created", "created_at", false, false, "java.time.LocalDateTime"),
            ))
        val stmt = SelectStatement(tableWithTypes, emptyList(), "t", listOf(
                JoinClause(JoinType.LEFT, "order", "o", listOf(
                        Condition.ColumnComparison(ColumnRef("t", "id"), ColumnRef("o", "user_id"), null),
                    )),
            ), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        val cols = model.entities[0].columns
        assertEquals("BIGINT", cols[0].type)
        assertEquals("VARCHAR", cols[1].type)
        assertEquals("INT", cols[2].type)
        assertEquals("DATETIME", cols[3].type)
    }

    @Test
    fun `join entity has joinType set`() {
        val stmt = SelectStatement(userTable, emptyList(), "u", listOf(
                JoinClause(JoinType.LEFT, "order", "o", listOf(
                        Condition.ColumnComparison(ColumnRef("u", "id"), ColumnRef("o", "user_id"), null),
                    )),
            ), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(JoinType.LEFT, model.entities[1].joinType)
        assertEquals(null, model.entities[0].joinType)
    }

}
