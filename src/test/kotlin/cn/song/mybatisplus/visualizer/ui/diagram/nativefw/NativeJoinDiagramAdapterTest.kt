package cn.song.mybatisplus.visualizer.ui.diagram.nativefw

import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.ui.diagram.ColumnEntry
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel
import cn.song.mybatisplus.visualizer.ui.diagram.EntityNode
import cn.song.mybatisplus.visualizer.ui.diagram.RelationshipLine
import com.intellij.diagram.DiagramCategory
import com.intellij.diagram.DiagramRelationshipInfo
import com.intellij.diagram.presentation.DiagramLineType
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-Dgm-05：原生图框架适配层纯单测（快照注册表 + 边样式 + 解析器 + 节点）。
 *
 * 平台类（DiagramLineType 等）随 bundledPlugin("com.intellij.diagram") 进入测试 classpath，
 * 不依赖 EDT / 项目模型，纯 JVM 可跑。
 */
class NativeJoinDiagramAdapterTest {

    private val userEntity = EntityNode(
        "u",
        "user",
        listOf(
            ColumnEntry("id", "BIGINT", true, "主键"),
            ColumnEntry("name", "VARCHAR", false, "姓名"),
        ),
        true,
        null,
        "用户表",
    )

    private val orderEntity = EntityNode(
        "o",
        "order",
        listOf(ColumnEntry("user_id", "BIGINT", false, "用户ID")),
        false,
        JoinType.LEFT,
        "订单表",
    )

    private val model = DiagramModel(
        listOf(userEntity, orderEntity),
        listOf(
            RelationshipLine("u", "o", JoinType.LEFT, "u.id = o.user_id"),
        ),
    )

    @Before
    fun cleanRegistry() {
        JoinDiagramSnapshotRegistry.clearForTest()
    }

    @Test
    fun putAndGetRoundTrip() {
        val id = JoinDiagramSnapshotRegistry.put(model, "title")
        val snapshotRef = JoinDiagramSnapshotRegistry.get(id)
        assertNotNull(snapshotRef)
        val snapshot = snapshotRef!!
        assertEquals("title", snapshot.title)
        assertEquals(2, snapshot.entities.size)
        assertEquals(1, snapshot.edges.size)
        val main = snapshot.entities.first { it.isMain }
        assertEquals("user", main.tableName)
        assertEquals(2, main.columns.size)
        // T-NativeUI-01：字段注释与表注释须经快照往返保留
        assertEquals("主键", main.columns[0].comment)
        assertEquals("用户表", main.tableComment)
        assertEquals("订单表", snapshot.entities.first { it.alias == "o" }.tableComment)
        assertEquals("u.id = o.user_id", snapshot.edges[0].label)
        assertEquals(JoinType.LEFT, snapshot.edges[0].joinType)
    }

    @Test
    fun snapshotIdFormatSafeForUmlVirtualFile() {
        // 快照 id 不得含 '.'——UmlEditorProvider 以扩展名 "uml" 识别旧格式文件
        val id = JoinDiagramSnapshotRegistry.put(model, "title")
        assertTrue(id.startsWith("snapshot"))
        assertTrue(!id.contains('.'))
    }

    @Test
    fun getMissingSnapshotReturnsNull() {
        assertNull(JoinDiagramSnapshotRegistry.get("snapshot999999"))
    }

    @Test
    fun findEdgeResolvesByAliases() {
        JoinDiagramSnapshotRegistry.put(model, "title")
        val edgeRef = JoinDiagramSnapshotRegistry.findEdge("u", "o")
        assertNotNull(edgeRef)
        val edge = edgeRef!!
        assertEquals(JoinType.LEFT, edge.joinType)
        assertNull(JoinDiagramSnapshotRegistry.findEdge("o", "u"))
        assertNull(JoinDiagramSnapshotRegistry.findEdge("x", "y"))
    }

    @Test
    fun evictBeyondKeepsNewest() {
        val first = JoinDiagramSnapshotRegistry.put(model, "first")
        val second = JoinDiagramSnapshotRegistry.put(model, "second")
        JoinDiagramSnapshotRegistry.evictBeyond(1)
        assertNull(JoinDiagramSnapshotRegistry.get(first))
        assertNotNull(JoinDiagramSnapshotRegistry.get(second))
    }

