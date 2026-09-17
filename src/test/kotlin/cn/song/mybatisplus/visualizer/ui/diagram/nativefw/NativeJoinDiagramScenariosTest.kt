package cn.song.mybatisplus.visualizer.ui.diagram.nativefw

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.sql.JoinDiagramGenerator
import com.intellij.diagram.presentation.DiagramLineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-Dgm-05 视觉验收的测试替代：以演示项目（mybatis-plus-sql-visualizer-demo）真实用户 Wrapper
 * 写法为蓝本，覆盖多表 JOIN / 别名≠表名 / 长列名 / 子查询 / 原始 SQL JOIN / PK 裁剪 / 各 JOIN 类型
 * 等「真实操作场景」，断言 SQL 模型 → 自绘中间模型 → 原生框架快照/图构建的全链路数据正确。
 *
 * 场景来源对照（演示项目 MpjExtServiceImpl 等）：
 *  - queryWrapperString          → S01（3 表字符串 JOIN，别名 addr/a ≠ 表名）
 *  - 4 表长列名专用方法           → S02（T-Dgm-05 验收场景）
 *  - selectAssociation           → S03（lambda JOIN 无显式别名）
 *  - selectCollectionWithFilter  → S04（lambda ON 多条件）
 */
class NativeJoinDiagramScenariosTest {

    private val userTable = TableInfo(
        entityClassName = "cn.song.mpjdemo.entity.UserDO",
        tableName = "user",
        columns = listOf(
            ColumnInfo("id", "id", isId = true, comment = "用户主键"),
            ColumnInfo("name", "name", comment = "用户姓名"),
            ColumnInfo("sex", "sex"),
        ),
    )

    private val addressTable = TableInfo(
        entityClassName = "cn.song.mpjdemo.entity.AddressDO",
        tableName = "user_address",
        columns = listOf(
            ColumnInfo("id", "id", isId = true, comment = "地址主键"),
            ColumnInfo("userId", "user_id", comment = "关联用户"),
            ColumnInfo("tel", "tel", comment = "联系电话"),
            ColumnInfo("address", "address"),
            ColumnInfo("status", "status", comment = "状态"),
        ),
    )

