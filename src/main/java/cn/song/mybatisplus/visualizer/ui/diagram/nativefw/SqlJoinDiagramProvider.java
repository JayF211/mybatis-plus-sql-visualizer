package cn.song.mybatisplus.visualizer.ui.diagram.nativefw;

import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.ui.diagram.ColumnEntry;
import com.intellij.diagram.AbstractDiagramElementManager;
import com.intellij.diagram.DiagramBuilder;
import com.intellij.diagram.DiagramCategory;
import com.intellij.diagram.DiagramColorManager;
import com.intellij.diagram.DiagramColorManagerBase;
import com.intellij.diagram.DiagramDataModel;
import com.intellij.diagram.DiagramEdge;
import com.intellij.diagram.DiagramEdgeBase;
import com.intellij.diagram.DiagramNode;
import com.intellij.diagram.DiagramNodeBase;
import com.intellij.diagram.DiagramNodeContentManager;
import com.intellij.diagram.DiagramPresentationModel;
import com.intellij.diagram.DiagramProvider;
import com.intellij.diagram.DiagramRelationshipInfo;
import com.intellij.diagram.DiagramRelationshipInfoAdapter;
import com.intellij.diagram.DiagramRelationshipManager;
import com.intellij.diagram.DiagramVfsResolver;
import com.intellij.diagram.DiagramVisibilityManager;
import com.intellij.diagram.EmptyDiagramVisibilityManager;
import com.intellij.diagram.presentation.DiagramLineType;
import com.intellij.diagram.presentation.DiagramState;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.SimpleColoredText;
import com.intellij.ui.SimpleTextAttributes;

import javax.swing.Icon;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T-Dgm-01/03：JOIN 图的 IDEA 原生图框架 Provider。
 *
 * <p>接入模式与 DatabaseTools 的 DbDiagramProvider / QueryPlanProvider 一致：
 * 实现 {@link DiagramProvider} 并注册到 `com.intellij.diagram.Provider` EP
 * （声明在 diagram-optional.xml——仅 Ultimate 捆绑的 Diagrams 插件存在时加载）。
 *
 * <p>建图数据来自 {@link JoinDiagramSnapshotRegistry}：打开图前调用方先把 SQL 解析产物登记为快照，
 * 本 Provider 以快照 id（FQN）回查并展开为节点/边——Provider 自身不接触 PSI/SQL 模型。
 *
 * <p>T-NativeUI：渲染完全复用 Database 原生图体系——表头「表名 + 表注释（灰色斜体）」、
 * 字段行「PK 图标 + 名 + 类型 + 注释」、连接线按 JOIN 类型区分箭头/线型/ON 标签、
 * 工具栏保留分类切换（COLUMNS/KEY_COLUMNS/COMMENTS）。插件只控制「字段展示」和「连接线数据」。
 *
 * <p>隔离铁律：本文件（及 nativefw 包内引用 com.intellij.diagram 的文件）只在 Diagrams 插件存在时
 * 被 {@link NativeJoinDiagramSupport} 门面触发加载；Community 下走自绘 DiagramPanel。
 */
public class SqlJoinDiagramProvider extends DiagramProvider<JoinDiagramSnapshotRegistry.NativeEntity> {

    /** Provider id：同时是 UmlVirtualFile name 的第一段（`&lt;ID&gt;/&lt;fqn&gt;`，字节码实证分隔符 `/`）。 */
    public static final String ID = "SqlJoinDiagram";

    @Override
    public String getID() {
        return ID;
    }

    @Override
    public String getPresentableName() {
        return "SQL Visualizer JOIN Diagram";
    }

    @Override
    public DiagramDataModel<JoinDiagramSnapshotRegistry.NativeEntity> createDataModel(
            Project project,
            JoinDiagramSnapshotRegistry.NativeEntity element,
            VirtualFile file,
            DiagramPresentationModel presentationModel) {
        return new JoinDiagramDataModel(project, this, file);
    }

