package cn.song.mybatisplus.visualizer.ui.diagram.nativefw

import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.ui.diagram.ColumnEntry
import com.intellij.diagram.AbstractDiagramElementManager
import com.intellij.diagram.DiagramBuilder
import com.intellij.diagram.DiagramColorManagerBase
import com.intellij.diagram.DiagramDataModel
import com.intellij.diagram.DiagramEdge
import com.intellij.diagram.DiagramEdgeBase
import com.intellij.diagram.DiagramNode
import com.intellij.diagram.DiagramNodeBase
import com.intellij.diagram.DiagramNodeContentManager
import com.intellij.diagram.DiagramPresentationModel
import com.intellij.diagram.DiagramProvider
import com.intellij.diagram.DiagramRelationshipInfo
import com.intellij.diagram.DiagramRelationshipInfoAdapter
import com.intellij.diagram.DiagramRelationshipManager
import com.intellij.diagram.DiagramVfsResolver
import com.intellij.diagram.DiagramVisibilityManager
import com.intellij.diagram.EmptyDiagramVisibilityManager
import com.intellij.diagram.presentation.DiagramLineType
import com.intellij.diagram.presentation.DiagramState
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleColoredText
import com.intellij.ui.SimpleTextAttributes
import javax.swing.Icon

/**
 * T-Dgm-01/03：JOIN 图的 IDEA 原生图框架 Provider。
 *
 * 接入模式与 DatabaseTools 的 DbDiagramProvider / QueryPlanProvider 一致：
 * 实现 [DiagramProvider] 并注册到 `com.intellij.diagram.Provider` EP
 * （声明在 diagram-optional.xml——仅 Ultimate 捆绑的 Diagrams 插件存在时加载）。
 *
 * 建图数据来自 [JoinDiagramSnapshotRegistry]：打开图前调用方先把 SQL 解析产物登记为快照，
 * 本 Provider 以快照 id（FQN）回查并展开为节点/边——Provider 自身不接触 PSI/SQL 模型。
 *
 * 隔离铁律：本文件（及 nativefw 包内引用 com.intellij.diagram 的文件）只在 Diagrams 插件存在时
 * 被 [NativeJoinDiagramSupport] 门面触发加载；Community 下走自绘 [cn.song.mybatisplus.visualizer.ui.diagram.DiagramPanel]。
 */
class SqlJoinDiagramProvider : DiagramProvider<JoinDiagramSnapshotRegistry.NativeEntity>() {

    override fun getID(): String = ID

    override fun getPresentableName(): String = "SQL Visualizer JOIN Diagram"

    override fun createDataModel(
        project: Project,
        element: JoinDiagramSnapshotRegistry.NativeEntity?,
        file: VirtualFile?,
        presentationModel: DiagramPresentationModel,
    ): DiagramDataModel<JoinDiagramSnapshotRegistry.NativeEntity> =
        JoinDiagramDataModel(project, this, file)

    override fun createVisibilityManager(): DiagramVisibilityManager = EmptyDiagramVisibilityManager.INSTANCE

    override fun getElementManager(): JoinTableElementManager = JoinTableElementManager()

    override fun getVfsResolver(): JoinEntityVfsResolver = JoinEntityVfsResolver()

    override fun getRelationshipManager(): DiagramRelationshipManager<JoinDiagramSnapshotRegistry.NativeEntity> =
        JoinRelationshipManager()

    override fun createNodeContentManager(): DiagramNodeContentManager = DiagramNodeContentManager.NO_CATEGORIES

    override fun getColorManager(): com.intellij.diagram.DiagramColorManager = DiagramColorManagerBase()

    companion object {
        /** Provider id：同时是 UmlVirtualFile name 的第一段（`<ID>/<fqn>`，字节码实证分隔符 `/`）。 */
        const val ID = "SqlJoinDiagram"

        /** 按 id 查找已注册的 Provider（Diagrams 插件加载本类后才可用；未注册返回 null）。 */
        @JvmStatic
        fun registeredProvider(): DiagramProvider<JoinDiagramSnapshotRegistry.NativeEntity>? =
            DiagramProvider.findByID<JoinDiagramSnapshotRegistry.NativeEntity>(ID)

        @JvmStatic
        fun isProviderRegistered(): Boolean = registeredProvider() != null
    }
}