    private val areaTable = TableInfo(
        entityClassName = "cn.song.mpjdemo.entity.AreaDO",
        tableName = "area",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("province", "province", comment = "省份"),
        ),
    )

    /** 60 列大表（PK 裁剪上限触发）。 */
    private val deviceTable = TableInfo(
        entityClassName = "cn.song.mpjdemo.entity.DeviceInfoEntity",
        tableName = "car_device_info",
        columns = List(60) { idx ->
            if (idx == 0) ColumnInfo("id", "id", isId = true)
            else ColumnInfo("field$idx", "car_device_column_number_$idx")
        },
    )

    @Before
    fun cleanRegistry() {
        JoinDiagramSnapshotRegistry.clearForTest()
    }

    // ------------------------------------------------------------------
    // S01：3 表字符串 JOIN（queryWrapperString 写法，别名≠表名）
    // ------------------------------------------------------------------
    private fun s01Statement() = SelectStatement(
        table = userTable,
        tableAlias = "t",
        joins = listOf(
            JoinClause(
                type = JoinType.LEFT,
                tableName = "user_address",
                alias = "addr",
                tableInfo = addressTable,
                onConditions = listOf(
                    Condition.ColumnComparison(
                        left = ColumnRef(tableAlias = "t", columnName = "id"),
                        right = ColumnRef(tableAlias = "addr", columnName = "user_id"),
                    ),
                ),
            ),
            JoinClause(
                type = JoinType.RIGHT,
                tableName = "area",
                alias = "a",
                tableInfo = areaTable,
                onConditions = listOf(
                    Condition.ColumnComparison(
                        left = ColumnRef(tableAlias = "addr", columnName = "area_id"),
                        right = ColumnRef(tableAlias = "a", columnName = "id"),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun s01_threeTableStringJoin_buildsTwoEdgesWithAliasNodes() {
        val model = JoinDiagramGenerator.buildDiagramModel(s01Statement())
        assertEquals(3, model.entities.size)
        assertEquals(2, model.relationships.size)

        val main = model.entities.first { it.isMain }
        assertEquals("user", main.tableName)
        assertEquals("t", main.alias)

        val addr = model.entities.first { it.alias == "addr" }
        assertEquals("user_address", addr.tableName)
        assertEquals("user_address (addr)", addr.displayName)
        assertEquals(JoinType.LEFT, addr.joinType)

        val area = model.entities.first { it.alias == "a" }
        assertEquals(JoinType.RIGHT, area.joinType)

        assertEquals("t", model.relationships[0].fromAlias)
        assertEquals("addr", model.relationships[0].toAlias)
        assertEquals("t.id = addr.user_id", model.relationships[0].label)
        assertEquals("addr.area_id = a.id", model.relationships[1].label)
    }

    @Test
    fun s01b_snapshotAndGraphCarryAliasEntitiesAndEdgeLabels() {
        val id = JoinDiagramSnapshotRegistry.put(
            JoinDiagramGenerator.buildDiagramModel(s01Statement()),
            "JOIN Diagram: user",
        )
        val snapshot = JoinDiagramSnapshotRegistry.get(id)!!
        assertEquals(3, snapshot.entities.size)
        assertEquals(2, snapshot.edges.size)

        val graph = JoinDiagramGraph.build(snapshot, SqlJoinDiagramProvider())
        assertEquals(3, graph.nodes.size)
        assertEquals(2, graph.edges.size)
        // 字段注释经适配层往返保留（对齐 IDEA Database 原生图「显示图」的注释展示）
        val mainNode = graph.nodes.first { it.identifyingElement.isMain }
        val idCol = mainNode.identifyingElement.columns.first { it.name == "id" }
        assertEquals("用户主键", idCol.comment)
        val nameCol = mainNode.identifyingElement.columns.first { it.name == "name" }
        assertEquals("用户姓名", nameCol.comment)
        val sexCol = mainNode.identifyingElement.columns.first { it.name == "sex" }
        assertNull(sexCol.comment)
        // JOIN 表列注释（ON 条件引用列，注释来自 tableInfo）
        val addrNode = graph.nodes.first { it.identifyingElement.alias == "addr" }
        val userIdCol = addrNode.identifyingElement.columns.first { it.name == "user_id" }
        assertEquals("关联用户", userIdCol.comment)
        assertEquals("user_address (addr)", addrNode.identifyingElement.displayName)
        assertEquals("user_address AS addr", addrNode.tooltip)
        // 边端点与 ON 标签在图边中保序
        assertEquals("t", graph.edges[0].source.identifyingElement.alias)
        assertEquals("addr", graph.edges[0].target.identifyingElement.alias)
    }

    // ------------------------------------------------------------------
    // S02：4 表 JOIN + 超长列名（T-Dgm-05 验收场景）
    // ------------------------------------------------------------------
    @Test
    fun s02_fourTableLongColumnNames_fullChain() {
        val stmt = SelectStatement(
            table = userTable,
            tableAlias = "t",
            selectColumns = listOf(
                ColumnRef(tableAlias = "addr", columnName = "contact_mobile_telephone_number"),
                ColumnRef(tableAlias = "addr", columnName = "delivery_shipping_full_address_description"),
                ColumnRef(tableAlias = "a", columnName = "provincial_administrative_region_division_code"),
                ColumnRef(tableAlias = "dev", columnName = "station_identification_uuid_reference"),
            ),
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT, tableName = "user_address", alias = "addr",
                    tableInfo = addressTable,
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "t", columnName = "id"),
                            right = ColumnRef(tableAlias = "addr", columnName = "user_id"),
                        ),
                    ),
                ),
                JoinClause(
                    type = JoinType.RIGHT, tableName = "area", alias = "a",
                    tableInfo = areaTable,
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "addr", columnName = "area_id"),
                            right = ColumnRef(tableAlias = "a", columnName = "id"),
                        ),
                    ),
                ),
                JoinClause(
                    type = JoinType.LEFT, tableName = "car_device_info", alias = "dev",
                    tableInfo = deviceTable,
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "dev", columnName = "user_id"),
                            right = ColumnRef(tableAlias = "t", columnName = "id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(4, model.entities.size)
        // T-DgmFullCols 契约（2026-09-17 变更，取代此前「MAX_COLUMNS 只作用于主表、JOIN 表列 = 仅被引用列」）：
        // JOIN 表与主表同口径——实体整表列（>50 列时裁到主键列），被引用列（selectColumns + ON）
        // 无论实体里有没有都保留（SQL 原始列名可能不在实体中）。dev 实体 60 列 → 只剩主键 id，
        // 两个被引用列追加在后。
        val dev = model.entities.first { it.alias == "dev" }
        assertEquals(
            "60 列实体裁到主键列 + 被引用列追加",
            listOf("id", "station_identification_uuid_reference", "user_id"),
            dev.columns.map { it.name },
        )

        val id = JoinDiagramSnapshotRegistry.put(model, "JOIN Diagram: 4 tables")
        val graph = JoinDiagramGraph.build(JoinDiagramSnapshotRegistry.get(id), SqlJoinDiagramProvider())
        assertEquals(4, graph.nodes.size)
        assertEquals(3, graph.edges.size)
        // 主表列完整进入节点 items（原生图节点内容行数据源）
        val mainNode = graph.nodes.first { it.identifyingElement.isMain }
        assertEquals(3, mainNode.identifyingElement.columns.size)
        // 长列名的 JOIN 表列（selectColumns 来源）进入边对端节点
        val addrNode = graph.nodes.first { it.identifyingElement.alias == "addr" }
        assertTrue(addrNode.identifyingElement.columns.any { it.name == "contact_mobile_telephone_number" })
    }

    // ------------------------------------------------------------------
    // S03/S04：lambda JOIN（selectAssociation / selectCollectionWithFilter 写法）
    // ------------------------------------------------------------------
    @Test
    fun s03_lambdaJoin_associationMapping() {
        val stmt = SelectStatement(
            table = userTable,
            tableAlias = "t",
            selectColumns = listOf(
                ColumnRef(tableAlias = "t", columnName = "id"),
                ColumnRef(tableAlias = "addr", columnName = "address", type = "String"),
            ),
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT, tableName = "user_address", alias = "addr",
                    tableInfo = addressTable,
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "addr", columnName = "user_id"),
                            right = ColumnRef(tableAlias = "t", columnName = "id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(2, model.entities.size)
        val snapshotId = JoinDiagramSnapshotRegistry.put(model, "title")
        val graph = JoinDiagramGraph.build(JoinDiagramSnapshotRegistry.get(snapshotId), SqlJoinDiagramProvider())
        assertEquals(1, graph.edges.size)
        // selectAs 类型来源（ColumnRef.type）在节点列类型中可见
        val addrNode = graph.nodes.first { it.identifyingElement.alias == "addr" }
        assertEquals("VARCHAR", addrNode.identifyingElement.columns.first { it.name == "address" }.type)
    }

    @Test
    fun s04_lambdaOnMultiCondition_labelJoinedByAnd() {
        val stmt = SelectStatement(
            table = userTable,
            tableAlias = "t",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT, tableName = "user_address", alias = "addr",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "addr", columnName = "user_id"),
                            right = ColumnRef(tableAlias = "t", columnName = "id"),
                        ),
                        Condition.Simple(
                            column = ColumnRef(tableAlias = "addr", columnName = "status"),
                            op = "=",
                            value = SqlValue.Placeholder(source = "1"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        val label = model.relationships.single().label
        assertTrue(label.contains("addr.user_id = t.id"))
        assertTrue(label.contains("addr.status = ?"))
        assertTrue(label.contains(" AND "))

        // ON 条件标签进入原生框架关系信息（线上文字）
        JoinDiagramSnapshotRegistry.put(model, "title")
        val info = joinRelationshipInfo("t", "addr")
        assertTrue(info.label!!.contains("addr.user_id = t.id"))
        assertEquals(DiagramLineType.SOLID, info.lineType)
    }

    // ------------------------------------------------------------------
    // S05/S06：原始 SQL JOIN / 子查询显示名
    // ------------------------------------------------------------------
    @Test
    fun s05_rawSqlJoin_labelMarked() {
        val stmt = SelectStatement(
            table = userTable,
            tableAlias = "t",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT, tableName = "user_address", alias = "addr",
                    onConditions = emptyList(),
                    rawSql = "user_address addr on t.id = addr.user_id and addr.status = 1",
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals("(rawSql)", model.relationships.single().label)
    }

    @Test
    fun s06_subqueryDisplayName() {
        val stmt = SelectStatement(
            table = userTable,
            tableAlias = "t",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT,
                    tableName = "(SELECT id, area_id FROM user_address WHERE status = 1) sub",
                    alias = "sub",
                    onConditions = emptyList(),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals("Subquery", model.entities[1].tableName)
    }

    // ------------------------------------------------------------------
    // S07：PK 标识经适配层往返保留（原生图节点加粗 + PK 图标数据源）
    // ------------------------------------------------------------------
    @Test
    fun s07_pkFlagSurvivesAdapterRoundTrip() {
        val model = JoinDiagramGenerator.buildDiagramModel(s01Statement())
        val id = JoinDiagramSnapshotRegistry.put(model, "title")
        val mainEntity = JoinDiagramSnapshotRegistry.get(id)!!.entities.first { it.isMain }
        val pk = mainEntity.columns.first { it.isPk }
        assertEquals("id", pk.name)
        val addrEntity = mainEntity
        assertNull(addrEntity.joinType)
    }

    // ------------------------------------------------------------------
    // S08：JOIN 类型 → 线型映射全覆盖（FULL 虚线，其余实线）
    // ------------------------------------------------------------------
    @Test
    fun s08_joinTypeLineMapping_allTypes() {
        assertEquals(DiagramLineType.SOLID, lineTypeOf(JoinType.INNER))
        assertEquals(DiagramLineType.SOLID, lineTypeOf(JoinType.LEFT))
        assertEquals(DiagramLineType.SOLID, lineTypeOf(JoinType.RIGHT))
        assertEquals(DiagramLineType.DASHED, lineTypeOf(JoinType.FULL))
        assertEquals(DiagramLineType.SOLID, lineTypeOf(null))
    }

    // ------------------------------------------------------------------
    // S09：无 JOIN 单表 → 空模型（标签页不出现路径）
    // ------------------------------------------------------------------
    @Test
    fun s09_noJoin_emptyModelBuildsEmptyGraph() {
        val model = JoinDiagramGenerator.buildDiagramModel(
            SelectStatement(table = userTable, tableAlias = "t"),
        )
        assertTrue(model.isEmpty)
        val id = JoinDiagramSnapshotRegistry.put(model, "empty")
        assertEquals(0, JoinDiagramSnapshotRegistry.get(id)!!.entities.size)
        val graph = JoinDiagramGraph.build(JoinDiagramSnapshotRegistry.get(id), SqlJoinDiagramProvider())
        assertEquals(0, graph.nodes.size)
        assertEquals(0, graph.edges.size)
    }

    // ------------------------------------------------------------------
    // S10：两表同别名冲突（真实写法里同一张表 JOIN 两次）
    // ------------------------------------------------------------------
    @Test
    fun s10_duplicateAliasUniquified() {
        val stmt = SelectStatement(
            table = userTable,
            tableAlias = "t",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT, tableName = "user_address", alias = "addr",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "t", columnName = "id"),
                            right = ColumnRef(tableAlias = "addr", columnName = "user_id"),
                        ),
                    ),
                ),
                JoinClause(
                    type = JoinType.LEFT, tableName = "user_address", alias = "addr",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "t", columnName = "id"),
                            right = ColumnRef(tableAlias = "addr", columnName = "user_id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        val aliases = model.entities.map { it.alias }
        assertEquals(3, aliases.size)
        assertEquals(aliases.size, aliases.toSet().size)
        assertEquals("addr", aliases[1])
        assertEquals("addr_2", aliases[2])
        // 两条关系线指向不同别名（图连线不串线的数据前提）
        assertEquals("addr", model.relationships[0].toAlias)
        assertEquals("addr_2", model.relationships[1].toAlias)
    }

    // ------------------------------------------------------------------
    // S11：无别名回落默认别名（t1/t2）+ 梳状拓扑全部从主表出发
    // ------------------------------------------------------------------
    @Test
    fun s11_defaultAliasFallback_edgesStarTopology() {
        val stmt = SelectStatement(
            table = userTable,
            tableAlias = "t",
            joins = listOf(
                JoinClause(type = JoinType.LEFT, tableName = "user_address", alias = "", tableInfo = addressTable, onConditions = emptyList()),
                JoinClause(type = JoinType.LEFT, tableName = "area", alias = "", tableInfo = areaTable, onConditions = emptyList()),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(listOf("t", "t1", "t2"), model.entities.map { it.alias })
        assertTrue(model.relationships.all { it.fromAlias == "t" })
        val snapshotId = JoinDiagramSnapshotRegistry.put(model, "title")
        val graph = JoinDiagramGraph.build(JoinDiagramSnapshotRegistry.get(snapshotId), SqlJoinDiagramProvider())
        assertEquals(2, graph.edges.size)
        assertTrue(graph.edges.all { it.source.identifyingElement.alias == "t" })
    }

    // ------------------------------------------------------------------
    // S12：快照淘汰上限（预览面板长期驻留的真实累积）
    // ------------------------------------------------------------------
    @Test
    fun s12_registryEvictionKeepsRecentSnapshots() {
        val first = JoinDiagramSnapshotRegistry.put(JoinDiagramGenerator.buildDiagramModel(s01Statement()), "first")
        repeat(40) { idx ->
            JoinDiagramSnapshotRegistry.put(
                model = JoinDiagramGenerator.buildDiagramModel(s01Statement()),
                title = "n$idx",
            )
        }
        JoinDiagramSnapshotRegistry.evictBeyond(32)
        assertNull(JoinDiagramSnapshotRegistry.get(first))
    }
}
