package cn.song.mybatisplus.visualizer.ui.diagram.nativefw

import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.ui.diagram.ColumnEntry
import com.intellij.diagram.AbstractDiagramElementManager
import com.intellij.diagram.DiagramBuilder
import com.intellij.diagram.DiagramCategory
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
import java.awt.Shape
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
 * T-NativeUI：渲染完全复用 Database 原生图体系——表头「表名 + 表注释（灰色斜体）」、
 * 字段行「PK 图标 + 名 + 类型 + 注释」、连接线按 JOIN 类型区分箭头/线型/ON 标签、
 * 工具栏保留分类切换（COLUMNS/KEY_COLUMNS/COMMENTS）。插件只控制「字段展示」和「连接线数据」。
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

    /**
     * T-NativeUI-02：分类体系。直接实现 [DiagramNodeContentManager] 而非继承
     * AbstractDiagramNodeContentManager——后者内部启用集合初始为空、又受持久化（DiagramContentSettings）
     * 过滤，新图会把列全滤掉。直接实现 + 默认全启用，既让字段默认展示，又保留 toolbar 分类切换。
     */
    override fun createNodeContentManager(): DiagramNodeContentManager = JoinDiagramNodeContentManager

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
 * T-NativeUI-02：节点分类管理器（对齐 Database 的 DbDiagramProvider$1）。
 *
 * 分类：
 *  - COLUMNS / KEY_COLUMNS：普通列 / 主键列——决定哪些字段行展示（toolbar 可独立显隐）
 *  - COMMENTS：字段注释——对齐 Database 工具栏里的 Comments 开关；字段注释内联渲染在列行里，
 *    本站不把注释单列为 item（与 Database「名+类型+注释」同一行一致），故 [isInCategory] 对其返回 false。
 *
 * 直接实现接口、默认全启用（enabledByDefault=true 且启用集合初值含全部三类），
 * 避免 Abstract 的持久化空状态把字段过滤掉（T-NativeUI-02 根因）。
 */
object JoinDiagramNodeContentManager : DiagramNodeContentManager {

    val COLUMNS = DiagramCategory({ "Columns" }, AllIcons.Nodes.DataColumn, true)
    val KEY_COLUMNS = DiagramCategory({ "Key columns" }, AllIcons.Nodes.FieldPK, true)
    val COMMENTS = DiagramCategory({ "Comments" }, AllIcons.Nodes.Tag, true)

    private val CATEGORIES = arrayOf(KEY_COLUMNS, COLUMNS, COMMENTS)

    private val enabled: MutableSet<DiagramCategory> =
        java.util.concurrent.ConcurrentHashMap.newKeySet<DiagramCategory>().apply { addAll(CATEGORIES) }

    override fun getContentCategories(): Array<DiagramCategory> = CATEGORIES

    override fun isCategoryEnabled(category: DiagramCategory): Boolean = category in enabled

    override fun setCategoryEnabled(category: DiagramCategory, enabled: Boolean) {
        if (enabled) this.enabled.add(category) else this.enabled.remove(category)
    }

    override fun getEnabledCategories(): Array<DiagramCategory> =
        CATEGORIES.filter { it in enabled }.toTypedArray()

    // 字段项可能出现在两个 Object 参数的任一位（不同平台版本框架传递顺序存在差异，字节码实证不稳）——
    // 两个槽位都查 ColumnEntry，取第一个命中项，消除对参数顺序的依赖。
    override fun isInCategory(
        identifyingElement: Any?,
        item: Any?,
        category: DiagramCategory,
        builder: DiagramBuilder?,
    ): Boolean {
        val column = listOf(item, identifyingElement).filterIsInstance<ColumnEntry>().firstOrNull() ?: return false
        return if (column.isPk) category == KEY_COLUMNS else category == COLUMNS
    }
}

/**
 * T-NativeUI-01/03：表节点元素管理器：表头（表名 + 表注释）、字段 item（名 + 类型 + 注释）。
 * 渲染全交框架（NodeItemComponent 走 3 参 getItemName/getItemType，f92db8b 实证）。
 */
class JoinTableElementManager : AbstractDiagramElementManager<JoinDiagramSnapshotRegistry.NativeEntity>() {

    override fun findInDataContext(dataContext: DataContext): JoinDiagramSnapshotRegistry.NativeEntity? = null

    override fun isAcceptableAsNode(o: Any?): Boolean = o is JoinDiagramSnapshotRegistry.NativeEntity

    /** 节点内容行 = 表列（名称 + 类型 + 注释；渲染见 3 参 getItemName/getItemType）。 */
    override fun getNodeItems(e: JoinDiagramSnapshotRegistry.NativeEntity): Array<Any> = e.columns.toTypedArray()