/**
 * 表节点元素管理器：节点标题 / 列 items / tooltip。
 * [AbstractDiagramElementManager] 全部为默认实现，仅覆写建图必需项。
 */
class JoinTableElementManager : AbstractDiagramElementManager<JoinDiagramSnapshotRegistry.NativeEntity>() {

    override fun findInDataContext(dataContext: DataContext): JoinDiagramSnapshotRegistry.NativeEntity? = null

    override fun isAcceptableAsNode(o: Any?): Boolean = o is JoinDiagramSnapshotRegistry.NativeEntity

    /** 节点内容行 = 表列（名称 + 类型 + 注释）。 */
    override fun getNodeItems(e: JoinDiagramSnapshotRegistry.NativeEntity): Array<Any> = e.columns.toTypedArray()

    override fun canCollapse(e: JoinDiagramSnapshotRegistry.NativeEntity): Boolean = false

    override fun isContainerFor(
        container: JoinDiagramSnapshotRegistry.NativeEntity,
        inner: JoinDiagramSnapshotRegistry.NativeEntity,
    ): Boolean = false

    override fun getElementTitle(e: JoinDiagramSnapshotRegistry.NativeEntity): String = e.displayName

    override fun getItemName(item: Any?, builder: DiagramBuilder): SimpleColoredText {
        val text = SimpleColoredText()
        val column = item as? ColumnEntry
        // 列名（主键加粗）+ 类型（灰色）
        text.append(
            column?.name ?: "",
            if (column?.isPk == true) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES,
        )
        column?.type?.let { text.append(" $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        return text
    }

    override fun getItemType(item: Any?): SimpleColoredText {
        // 字段注释（DB COMMENT）—— 对齐 IDEA Database 原生图「显示图」的注释展示。
        val text = SimpleColoredText()
        (item as? ColumnEntry)?.comment?.takeIf { it.isNotBlank() }?.let {
            text.append(it, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        }
        return text
    }

    override fun getItemIcon(item: Any?, state: DiagramState): Icon? =
        (item as? ColumnEntry)?.let { if (it.isPk) AllIcons.Nodes.FieldPK else AllIcons.Nodes.DataColumn }

    override fun getNodeTooltip(e: JoinDiagramSnapshotRegistry.NativeEntity): String =
        if (e.alias != e.tableName) "${e.tableName} AS ${e.alias}" else e.tableName
}

/**
 * FQN 解析：FQN = 快照 id；解析回主表实体（框架用它作为 createDataModel 的种子元素）。
 */
class JoinEntityVfsResolver : DiagramVfsResolver<JoinDiagramSnapshotRegistry.NativeEntity> {

    override fun getQualifiedName(e: JoinDiagramSnapshotRegistry.NativeEntity?): String? = e?.alias

    override fun resolveElementByFQN(fqn: String, project: Project): JoinDiagramSnapshotRegistry.NativeEntity? {
        val snapshot = JoinDiagramSnapshotRegistry.get(fqn) ?: return null
        return snapshot.entities.firstOrNull { it.isMain } ?: snapshot.entities.firstOrNull()
    }
}

/**
 * 关系边样式：JOIN 类型 → 线型，ON 条件 → 线上标签。
 * 框架在建边/重建时回调，按「最近一个同时包含两端别名的快照」解析边样式。
 */
class JoinRelationshipManager : DiagramRelationshipManager<JoinDiagramSnapshotRegistry.NativeEntity> {

    override fun getDependencyInfo(
        from: JoinDiagramSnapshotRegistry.NativeEntity,
        to: JoinDiagramSnapshotRegistry.NativeEntity,
        aCategory: com.intellij.diagram.DiagramCategory?,
    ): DiagramRelationshipInfo =
        joinRelationshipInfo(from.alias, to.alias)
}

/** 快照内的边样式解析（RelationshipManager 与 [JoinTableEdge] 共用）。 */
internal fun joinRelationshipInfo(fromAlias: String, toAlias: String): DiagramRelationshipInfo {
    val edge = JoinDiagramSnapshotRegistry.findEdge(fromAlias, toAlias)
    return DiagramRelationshipInfoAdapter(
        edge?.joinType?.name ?: JoinType.INNER.name,
        lineTypeOf(edge?.joinType),
        edge?.label ?: "",
    )
}

/** JOIN 类型 → 线型：FULL（外连接全包含）虚线，其余实线。 */
internal fun lineTypeOf(joinType: JoinType?): DiagramLineType =
    if (joinType == JoinType.FULL) DiagramLineType.DASHED else DiagramLineType.SOLID

/**
 * 建图数据模型：快照 → 节点/边。
 * 快照缺失（如进程内注册表已被清理）时返回空图。
 */
class JoinDiagramDataModel(
    project: Project,
    provider: SqlJoinDiagramProvider,
    file: VirtualFile?,
) : DiagramDataModel<JoinDiagramSnapshotRegistry.NativeEntity>(project, provider) {

    /** UmlVirtualFile name = "<providerId>/<snapshotId>"，取 `/` 之后为快照 id。 */
    private val snapshotId: String? = file?.name?.let { name ->
        val idx = name.indexOf('/')
        if (idx >= 0) name.substring(idx + 1) else name
    }

    private val graph: JoinDiagramGraph =
        JoinDiagramGraph.build(snapshotId?.let { JoinDiagramSnapshotRegistry.get(it) }, provider)

    override fun getNodes(): Collection<DiagramNode<JoinDiagramSnapshotRegistry.NativeEntity>> = graph.nodes

    override fun getEdges(): Collection<DiagramEdge<JoinDiagramSnapshotRegistry.NativeEntity>> = graph.edges

    override fun getNodeName(node: DiagramNode<JoinDiagramSnapshotRegistry.NativeEntity>): String =
        node.identifyingElement?.displayName ?: ""

    override fun addElement(element: JoinDiagramSnapshotRegistry.NativeEntity?): DiagramNode<JoinDiagramSnapshotRegistry.NativeEntity>? = null

    override fun getModificationTracker(): ModificationTracker = ModificationTracker.NEVER_CHANGED

    override fun dispose() {
        // 快照归注册表所有（evictBeyond 控制上限），DataModel 无额外资源
    }
}

/**
 * 节点/边构建结果（[JoinDiagramGraph.build] 纯函数，便于无 Project 的纯 JVM 单测）。
 */
internal class JoinDiagramGraph(
    val nodes: List<JoinTableNode>,
    val edges: List<JoinTableEdge>,
) {
    companion object {
        internal fun build(
            snapshot: JoinDiagramSnapshotRegistry.Snapshot?,
            provider: SqlJoinDiagramProvider,
        ): JoinDiagramGraph {
            if (snapshot == null) return JoinDiagramGraph(emptyList(), emptyList())
            val nodes = snapshot.entities.map { JoinTableNode(it, provider) }
            val byAlias = nodes.associateBy { it.identifyingElement.alias }
            val edges = snapshot.edges.mapNotNull { edge ->
                val from = byAlias[edge.fromAlias] ?: return@mapNotNull null
                val to = byAlias[edge.toAlias] ?: return@mapNotNull null
                JoinTableEdge(from, to)
            }
            return JoinDiagramGraph(nodes, edges)
        }
    }
}

/** 表节点。 */
class JoinTableNode(
    private val entity: JoinDiagramSnapshotRegistry.NativeEntity,
    provider: SqlJoinDiagramProvider,
) : DiagramNodeBase<JoinDiagramSnapshotRegistry.NativeEntity>(provider) {

    override fun getIdentifyingElement(): JoinDiagramSnapshotRegistry.NativeEntity = entity

    override fun getIcon(): javax.swing.Icon = AllIcons.Nodes.DataTables

    override fun getTooltip(): String =
        if (entity.alias != entity.tableName) "${entity.tableName} AS ${entity.alias}" else entity.tableName
}

/** 关系边（样式由 [joinRelationshipInfo] 按 JOIN 类型给出）。 */
class JoinTableEdge(
    source: JoinTableNode,
    target: JoinTableNode,
) : DiagramEdgeBase<JoinDiagramSnapshotRegistry.NativeEntity>(
    source,
    target,
    joinRelationshipInfo(source.identifyingElement.alias, target.identifyingElement.alias),
)
