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
        "cn.song.mpjdemo.entity.UserDO",
        "user",
        listOf(
            ColumnInfo("id", "id", true, false, null, null, "用户主键"),
            ColumnInfo("name", "name", false, false, null, null, "用户姓名"),
            ColumnInfo("sex", "sex"),
        ),
    )

    private val addressTable = TableInfo(
        "cn.song.mpjdemo.entity.AddressDO",
        "user_address",
        listOf(
            ColumnInfo("id", "id", true, false, null, null, "地址主键"),
            ColumnInfo("userId", "user_id", false, false, null, null, "关联用户"),
            ColumnInfo("tel", "tel", false, false, null, null, "联系电话"),
            ColumnInfo("address", "address"),
            ColumnInfo("status", "status", false, false, null, null, "状态"),
        ),
    )

    private val areaTable = TableInfo(
        "cn.song.mpjdemo.entity.AreaDO",
        "area",
        listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("province", "province", false, false, null, null, "省份"),
        ),
    )

    /** 60 列大表（PK 裁剪上限触发）。 */
    private val deviceTable = TableInfo(
        "cn.song.mpjdemo.entity.DeviceInfoEntity",
        "car_device_info",
        List(60) { idx ->
            if (idx == 0) ColumnInfo("id", "id", true)
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
    private fun s01Statement() = selectStatement(
        userTable,
        "t",
        joins = listOf(
            joinClause(
                JoinType.LEFT,
                "user_address",
                "addr",
                tableInfo = addressTable,
                onConditions = listOf(
                    columnComparison(
                        ColumnRef("t", "id"),
                        ColumnRef("addr", "user_id"),
                    ),
                ),
            ),
            joinClause(
                JoinType.RIGHT,
                "area",
                "a",
                tableInfo = areaTable,
                onConditions = listOf(
                    columnComparison(
                        ColumnRef("addr", "area_id"),
                        ColumnRef("a", "id"),
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
        val stmt = selectStatement(
            userTable,
            "t",
            selectColumns = listOf(
                ColumnRef("addr", "contact_mobile_telephone_number"),
                ColumnRef("addr", "delivery_shipping_full_address_description"),
                ColumnRef("a", "provincial_administrative_region_division_code"),
                ColumnRef("dev", "station_identification_uuid_reference"),
            ),
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "user_address", "addr",
                    tableInfo = addressTable,
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("t", "id"),
                            ColumnRef("addr", "user_id"),
                        ),
                    ),
                ),
                joinClause(
                    JoinType.RIGHT, "area", "a",
                    tableInfo = areaTable,
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("addr", "area_id"),
                            ColumnRef("a", "id"),
                        ),
                    ),
                ),
                joinClause(
                    JoinType.LEFT, "car_device_info", "dev",
                    tableInfo = deviceTable,
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("dev", "user_id"),
                            ColumnRef("t", "id"),
                        ),
                    ),
                ),
            ),
        )
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(4, model.entities.size)
        // 真实契约：MAX_COLUMNS 裁剪仅作用于主表；JOIN 表列 = 被引用列（selectColumns + ON 条件），
        // dev 表即使 60 列也只显示 select/ON 引用到的列（图保持精简的天然机制）
        val dev = model.entities.first { it.alias == "dev" }
        assertEquals(listOf("station_identification_uuid_reference", "user_id"), dev.columns.map { it.name })

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
        val stmt = selectStatement(
            userTable,
            "t",
            selectColumns = listOf(
                ColumnRef("t", "id"),
                ColumnRef("addr", "address", null, null, null, null, "String"),
            ),
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "user_address", "addr",
                    tableInfo = addressTable,
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("addr", "user_id"),
                            ColumnRef("t", "id"),
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
        val stmt = selectStatement(
            userTable,
            "t",
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "user_address", "addr",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("addr", "user_id"),
                            ColumnRef("t", "id"),
                        ),
                        Condition.Simple(
                            ColumnRef("addr", "status"),
                            "=",
                            SqlValue.Placeholder("1"),
                            null,
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
        val info = JoinDiagramRenderTexts.relationshipInfo("t", "addr")
        assertTrue(info.label!!.contains("addr.user_id = t.id"))
        assertEquals(DiagramLineType.SOLID, info.lineType)
    }

    // ------------------------------------------------------------------
    // S05/S06：原始 SQL JOIN / 子查询显示名
    // ------------------------------------------------------------------
    @Test
    fun s05_rawSqlJoin_labelMarked() {
        val stmt = selectStatement(
            userTable,
            "t",
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "user_address", "addr",
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
        val stmt = selectStatement(
            userTable,
            "t",
            joins = listOf(
                joinClause(
                    JoinType.LEFT,
                    "(SELECT id, area_id FROM user_address WHERE status = 1) sub",
                    "sub",
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
        assertEquals(DiagramLineType.SOLID, JoinDiagramRenderTexts.lineTypeOf(JoinType.INNER))
        assertEquals(DiagramLineType.SOLID, JoinDiagramRenderTexts.lineTypeOf(JoinType.LEFT))
        assertEquals(DiagramLineType.SOLID, JoinDiagramRenderTexts.lineTypeOf(JoinType.RIGHT))
        assertEquals(DiagramLineType.DASHED, JoinDiagramRenderTexts.lineTypeOf(JoinType.FULL))
        assertEquals(DiagramLineType.SOLID, JoinDiagramRenderTexts.lineTypeOf(null))
    }

    // ------------------------------------------------------------------
    // S09：无 JOIN 单表 → 空模型（标签页不出现路径）
    // ------------------------------------------------------------------
    @Test
    fun s09_noJoin_emptyModelBuildsEmptyGraph() {
        val model = JoinDiagramGenerator.buildDiagramModel(
            selectStatement(userTable, "t"),
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
        val stmt = selectStatement(
            userTable,
            "t",
            joins = listOf(
                joinClause(
                    JoinType.LEFT, "user_address", "addr",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("t", "id"),
                            ColumnRef("addr", "user_id"),
                        ),
                    ),
                ),
                joinClause(
                    JoinType.LEFT, "user_address", "addr",
                    onConditions = listOf(
                        columnComparison(
                            ColumnRef("t", "id"),
                            ColumnRef("addr", "user_id"),
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
        val stmt = selectStatement(
            userTable,
            "t",
            joins = listOf(
                joinClause(JoinType.LEFT, "user_address", "", tableInfo = addressTable, onConditions = emptyList()),
                joinClause(JoinType.LEFT, "area", "", tableInfo = areaTable, onConditions = emptyList()),
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
                JoinDiagramGenerator.buildDiagramModel(s01Statement()),
                "n$idx",
            )
        }
        JoinDiagramSnapshotRegistry.evictBeyond(32)
        assertNull(JoinDiagramSnapshotRegistry.get(first))
    }
}
