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

    // 生产代码为 Java（SelectStatement/JoinClause 无默认参数与具名实参），以下 helper 以
    // 默认参数还原 master Kotlin data class 的缺省语义（selectColumns/conditions 等为空、可空项 null）。

    private fun selectStatement(
        table: TableInfo,
        tableAlias: String? = null,
        selectColumns: List<ColumnRef> = emptyList(),
        joins: List<JoinClause> = emptyList(),
    ): SelectStatement = SelectStatement(
        table, selectColumns, tableAlias, joins, false,
        emptyList(), emptyList(), null, emptyList(), null, null, null, null,
        emptyList(), null, null,
    )

    private fun joinClause(
        type: JoinType,
        tableName: String,
        alias: String,
        onConditions: List<Condition> = emptyList(),
        rawSql: String? = null,
        tableInfo: TableInfo? = null,
    ): JoinClause = JoinClause(type, tableName, alias, onConditions, rawSql, null, null, tableInfo)

    private fun columnComparison(left: ColumnRef, right: ColumnRef): Condition =
        Condition.ColumnComparison(left, right, null)

    private val userTable = TableInfo(
        "UserEntity",
        "user",
        listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("name", "name"),
            ColumnInfo("email", "email"),
            ColumnInfo("deptId", "dept_id"),
        ),
    )

    private val orderTable = TableInfo(
        "OrderEntity",
        "order",
        listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("userId", "user_id"),
            ColumnInfo("amount", "amount"),
        ),
    )

    private val deptTable = TableInfo(
        "DeptEntity",
        "dept",
        listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("name", "name"),
        ),
    )

    private val addressTable = TableInfo(
        "AddressEntity",
        "address",
        listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("userId", "user_id"),
            ColumnInfo("city", "city"),
        ),
    )

    @Test
    fun `single table no join returns empty model`() {
        val stmt = selectStatement(userTable)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertTrue("model should be empty", model.isEmpty)
        assertEquals(0, model.entities.size)
        assertEquals(0, model.relationships.size)
    }

    @Test
    fun `empty joins list returns empty model`() {
        val stmt = selectStatement(userTable)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertTrue("model should be empty", model.isEmpty)
    }

    @Test
    fun `two table left join`() {
        val stmt = selectStatement(
            userTable, "u",
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "order", "o",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("o", "user_id"),
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
        val stmt = selectStatement(
            userTable, "u",
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "order", "o",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("o", "user_id"),
                        ),
                    ),
                ),
                joinClause(
                    JoinType.INNER, "dept", "d",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("u", "dept_id"),
                            ColumnRef("d", "id"),
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
        val stmt = selectStatement(
            userTable, "u",
            joins = listOf(
                joinClause(
                    JoinType.RIGHT, "address", "a",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("a", "user_id"),
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
        val stmt = selectStatement(
            userTable, "u",
            joins = listOf(
                joinClause(
                    JoinType.FULL, "address", "a",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("a", "user_id"),
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
        val stmt = selectStatement(
            userTable, "u",
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "order", "o",
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
        val manyColumns = (1..60).map { i -> ColumnInfo("field$i", "col_$i", i == 1) }
        val bigTable = TableInfo("BigEntity", "big_table", manyColumns)
        val stmt = selectStatement(
            bigTable, "t",
            joins = listOf(
                joinClause(
                    JoinType.INNER, "order", "o",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("t", "col_1"),
                            ColumnRef("o", "id"),
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
        val stmt = selectStatement(
            userTable, "t",
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "order", "",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("t", "id"),
                            ColumnRef("user_id"),
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
        val stmt = selectStatement(
            userTable, "u",
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "order", "o",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("o", "user_id"),
                        ),
                        columnComparison(
                            ColumnRef("u", "dept_id"),
                            ColumnRef("o", "dept_id"),
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
        val stmt = selectStatement(
            userTable, "u",
            selectColumns = listOf(
                ColumnRef("o", "station_name", null, null, null, null, "java.lang.String"),
            ),
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "order", "o",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("o", "user_id"),
                        ),
                        columnComparison(
                            ColumnRef("u", "dept_id"),
                            ColumnRef("o", "dept_id"),
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
        val stmt = selectStatement(
            userTable, "t",
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "order", "orderEntity",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("t", "id"),
                            ColumnRef("orderEntity", "user_id"),
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
        val stmt = selectStatement(
            userTable, "t",
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "order", "tt",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("t", "id"),
                            ColumnRef("tt", "user_id"),
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
        val stmt = selectStatement(
            userTable, "t",
            joins = listOf(
                joinClause(
                    JoinType.LEFT,
                    "(SELECT tt.id, tt.station_id, tt.station_name FROM station_info tt)",
                    "tt",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("t", "id"),
                            ColumnRef("tt", "station_id"),
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
            "UserEntity",
            "user",
            listOf(
                ColumnInfo("id", "id", true, false, "java.lang.Long"),
                ColumnInfo("name", "name", false, false, "java.lang.String"),
                ColumnInfo("age", "age", false, false, "java.lang.Integer"),
                ColumnInfo("created", "created_at", false, false, "java.time.LocalDateTime"),
            ),
        )
        val stmt = selectStatement(
            tableWithTypes, "t",
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "order", "o",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("t", "id"),
                            ColumnRef("o", "user_id"),
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
        val stmt = selectStatement(
            userTable, "u",
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "order", "o",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("o", "user_id"),
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
        val stmt = selectStatement(
            commentedTable, "u",
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "order", "o",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("o", "user_id"),
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
        val stmt = selectStatement(
            userTable, "u",
            joins = listOf(
                joinClause(
                    JoinType.INNER, "dept", "d", tableInfo = deptWithComment,
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("u", "dept_id"),
                            ColumnRef("d", "id"),
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
            "UserEntity", "user",
            listOf(
                ColumnInfo("id", "id", true),
                ColumnInfo("name", "name", false, false, null, null, "姓名"),
                ColumnInfo("email", "email", false, false, null, null, "邮箱"),
            ),
        )
        val stmt = selectStatement(
            commentedTable, "u",
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "order", "o",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("o", "user_id"),
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
        val stmt = selectStatement(
            userTable, "t",
            selectColumns = listOf(
                ColumnRef("stationInfoEntity", "station_name", null, null, null, null, "java.lang.String"),
            ),
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "station_info", "stationInfoEntity",
                    tableInfo = deptTable,
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("stationInfoEntity", "station_id"),
                            ColumnRef("t", "station_id"),
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

    // ---- T-Embed-04 ON 等值列配对（虚拟外键数据基础） ----

    @Test
    fun `on column pairs extracted with main-to-join orientation`() {
        val stmt = selectStatement(
            userTable, "t",
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "station_info", "t1",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("t1", "station_id"),
                            ColumnRef("t", "station_id"),
                        ),
                        columnComparison(
                            ColumnRef("t", "id"),
                            ColumnRef("t1", "owner_id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        val pairs = model.relationships[0].columnPairs
        assertEquals(2, pairs.size)
        // 方向统一为 (主表侧列, JOIN 表侧列)
        assertTrue("t1.station_id = t.station_id → (station_id, station_id)", Pair("station_id", "station_id") in pairs)
        assertTrue("t.id = t1.owner_id → (id, owner_id)", Pair("id", "owner_id") in pairs)
    }

    @Test
    fun `on column pairs skip non-comparison conditions`() {
        val stmt = selectStatement(
            userTable, "t",
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "order", "o",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("o", "user_id"),
                            ColumnRef("t", "id"),
                        ),
                        Condition.Raw("t.deleted = 0"),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(1, model.relationships[0].columnPairs.size)
    }

    /** 生成带表注释的副本（Java 端无 data class copy——完整构造器重建）。 */
    private fun tableWithComment(tableName: String, comment: String?, base: TableInfo): TableInfo =
        TableInfo(
            base.entityClassName, tableName, base.columns,
            base.logicDeleteColumn, base.versionColumn, comment, base.associations,
        )

}