    override fun canCollapse(e: JoinDiagramSnapshotRegistry.NativeEntity): Boolean = false

    override fun isContainerFor(
        container: JoinDiagramSnapshotRegistry.NativeEntity,
        inner: JoinDiagramSnapshotRegistry.NativeEntity,
    ): Boolean = false

    override fun getElementTitle(e: JoinDiagramSnapshotRegistry.NativeEntity): String = e.displayName

    /** T-NativeUI-03：表头 = 表名(别名) + 表注释（灰色斜体），对齐 Database 标题栏。 */
    override fun getPresentableElementTitle(
        e: JoinDiagramSnapshotRegistry.NativeEntity,
        builder: DiagramBuilder,
    ): SimpleColoredText {
        val text = SimpleColoredText()
        text.append(e.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        e.tableComment?.takeIf { it.isNotBlank() }?.let {
            text.append("  $it", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        }
        return text
    }

    // 框架 DiagramNodeItemComponent 调用的是 3 参重载（identifyingElement, item, builder）——
    // 只实现 2 参会让字段文字落回空默认，只剩图标。故实现 3 参并按 Database 原生样式渲染。
    override fun getItemName(identifyingElement: JoinDiagramSnapshotRegistry.NativeEntity?, item: Any?, builder: DiagramBuilder): SimpleColoredText {
        val text = SimpleColoredText()
        val column = item as? ColumnEntry
        // 字段名（主键加粗）
        text.append(
            column?.name ?: "",
            if (column?.isPk == true) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES,
        )
        return text
    }

    override fun getItemType(identifyingElement: JoinDiagramSnapshotRegistry.NativeEntity?, item: Any?, builder: DiagramBuilder?): SimpleColoredText {
        val text = SimpleColoredText()
        val column = item as? ColumnEntry
        // 类型（灰色）+ 字段注释（灰色斜体），对齐 IDEA Database 原生图
        column?.type?.let { text.append(it, SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        column?.comment?.takeIf { it.isNotBlank() }?.let {
            text.append("  $it", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        }
        return text
    }

    override fun getItemIcon(item: Any?, state: DiagramState): Icon? =
        (item as? ColumnEntry)?.let { if (it.isPk) AllIcons.Nodes.FieldPK else AllIcons.Nodes.DataColumn }

    override fun getNodeTooltip(e: JoinDiagramSnapshotRegistry.NativeEntity): String {
        val base = if (e.alias != e.tableName) "${e.tableName} AS ${e.alias}" else e.tableName
        return e.tableComment?.takeIf { it.isNotBlank() }?.let { "$base — $it" } ?: base
    }
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
 * 关系边样式：JOIN 类型 → 箭头/线型/宽度，ON 条件 → 线上标签。
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
    val joinType = edge?.joinType ?: JoinType.INNER
    return DiagramRelationshipInfoAdapter.Builder()
        .setName(joinType.name)
        .setLineType(lineTypeOf(joinType))
        .setWidth(widthOf(joinType))
        .setSourceArrow(startArrowOf(joinType))
        .setTargetArrow(endArrowOf(joinType))
        .setUpperCenterLabel(edge?.label ?: "")
        .create()
}

/** JOIN 类型 → 线型：FULL（外连接全包含）虚线，其余实线。 */
internal fun lineTypeOf(joinType: JoinType?): DiagramLineType =
    if (joinType == JoinType.FULL) DiagramLineType.DASHED else DiagramLineType.SOLID

/** JOIN 类型 → 线宽：FULL 略粗以强化"全包含"语义，其余 1。 */
internal fun widthOf(joinType: JoinType?): Int = if (joinType == JoinType.FULL) 2 else 1

/** 起点箭头（主表侧）：RIGHT 指向主表，其余无箭头。 */
internal fun startArrowOf(joinType: JoinType?): Shape = when (joinType) {
    JoinType.RIGHT -> DiagramRelationshipInfo.DELTA
    JoinType.FULL -> DiagramRelationshipInfo.WHITE_DELTA
    else -> DiagramRelationshipInfo.NONE
}

/** 终点箭头（JOIN 表侧）：INNER/LEFT 指向 JOIN 表，FULL 双向白色三角。 */
internal fun endArrowOf(joinType: JoinType?): Shape = when (joinType) {
    JoinType.INNER -> DiagramRelationshipInfo.DELTA
    JoinType.LEFT -> DiagramRelationshipInfo.DELTA_SMALL
    JoinType.FULL -> DiagramRelationshipInfo.WHITE_DELTA
    else -> DiagramRelationshipInfo.NONE
}

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