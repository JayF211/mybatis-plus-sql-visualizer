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
        val stmt = SelectStatement(userTable)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertTrue("model should be empty", model.isEmpty)
        assertEquals(0, model.entities.size)
        assertEquals(0, model.relationships.size)
    }

    @Test
    fun `empty joins list returns empty model`() {
        val stmt = SelectStatement(userTable)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertTrue("model should be empty", model.isEmpty)
    }

    @Test
    fun `two table left join`() {
        val stmt = SelectStatement(
            userTable, emptyList(), "u",
            listOf(
                JoinClause(
                    JoinType.LEFT, "order", "o",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("o", "user_id"),
                            null,
                        ),
                    ),
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
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
            userTable, emptyList(), "u",
            listOf(
                JoinClause(
                    JoinType.LEFT, "order", "o",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("o", "user_id"),
                            null,
                        ),
                    ),
                ),
                JoinClause(
                    JoinType.INNER, "dept", "d",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("u", "dept_id"),
                            ColumnRef("d", "id"),
                            null,
                        ),
                    ),
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
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
            userTable, emptyList(), "u",
            listOf(
                JoinClause(
                    JoinType.RIGHT, "address", "a",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("a", "user_id"),
                            null,
                        ),
                    ),
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(JoinType.RIGHT, model.relationships[0].joinType)
    }

    @Test
    fun `full join relation type`() {
        val stmt = SelectStatement(
            userTable, emptyList(), "u",
            listOf(
                JoinClause(
                    JoinType.FULL, "address", "a",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("a", "user_id"),
                            null,
                        ),
                    ),
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(JoinType.FULL, model.relationships[0].joinType)
    }

    @Test
    fun `rawSql join still renders`() {
        val stmt = SelectStatement(
            userTable, emptyList(), "u",
            listOf(
                JoinClause(
                    JoinType.LEFT, "order", "o",
                    emptyList(),
                    "LEFT JOIN (SELECT * FROM order WHERE amount > 100) o ON u.id = o.user_id",
                    null, null, null,
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
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
        val stmt = SelectStatement(
            bigTable, emptyList(), "t",
            listOf(
                JoinClause(
                    JoinType.INNER, "order", "o",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("t", "col_1"),
                            ColumnRef("o", "id"),
                            null,
                        ),
                    ),
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(1, model.entities[0].columns.size)
        assertTrue(model.entities[0].columns[0].isPk)
        assertEquals("col_1", model.entities[0].columns[0].name)
    }

    @Test
    fun `join without alias uses default alias`() {
        val stmt = SelectStatement(
            userTable, emptyList(), "t",
            listOf(
                JoinClause(
                    JoinType.LEFT, "order", "",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("t", "id"),
                            ColumnRef("user_id"),
                            null,
                        ),
                    ),
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
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
            userTable, emptyList(), "u",
            listOf(
                JoinClause(
                    JoinType.LEFT, "order", "o",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("o", "user_id"),
                            null,
                        ),
                        Condition.ColumnComparison(
                            ColumnRef("u", "dept_id"),
                            ColumnRef("o", "dept_id"),
                            null,
                        ),
                    ),
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
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
            userTable,
            listOf(
                ColumnRef("o", "station_name", null, null, null, null, "java.lang.String"),
            ),
            "u",
            listOf(
                JoinClause(
                    JoinType.LEFT, "order", "o",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("o", "user_id"),
                            null,
                        ),
                        Condition.ColumnComparison(
                            ColumnRef("u", "dept_id"),
                            ColumnRef("o", "dept_id"),
                            null,
                        ),
                    ),
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
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
            userTable, emptyList(), "t",
            listOf(
                JoinClause(
                    JoinType.LEFT, "order", "orderEntity",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("t", "id"),
                            ColumnRef("orderEntity", "user_id"),
                            null,
                        ),
                    ),
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
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
            userTable, emptyList(), "t",
            listOf(
                JoinClause(
                    JoinType.LEFT, "order", "tt",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("t", "id"),
                            ColumnRef("tt", "user_id"),
                            null,
                        ),
                    ),
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals("tt", model.entities[1].alias)
    }

    @Test
    fun `subquery join shows Subquery as table name`() {
        val stmt = SelectStatement(
            userTable, emptyList(), "t",
            listOf(
                JoinClause(
                    JoinType.LEFT,
                    "(SELECT tt.id, tt.station_id, tt.station_name FROM station_info tt)",
                    "tt",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("t", "id"),
                            ColumnRef("tt", "station_id"),
                            null,
                        ),
                    ),
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
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
        val stmt = SelectStatement(
            tableWithTypes, emptyList(), "t",
            listOf(
                JoinClause(
                    JoinType.LEFT, "order", "o",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("t", "id"),
                            ColumnRef("o", "user_id"),
                            null,
                        ),
                    ),
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
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
            userTable, emptyList(), "u",
            listOf(
                JoinClause(
                    JoinType.LEFT, "order", "o",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("o", "user_id"),
                            null,
                        ),
                    ),
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
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
            commentedTable, emptyList(), "u",
            listOf(
                JoinClause(
                    JoinType.LEFT, "order", "o",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("o", "user_id"),
                            null,
                        ),
                    ),
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals("用户表", model.entities[0].tableComment)
        assertNull(model.entities[1].tableComment)
    }

    @Test
    fun `join table comment flows from join tableInfo`() {
        val deptWithComment = tableWithComment("dept", "部门表", deptTable)
        val stmt = SelectStatement(
            userTable, emptyList(), "u",
            listOf(
                JoinClause(
                    JoinType.INNER, "dept", "d",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("u", "dept_id"),
                            ColumnRef("d", "id"),
                            null,
                        ),
                    ),
                    null, null, null, deptWithComment,
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
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
        val stmt = SelectStatement(
            commentedTable, emptyList(), "u",
            listOf(
                JoinClause(
                    JoinType.LEFT, "order", "o",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("o", "user_id"),
                            null,
                        ),
                    ),
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        val nameCol = model.entities[0].columns.first { it.name == "name" }
        assertEquals("姓名", nameCol.comment)
        assertEquals("邮箱", model.entities[0].columns.first { it.name == "email" }.comment)
    }

    // ---- T-DgmFullCols JOIN 表整表列口径（2026-09-17：与主表一致，观感对齐 Database 原生「显示图」） ----

    @Test
    fun `join table shows full entity columns not only referenced ones`() {
        // 此前 JOIN 表只画「SELECT 引用列 + ON 列」，通常就一两个 join key，与主表"整表口径"不对称
        val stmt = SelectStatement(
            userTable, emptyList(), "u",
            listOf(
                JoinClause(
                    JoinType.INNER, "dept", "d",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("u", "dept_id"),
                            ColumnRef("d", "id"),
                            null,
                        ),
                    ),
                    null, null, null, deptTable,
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        val dept = model.entities.first { it.alias == "d" }
        assertEquals("JOIN 表 = 实体整表列（id 主键 + name），不再只剩 ON 引用的 id", listOf("id", "name"), dept.columns.map { it.name })
        assertEquals("实体主键标记保留", true, dept.columns.first { it.name == "id" }.isPk)
    }

    @Test
    fun `join table keeps referenced column absent from entity`() {
        // SQL 原始列名可能不在实体里（selectAs/拼接列）——引用列无论实体有没有都保留
        val stmt = SelectStatement(
            userTable,
            listOf(ColumnRef("d", "raw_pseudo_col")),
            "u",
            listOf(
                JoinClause(
                    JoinType.LEFT, "dept", "d",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("u", "dept_id"),
                            ColumnRef("d", "id"),
                            null,
                        ),
                    ),
                    null, null, null, deptTable,
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        val dept = model.entities.first { it.alias == "d" }
        assertEquals(listOf("id", "name", "raw_pseudo_col"), dept.columns.map { it.name })
    }

    @Test
    fun `join table over column cap falls back to pk plus referenced columns`() {
        // 与主表同款上限：实体 >50 列时只留主键列；被引用列仍追加（它们可能不在实体里）
        val wideTable = TableInfo(
            "WideEntity", "wide",
            List(60) { idx -> if (idx == 0) ColumnInfo("id", "id", true) else ColumnInfo("f$idx", "f_$idx") },
        )
        val stmt = SelectStatement(
            userTable,
            listOf(ColumnRef("w", "ref_only")),
            "u",
            listOf(
                JoinClause(
                    JoinType.LEFT, "wide", "w",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("w", "id"),
                            null,
                        ),
                    ),
                    null, null, null, wideTable,
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(listOf("id", "ref_only"), model.entities.first { it.alias == "w" }.columns.map { it.name })
    }

    // ---- T-Embed-07 JOIN 表字段空缺陷（转换器原始别名） ----

    @Test
    fun `join table columns extracted with converter original alias`() {
        // MPJ 真机形态：join.alias = 类名小驼峰（stationInfoEntity），显示别名归一为 t1；
        // selectAs/ON 的列引用携带原始别名，ON 标签经 aliasMap 归一显示正常，
        // 列提取须双别名匹配——否则 JOIN 表字段全部落空（2026-09-04 真机截图实证）
        val stmt = SelectStatement(
            userTable,
            listOf(
                ColumnRef("stationInfoEntity", "station_name", null, null, null, null, "java.lang.String"),
            ),
            "t",
            listOf(
                JoinClause(
                    JoinType.LEFT, "station_info", "stationInfoEntity",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("stationInfoEntity", "station_id"),
                            ColumnRef("t", "station_id"),
                            null,
                        ),
                    ),
                    null, null, null, deptTable,
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
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
        val stmt = SelectStatement(
            userTable, emptyList(), "t",
            listOf(
                JoinClause(
                    JoinType.LEFT, "station_info", "t1",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("t1", "station_id"),
                            ColumnRef("t", "station_id"),
                            null,
                        ),
                        Condition.ColumnComparison(
                            ColumnRef("t", "id"),
                            ColumnRef("t1", "owner_id"),
                            null,
                        ),
                    ),
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
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
        val stmt = SelectStatement(
            userTable, emptyList(), "t",
            listOf(
                JoinClause(
                    JoinType.LEFT, "order", "o",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("o", "user_id"),
                            ColumnRef("t", "id"),
                            null,
                        ),
                        Condition.Raw("t.deleted = 0"),
                    ),
                ),
            ),
            false, emptyList(), emptyList(), null, emptyList(),
            null, null, null, null, emptyList(), null, null,
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(1, model.relationships[0].columnPairs.size)
    }

    /** 生成带表注释的副本。 */
    private fun tableWithComment(tableName: String, comment: String?, base: TableInfo): TableInfo =
        base.copy(
            base.entityClassName, tableName, base.columns,
            base.logicDeleteColumn, base.versionColumn, comment, base.associations,
        )

}