    @Test
    fun joinTypeToLineType() {
        assertEquals(DiagramLineType.SOLID, JoinDiagramRenderTexts.lineTypeOf(JoinType.INNER))
        assertEquals(DiagramLineType.SOLID, JoinDiagramRenderTexts.lineTypeOf(JoinType.LEFT))
        assertEquals(DiagramLineType.SOLID, JoinDiagramRenderTexts.lineTypeOf(JoinType.RIGHT))
        assertEquals(DiagramLineType.DASHED, JoinDiagramRenderTexts.lineTypeOf(JoinType.FULL))
        assertEquals(DiagramLineType.SOLID, JoinDiagramRenderTexts.lineTypeOf(null))
    }

    @Test
    fun relationshipInfoCarriesLabelAndJoinType() {
        JoinDiagramSnapshotRegistry.put(model, "title")
        val info = JoinDiagramRenderTexts.relationshipInfo("u", "o")
        assertEquals("u.id = o.user_id", info.label)
        assertEquals(DiagramLineType.SOLID, info.lineType)
    }

    @Test
    fun relationshipInfoFallsBackWhenEdgeMissing() {
        val info = JoinDiagramRenderTexts.relationshipInfo("x", "y")
        assertEquals("", info.label)
        assertEquals(DiagramLineType.SOLID, info.lineType)
    }

