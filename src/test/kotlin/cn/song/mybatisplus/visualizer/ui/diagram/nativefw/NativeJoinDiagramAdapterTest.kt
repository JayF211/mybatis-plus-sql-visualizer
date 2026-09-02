package cn.song.mybatisplus.visualizer.ui.diagram.nativefw

import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.ui.diagram.ColumnEntry
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel
import cn.song.mybatisplus.visualizer.ui.diagram.EntityNode
import cn.song.mybatisplus.visualizer.ui.diagram.RelationshipLine
import com.intellij.diagram.presentation.DiagramLineType
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
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
        alias = "u",
        tableName = "user",
        columns = listOf(
            ColumnEntry("id", "BIGINT", isPk = true),
            ColumnEntry("name", "VARCHAR", isPk = false),
        ),
        isMain = true,
    )

    private val orderEntity = EntityNode(
        alias = "o",
        tableName = "order",
        columns = listOf(ColumnEntry("user_id", "BIGINT", isPk = false)),
        isMain = false,
        joinType = JoinType.LEFT,
    )

    private val model = DiagramModel(
        entities = listOf(userEntity, orderEntity),
        relationships = listOf(
            RelationshipLine(fromAlias = "u", toAlias = "o", joinType = JoinType.LEFT, label = "u.id = o.user_id"),
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
        JoinDiagramSnapshotRegistry.evictBeyond(limit = 1)
        assertNull(JoinDiagramSnapshotRegistry.get(first))
        assertNotNull(JoinDiagramSnapshotRegistry.get(second))
    }

    @Test
    fun joinTypeToLineType() {
        assertEquals(DiagramLineType.SOLID, lineTypeOf(JoinType.INNER))
        assertEquals(DiagramLineType.SOLID, lineTypeOf(JoinType.LEFT))
        assertEquals(DiagramLineType.SOLID, lineTypeOf(JoinType.RIGHT))
        assertEquals(DiagramLineType.DASHED, lineTypeOf(JoinType.FULL))
        assertEquals(DiagramLineType.SOLID, lineTypeOf(null))
    }

    @Test
    fun relationshipInfoCarriesLabelAndJoinType() {
        JoinDiagramSnapshotRegistry.put(model, "title")
        val info = joinRelationshipInfo("u", "o")
        assertEquals("u.id = o.user_id", info.label)
        assertEquals(DiagramLineType.SOLID, info.lineType)
    }

    @Test
    fun relationshipInfoFallsBackWhenEdgeMissing() {
        val info = joinRelationshipInfo("x", "y")
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
            JoinDiagramSnapshotRegistry.NativeEntity(
                alias = "u", tableName = "user",
                columns = emptyList(), isMain = true, joinType = null,
            ),
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
}