    @Override
    public DiagramVisibilityManager createVisibilityManager() {
        return EmptyDiagramVisibilityManager.INSTANCE;
    }

    @Override
    public JoinTableElementManager getElementManager() {
        return new JoinTableElementManager();
    }

    @Override
    public JoinEntityVfsResolver getVfsResolver() {
        return new JoinEntityVfsResolver();
    }

    @Override
    public DiagramRelationshipManager<JoinDiagramSnapshotRegistry.NativeEntity> getRelationshipManager() {
        return new JoinRelationshipManager();
    }

    /**
     * T-NativeUI-02：分类体系。直接实现 {@link DiagramNodeContentManager} 而非继承
     * AbstractDiagramNodeContentManager——后者内部启用集合初始为空、又受持久化（DiagramContentSettings）
     * 过滤，新图会把列全滤掉。直接实现 + 默认全启用，既让字段默认展示，又保留 toolbar 分类切换。
     */
    @Override
    public DiagramNodeContentManager createNodeContentManager() {
        return JoinDiagramNodeContentManager.INSTANCE;
    }

    @Override
    public DiagramColorManager getColorManager() {
        return new DiagramColorManagerBase();
    }

    /** 按 id 查找已注册的 Provider（Diagrams 插件加载本类后才可用；未注册返回 null）。 */
    public static DiagramProvider<JoinDiagramSnapshotRegistry.NativeEntity> registeredProvider() {
        return DiagramProvider.findByID(ID);
    }

    public static boolean isProviderRegistered() {
        return registeredProvider() != null;
    }
}

/**
 * T-NativeUI-02：节点分类管理器（对齐 Database 的 DbDiagramProvider$1）。
 *
 * 分类：
 *  - COLUMNS / KEY_COLUMNS：普通列 / 主键列——决定哪些字段行展示（toolbar 可独立显隐）
 *  - COMMENTS：字段注释——对齐 Database 工具栏里的 Comments 开关；字段注释内联渲染在列行里，
 *    不把注释单列为 item（与 Database「名+类型+注释」同一行一致），故 isInCategory 对其返回 false。
 *
 * 直接实现接口、默认全启用（enabledByDefault=true 且启用集合初值含全部三类），
 * 避免 Abstract 的持久化空状态把字段过滤掉（T-NativeUI-02 根因）。
 */
final class JoinDiagramNodeContentManager implements DiagramNodeContentManager {

    public static final JoinDiagramNodeContentManager INSTANCE = new JoinDiagramNodeContentManager();

    private JoinDiagramNodeContentManager() {
    }

    public static final DiagramCategory COLUMNS =
            new DiagramCategory(() -> "Columns", AllIcons.Nodes.DataColumn, true);
    public static final DiagramCategory KEY_COLUMNS =
            new DiagramCategory(() -> "Key columns", AllIcons.Nodes.FieldPK, true);
    public static final DiagramCategory COMMENTS =
            new DiagramCategory(() -> "Comments", AllIcons.Nodes.Tag, true);

    // 顺序即画布字段行分区顺序（框架按 enabled 分类逐组 append、组间插分隔符）：
    // 普通列在前、主键组收尾 = 原生 Database「显示图」的「PK 底部独立分区」效果。
    private static final DiagramCategory[] CATEGORIES = {COLUMNS, KEY_COLUMNS, COMMENTS};

    private static final Set<DiagramCategory> ENABLED =
            ConcurrentHashMap.newKeySet();

    static {
        for (DiagramCategory c : CATEGORIES) {
            ENABLED.add(c);
        }
    }

    @Override
    public DiagramCategory[] getContentCategories() {
        return CATEGORIES;
    }

    @Override
    public boolean isCategoryEnabled(DiagramCategory category) {
        return ENABLED.contains(category);
    }