    /** Project 为大接口，测试用动态代理空实现（resolver 实现不触碰 project）。 */
    private fun stubProject(): Project = Project::class.java.cast(
        java.lang.reflect.Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, _, _ -> null },
    )

    @Test
    fun vfsResolverResolvesMainEntity() {
        val id = JoinDiagramSnapshotRegistry.put(model, "title")
        val resolver = JoinEntityVfsResolver()
        val entityRef = resolver.resolveElementByFQN(id, stubProject())
        assertNotNull(entityRef)
        val entity = entityRef!!
        assertTrue(entity.isMain)
        assertEquals("u", resolver.getQualifiedName(entity))
    }

    @Test
    fun vfsResolverMissingFqnReturnsNull() {
        assertNull(JoinEntityVfsResolver().resolveElementByFQN("snapshot404", stubProject()))
    }

    @Test
    fun nodeExposesEntityIdentityAndTooltip() {
        val node = JoinTableNode(
            JoinDiagramSnapshotRegistry.NativeEntity("u", "user", emptyList(), true, null, null),
            SqlJoinDiagramProvider(),
        )
        assertEquals("u", node.identifyingElement.alias)
        // 别名≠表名时 tooltip 显示 AS 形式（T-Dgm-05 场景测试发现的缺陷修复后语义）
        assertEquals("user AS u", node.tooltip)
        assertNotNull(node.icon)
    }

    @Test
    fun graphFactoryBuildsNodesAndEdges() {
        val id = JoinDiagramSnapshotRegistry.put(model, "title")
        val graph = JoinDiagramGraph.build(JoinDiagramSnapshotRegistry.get(id), SqlJoinDiagramProvider())
        assertEquals(2, graph.nodes.size)
        assertEquals(1, graph.edges.size)
        assertEquals("user AS u", graph.nodes.first { it.identifyingElement.isMain }.tooltip)
        assertEquals("u", graph.edges[0].source.identifyingElement.alias)
        assertEquals("o", graph.edges[0].target.identifyingElement.alias)
    }

    @Test
    fun graphFactoryMissingSnapshotBuildsEmptyGraph() {
        val graph = JoinDiagramGraph.build(JoinDiagramSnapshotRegistry.get("snapshot404"), SqlJoinDiagramProvider())
        assertEquals(0, graph.nodes.size)
        assertEquals(0, graph.edges.size)
    }

    // ---- T-NativeUI-02 分类体系 ----

    @Test
    fun contentManagerDefinesThreeNativeCategories() {
        val cats = JoinDiagramNodeContentManager.INSTANCE.contentCategories
        assertEquals(3, cats.size)
        // 对齐 Database 的 COLUMNS / ID_COLUMNS / COMMENTS 命名与启用默认
        assertTrue(cats.any { it == JoinDiagramNodeContentManager.COLUMNS })
        assertTrue(cats.any { it == JoinDiagramNodeContentManager.KEY_COLUMNS })
        assertTrue(cats.any { it == JoinDiagramNodeContentManager.COMMENTS })
    }

    @Test
    fun contentManagerEnabledByDefault() {
        // T-NativeUI-02 根因：Abstract 分类管理器默认空状态把字段过滤掉——
        // 本站直接实现接口且默认全启用，新图不应过滤任何列
        assertTrue(JoinDiagramNodeContentManager.INSTANCE.isCategoryEnabled(JoinDiagramNodeContentManager.COLUMNS))
        assertTrue(JoinDiagramNodeContentManager.INSTANCE.isCategoryEnabled(JoinDiagramNodeContentManager.KEY_COLUMNS))
        assertEquals(3, JoinDiagramNodeContentManager.INSTANCE.enabledCategories.size)
    }

    @Test
    fun contentManagerClassifiesColumnsByPk() {
        val pk = ColumnEntry("id", "BIGINT", true)
        val normal = ColumnEntry("name", "VARCHAR", false)
        // 参数顺序与框架一致：isInCategory(identifyingElement, item, category, builder)——第 2 参是字段项
        assertTrue(JoinDiagramNodeContentManager.INSTANCE.isInCategory(null, pk, JoinDiagramNodeContentManager.KEY_COLUMNS, null))
        assertFalse(JoinDiagramNodeContentManager.INSTANCE.isInCategory(null, pk, JoinDiagramNodeContentManager.COLUMNS, null))
        assertTrue(JoinDiagramNodeContentManager.INSTANCE.isInCategory(null, normal, JoinDiagramNodeContentManager.COLUMNS, null))
        assertFalse(JoinDiagramNodeContentManager.INSTANCE.isInCategory(null, normal, JoinDiagramNodeContentManager.KEY_COLUMNS, null))
        // 注释切换类不对列行过滤（与 Database 一致：COMMENTS 单独呈现）
        assertFalse(JoinDiagramNodeContentManager.INSTANCE.isInCategory(null, pk, JoinDiagramNodeContentManager.COMMENTS, null))
    }

    @Test
    fun contentManagerToggleTracksEnabled() {
        JoinDiagramNodeContentManager.INSTANCE.setCategoryEnabled(JoinDiagramNodeContentManager.COLUMNS, false)
        try {
            assertFalse(JoinDiagramNodeContentManager.INSTANCE.isCategoryEnabled(JoinDiagramNodeContentManager.COLUMNS))
            assertTrue(JoinDiagramNodeContentManager.INSTANCE.isCategoryEnabled(JoinDiagramNodeContentManager.KEY_COLUMNS))
        } finally {
            JoinDiagramNodeContentManager.INSTANCE.setCategoryEnabled(JoinDiagramNodeContentManager.COLUMNS, true)
        }
    }

    // ---- T-NativeUI-04 连接线语义化 ----

    private fun registerEdge(joinType: JoinType): String {
        return JoinDiagramSnapshotRegistry.put(
            DiagramModel(
                listOf(
                    userEntity,
                    EntityNode(orderEntity.alias, orderEntity.tableName, orderEntity.columns,
                            orderEntity.isMain, joinType, orderEntity.tableComment),
                ),
                listOf(
                    RelationshipLine("u", "o", joinType, "u.id = o.user_id"),
                ),
            ),
            "edge-$joinType",
        )
    }

    @Test
    fun innerJoinEdgeUsesSolidDeltaToTarget() {
        registerEdge(JoinType.INNER)
        val info = JoinDiagramRenderTexts.relationshipInfo("u", "o")
        assertEquals(DiagramLineType.SOLID, info.lineType)
        assertEquals(1, info.width)
        assertEquals(DiagramRelationshipInfo.NONE, info.sourceArrow)
        assertEquals(DiagramRelationshipInfo.DELTA, info.targetArrow)
        assertEquals("u.id = o.user_id", info.label)
    }

    @Test
    fun leftJoinEdgeUsesSmallDeltaToTarget() {
        registerEdge(JoinType.LEFT)
        val info = JoinDiagramRenderTexts.relationshipInfo("u", "o")
        assertEquals(DiagramLineType.SOLID, info.lineType)
        assertEquals(DiagramRelationshipInfo.NONE, info.sourceArrow)
        assertEquals(DiagramRelationshipInfo.DELTA_SMALL, info.targetArrow)
    }

    @Test
    fun rightJoinEdgeMirrorsDeltaToSource() {
        registerEdge(JoinType.RIGHT)
        val info = JoinDiagramRenderTexts.relationshipInfo("u", "o")
        assertEquals(DiagramLineType.SOLID, info.lineType)
        assertEquals(DiagramRelationshipInfo.DELTA, info.sourceArrow)
        assertEquals(DiagramRelationshipInfo.NONE, info.targetArrow)
    }

    @Test
    fun fullJoinEdgeIsDashedWideBothWhiteDeltas() {
        registerEdge(JoinType.FULL)
        val info = JoinDiagramRenderTexts.relationshipInfo("u", "o")
        assertEquals(DiagramLineType.DASHED, info.lineType)
        assertEquals(2, info.width)
        assertEquals(DiagramRelationshipInfo.WHITE_DELTA, info.sourceArrow)
        assertEquals(DiagramRelationshipInfo.WHITE_DELTA, info.targetArrow)
    }

    @Test
    fun missingEdgeFallsBackToInnerSolidNoArrow() {
        val info = JoinDiagramRenderTexts.relationshipInfo("x", "y")
        assertEquals(DiagramLineType.SOLID, info.lineType)
        assertEquals(DiagramRelationshipInfo.NONE, info.sourceArrow)
        assertEquals(DiagramRelationshipInfo.DELTA, info.targetArrow)
        assertEquals("", info.label)
    }

    @Test
    fun lineTypeWidthArrowsHelpers() {
        assertEquals(DiagramLineType.DASHED, JoinDiagramRenderTexts.lineTypeOf(JoinType.FULL))
        assertEquals(DiagramLineType.SOLID, JoinDiagramRenderTexts.lineTypeOf(JoinType.INNER))
        assertEquals(1, JoinDiagramRenderTexts.widthOf(JoinType.INNER))
        assertEquals(2, JoinDiagramRenderTexts.widthOf(JoinType.FULL))
        assertEquals(DiagramRelationshipInfo.DELTA, JoinDiagramRenderTexts.endArrowOf(JoinType.INNER))
        assertEquals(DiagramRelationshipInfo.WHITE_DELTA, JoinDiagramRenderTexts.endArrowOf(JoinType.FULL))
    }

    // ---- T-Embed-07 原生样式对齐（/* */ 注释 / PK 沉底分区） ----

    @Test
    fun contentManagerOrdersColumnsBeforeKeysForNativePkPartition() {
        // 分类顺序即画布分区顺序（框架逐 enabled 分类 append、组间分隔符）——
        // COLUMNS 在前、KEY_COLUMNS 收尾 = 原生 Database「显示图」PK 底部独立分区
        val cats = JoinDiagramNodeContentManager.INSTANCE.contentCategories
        val columnsIdx = cats.indexOf(JoinDiagramNodeContentManager.COLUMNS)
        val keysIdx = cats.indexOf(JoinDiagramNodeContentManager.KEY_COLUMNS)
        assertTrue("COLUMNS 应排在 KEY_COLUMNS 之前（PK 沉底），实际=$cats", columnsIdx < keysIdx)
    }

    @Test
    fun itemNameInlinesCommentAsNativeBlockCommentStyle() {
        val rendered = JoinDiagramRenderTexts.joinItemNameText("station_id", false, "站点ID").toString()
        assertTrue("字段名应保留，实际=$rendered", rendered.contains("station_id"))
        assertTrue("注释应内联为 /* */ 样式，实际=$rendered", rendered.contains("/* 站点ID */"))
    }

    @Test
    fun itemNameBoldWhenPk() {
        val rendered = JoinDiagramRenderTexts.joinItemNameText("id", true, null).toString()
        assertEquals("id", rendered.trim())
    }

    @Test
    fun itemTypeCarriesTypeOnly() {
        val rendered = JoinDiagramRenderTexts.joinItemTypeText("int(11)").toString()
        assertTrue("类型列应保留，实际=$rendered", rendered.contains("int(11)"))
        assertFalse("类型列不承载注释", rendered.contains("站点ID"))
    }

    @Test
    fun presentableTitleShowsTableCommentAsBlockComment() {
        val rendered = JoinDiagramRenderTexts.joinTitleText("car_device_info (t)", "设备信息表").toString()
        assertTrue("表名应保留，实际=$rendered", rendered.contains("car_device_info (t)"))
        assertTrue("表注释应为 /* */ 样式，实际=$rendered", rendered.contains("/* 设备信息表 */"))
    }

    @Test
    fun rendererDelegatesKeepNativeLayout() {
        // 覆写方法与顶层纯函数一致（类型列独立、不承载注释）
        val col = ColumnEntry("station_id", "int(11)", false, "站点ID")
        val viaOverride = JoinTableElementManager().getItemType(null, col, null)
        assertEquals(JoinDiagramRenderTexts.joinItemTypeText("int(11)").toString(), viaOverride.toString())
    }
}