    @Override
    public void setCategoryEnabled(DiagramCategory category, boolean enabled) {
        if (enabled) {
            ENABLED.add(category);
        } else {
            ENABLED.remove(category);
        }
    }

    @Override
    public DiagramCategory[] getEnabledCategories() {
        List<DiagramCategory> out = new ArrayList<>();
        for (DiagramCategory c : CATEGORIES) {
            if (ENABLED.contains(c)) {
                out.add(c);
            }
        }
        return out.toArray(new DiagramCategory[0]);
    }

    // 字段项可能出现在两个 Object 参数的任一位（不同平台版本框架传递顺序存在差异，字节码实证不稳）——
    // 两个槽位都查 ColumnEntry，取第一个命中项，消除对参数顺序的依赖。
    @Override
    public boolean isInCategory(Object identifyingElement, Object item, DiagramCategory category, DiagramBuilder builder) {
        ColumnEntry column = item instanceof ColumnEntry ? (ColumnEntry) item : null;
        if (column == null) {
            column = identifyingElement instanceof ColumnEntry ? (ColumnEntry) identifyingElement : null;
        }
        if (column == null) {
            return false;
        }
        return column.isPk() ? category == KEY_COLUMNS : category == COLUMNS;
    }
}

/**
 * T-NativeUI-01/03：表节点元素管理器：表头（表名 + 表注释）、字段 item（名 + 类型 + 注释）。
 * 渲染全交框架（NodeItemComponent 走 3 参 getItemName/getItemType，f92db8b 实证）。
 */
class JoinTableElementManager extends AbstractDiagramElementManager<JoinDiagramSnapshotRegistry.NativeEntity> {

    @Override
    public JoinDiagramSnapshotRegistry.NativeEntity findInDataContext(DataContext dataContext) {
        return null;
    }

    @Override
    public boolean isAcceptableAsNode(Object o) {
        return o instanceof JoinDiagramSnapshotRegistry.NativeEntity;
    }

    /** 节点内容行 = 表列（名称 + 类型 + 注释；渲染见 3 参 getItemName/getItemType）。 */
    @Override
    public Object[] getNodeItems(JoinDiagramSnapshotRegistry.NativeEntity e) {
        return e.getColumns().toArray();
    }

    @Override
    public boolean canCollapse(JoinDiagramSnapshotRegistry.NativeEntity e) {
        return false;
    }

    @Override
    public boolean isContainerFor(JoinDiagramSnapshotRegistry.NativeEntity container,
                                  JoinDiagramSnapshotRegistry.NativeEntity inner) {
        return false;
    }

    @Override
    public String getElementTitle(JoinDiagramSnapshotRegistry.NativeEntity e) {
        return e.getDisplayName();
    }

    // T-NativeUI-03/T-Embed-07：表头 = 表名(别名) + "/* 表注释 */"（斜体），对齐 Database 标题栏。
    @Override
    public SimpleColoredText getPresentableElementTitle(JoinDiagramSnapshotRegistry.NativeEntity e,
                                                        DiagramBuilder builder) {
        return JoinDiagramRenderTexts.joinTitleText(e.getDisplayName(), e.getTableComment());
    }

    @Override
    public SimpleColoredText getItemName(JoinDiagramSnapshotRegistry.NativeEntity identifyingElement,
                                         Object item, DiagramBuilder builder) {
        ColumnEntry column = item instanceof ColumnEntry ? (ColumnEntry) item : null;
        return JoinDiagramRenderTexts.joinItemNameText(column != null ? column.getName() : null,
                column != null && column.isPk(), column != null ? column.getComment() : null);
    }

    @Override
    public SimpleColoredText getItemType(JoinDiagramSnapshotRegistry.NativeEntity identifyingElement,
                                         Object item, DiagramBuilder builder) {
        ColumnEntry column = item instanceof ColumnEntry ? (ColumnEntry) item : null;
        return JoinDiagramRenderTexts.joinItemTypeText(column != null ? column.getType() : null);
    }

    @Override
    public Icon getItemIcon(Object item, DiagramState state) {
        if (!(item instanceof ColumnEntry)) {
            return null;
        }
        return ((ColumnEntry) item).isPk() ? AllIcons.Nodes.FieldPK : AllIcons.Nodes.DataColumn;
    }

    @Override
    public String getNodeTooltip(JoinDiagramSnapshotRegistry.NativeEntity e) {
        String base = !e.getAlias().equals(e.getTableName())
                ? e.getTableName() + " AS " + e.getAlias()
                : e.getTableName();
        String comment = e.getTableComment();
        return (comment != null && !comment.isBlank()) ? base + " — " + comment : base;
    }
}

// ---- 渲染文本构建（静态纯函数，无 builder/平台状态依赖，纯 JVM 可测） ----
// 框架 DiagramNodeItemComponent 调用 3 参重载（f92db8b 实证）；布局语义（T-Embed-07 对齐 Database）：
// 名字列（主键加粗）+ " /* 注释 */"（灰斜体）内联；类型列（灰）由框架右对齐独立展示。

/** 渲染文本构建 + 边样式解析（纯函数，无 builder/平台状态依赖，纯 JVM 可测）。 */
final class JoinDiagramRenderTexts {

    private JoinDiagramRenderTexts() {
    }

    static SimpleColoredText joinTitleText(String displayName, String tableComment) {
        SimpleColoredText text = new SimpleColoredText();
        text.append(displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
        if (tableComment != null && !tableComment.isBlank()) {
            text.append("  /* " + tableComment + " */", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
        }
        return text;
    }

    static SimpleColoredText joinItemNameText(String name, boolean isPk, String comment) {
        SimpleColoredText text = new SimpleColoredText();
        text.append(name != null ? name : "",
                isPk ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
        if (comment != null && !comment.isBlank()) {
            text.append("  /* " + comment + " */", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
        }
        return text;
    }

    static SimpleColoredText joinItemTypeText(String type) {
        SimpleColoredText text = new SimpleColoredText();
        if (type != null && !type.isBlank()) {
            text.append(type, SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
        return text;
    }

    static DiagramLineType lineTypeOf(JoinType joinType) {
        return joinType == JoinType.FULL ? DiagramLineType.DASHED : DiagramLineType.SOLID;
    }

    /** JOIN 类型 → 线宽：FULL 略粗以强化"全包含"语义，其余 1。 */
    static int widthOf(JoinType joinType) {
        return joinType == JoinType.FULL ? 2 : 1;
    }

    /** 起点箭头（主表侧）：RIGHT 指向主表，其余无箭头。 */
    static Shape startArrowOf(JoinType joinType) {
        if (joinType == JoinType.RIGHT) {
            return DiagramRelationshipInfo.DELTA;
        }
        if (joinType == JoinType.FULL) {
            return DiagramRelationshipInfo.WHITE_DELTA;
        }
        return DiagramRelationshipInfo.NONE;
    }

    /** 终点箭头（JOIN 表侧）：INNER/LEFT 指向 JOIN 表，FULL 双向白色三角。 */
    static Shape endArrowOf(JoinType joinType) {
        if (joinType == JoinType.INNER) {
            return DiagramRelationshipInfo.DELTA;
        }
        if (joinType == JoinType.LEFT) {
            return DiagramRelationshipInfo.DELTA_SMALL;
        }
        if (joinType == JoinType.FULL) {
            return DiagramRelationshipInfo.WHITE_DELTA;
        }
        return DiagramRelationshipInfo.NONE;
    }

    /** 快照内的边样式解析（RelationshipManager 与 JoinTableEdge 共用）。 */
    static DiagramRelationshipInfo relationshipInfo(String fromAlias, String toAlias) {
        JoinDiagramSnapshotRegistry.NativeEdge edge = JoinDiagramSnapshotRegistry.findEdge(fromAlias, toAlias);
        JoinType joinType = edge != null ? edge.getJoinType() : JoinType.INNER;
        return new DiagramRelationshipInfoAdapter.Builder()
                .setName(joinType.name())
                .setLineType(lineTypeOf(joinType))
                .setWidth(widthOf(joinType))
                .setSourceArrow(startArrowOf(joinType))
                .setTargetArrow(endArrowOf(joinType))
                .setUpperCenterLabel(edge != null ? edge.getLabel() : "")
                .create();
    }

}

/**
 * FQN 解析：FQN = 快照 id；解析回主表实体（框架用它作为 createDataModel 的种子元素）。
 */
class JoinEntityVfsResolver implements DiagramVfsResolver<JoinDiagramSnapshotRegistry.NativeEntity> {

    @Override
    public String getQualifiedName(JoinDiagramSnapshotRegistry.NativeEntity e) {
        return e != null ? e.getAlias() : null;
    }

    @Override
    public JoinDiagramSnapshotRegistry.NativeEntity resolveElementByFQN(String fqn, Project project) {
        JoinDiagramSnapshotRegistry.Snapshot snapshot = JoinDiagramSnapshotRegistry.get(fqn);
        if (snapshot == null) {
            return null;
        }
        for (JoinDiagramSnapshotRegistry.NativeEntity e : snapshot.getEntities()) {
            if (e.isMain()) {
                return e;
            }
        }
        return snapshot.getEntities().isEmpty() ? null : snapshot.getEntities().get(0);
    }
}

/**
 * 关系边样式：JOIN 类型 → 箭头/线型/宽度，ON 条件 → 线上标签。
 * 框架在建边/重建时回调，按「最近一个同时包含两端别名的快照」解析边样式。
 */
class JoinRelationshipManager implements DiagramRelationshipManager<JoinDiagramSnapshotRegistry.NativeEntity> {

    @Override
    public DiagramRelationshipInfo getDependencyInfo(JoinDiagramSnapshotRegistry.NativeEntity from,
                                                     JoinDiagramSnapshotRegistry.NativeEntity to,
                                                     DiagramCategory category) {
        return JoinDiagramRenderTexts.relationshipInfo(from.getAlias(), to.getAlias());
    }
}


/**
 * 建图数据模型：快照 → 节点/边。
 * 快照缺失（如进程内注册表已被清理）时返回空图。
 */
class JoinDiagramDataModel extends DiagramDataModel<JoinDiagramSnapshotRegistry.NativeEntity> {

    /** UmlVirtualFile name = "&lt;providerId&gt;/&lt;snapshotId&gt;"，取 `/` 之后为快照 id。 */
    private final String snapshotId;

    private final JoinDiagramGraph graph;

    JoinDiagramDataModel(Project project, SqlJoinDiagramProvider provider, VirtualFile file) {
        super(project, provider);
        String id = null;
        if (file != null) {
            String name = file.getName();
            int idx = name.indexOf('/');
            id = idx >= 0 ? name.substring(idx + 1) : name;
        }
        this.snapshotId = id;
        this.graph = JoinDiagramGraph.build(
                snapshotId != null ? JoinDiagramSnapshotRegistry.get(snapshotId) : null, provider);
    }

    @Override
    public Collection<DiagramNode<JoinDiagramSnapshotRegistry.NativeEntity>> getNodes() {
        return graph.getNodes();
    }

    @Override
    public Collection<DiagramEdge<JoinDiagramSnapshotRegistry.NativeEntity>> getEdges() {
        return graph.getEdges();
    }

    @Override
    public String getNodeName(DiagramNode<JoinDiagramSnapshotRegistry.NativeEntity> node) {
        JoinDiagramSnapshotRegistry.NativeEntity e = node.getIdentifyingElement();
        return e != null ? e.getDisplayName() : "";
    }

    @Override
    public DiagramNode<JoinDiagramSnapshotRegistry.NativeEntity> addElement(
            JoinDiagramSnapshotRegistry.NativeEntity element) {
        return null;
    }

    @Override
    public ModificationTracker getModificationTracker() {
        return ModificationTracker.NEVER_CHANGED;
    }

    @Override
    public void dispose() {
        // 快照归注册表所有（evictBeyond 控制上限），DataModel 无额外资源
    }
}

/**
 * 节点/边构建结果（JoinDiagramGraph.build 纯函数，便于无 Project 的纯 JVM 单测）。
 */
final class JoinDiagramGraph {

    private final List<DiagramNode<JoinDiagramSnapshotRegistry.NativeEntity>> nodes;
    private final List<DiagramEdge<JoinDiagramSnapshotRegistry.NativeEntity>> edges;

    JoinDiagramGraph(List<DiagramNode<JoinDiagramSnapshotRegistry.NativeEntity>> nodes,
                     List<DiagramEdge<JoinDiagramSnapshotRegistry.NativeEntity>> edges) {
        this.nodes = nodes;
        this.edges = edges;
    }

    List<DiagramNode<JoinDiagramSnapshotRegistry.NativeEntity>> getNodes() {
        return nodes;
    }

    List<DiagramEdge<JoinDiagramSnapshotRegistry.NativeEntity>> getEdges() {
        return edges;
    }

    static JoinDiagramGraph build(JoinDiagramSnapshotRegistry.Snapshot snapshot, SqlJoinDiagramProvider provider) {
        if (snapshot == null) {
            return new JoinDiagramGraph(List.of(), List.of());
        }
        List<DiagramNode<JoinDiagramSnapshotRegistry.NativeEntity>> nodes = new ArrayList<>();
        Map<String, JoinTableNode> byAlias = new LinkedHashMap<>();
        for (JoinDiagramSnapshotRegistry.NativeEntity e : snapshot.getEntities()) {
            JoinTableNode node = new JoinTableNode(e, provider);
            nodes.add(node);
            byAlias.put(node.getIdentifyingElement().getAlias(), node);
        }
        List<DiagramEdge<JoinDiagramSnapshotRegistry.NativeEntity>> edges = new ArrayList<>();
        for (JoinDiagramSnapshotRegistry.NativeEdge edge : snapshot.getEdges()) {
            JoinTableNode from = byAlias.get(edge.getFromAlias());
            JoinTableNode to = byAlias.get(edge.getToAlias());
            if (from == null || to == null) {
                continue;
            }
            edges.add(new JoinTableEdge(from, to));
        }
        return new JoinDiagramGraph(nodes, edges);
    }
}

/** 表节点。 */
class JoinTableNode extends DiagramNodeBase<JoinDiagramSnapshotRegistry.NativeEntity> {

    private final JoinDiagramSnapshotRegistry.NativeEntity entity;

    JoinTableNode(JoinDiagramSnapshotRegistry.NativeEntity entity, SqlJoinDiagramProvider provider) {
        super(provider);
        this.entity = entity;
    }

    @Override
    public JoinDiagramSnapshotRegistry.NativeEntity getIdentifyingElement() {
        return entity;
    }

    @Override
    public Icon getIcon() {
        return AllIcons.Nodes.DataTables;
    }

    @Override
    public String getTooltip() {
        return !entity.getAlias().equals(entity.getTableName())
                ? entity.getTableName() + " AS " + entity.getAlias()
                : entity.getTableName();
    }
}

/** 关系边（样式由 JoinDiagramEdgeStyles.relationshipInfo 按 JOIN 类型给出）。 */
class JoinTableEdge extends DiagramEdgeBase<JoinDiagramSnapshotRegistry.NativeEntity> {

    JoinTableEdge(JoinTableNode source, JoinTableNode target) {
        super(source, target,
                JoinDiagramRenderTexts.relationshipInfo(
                        source.getIdentifyingElement().getAlias(),
                        target.getIdentifyingElement().getAlias()));
    }
}
