package cn.song.mybatisplus.visualizer.ui.diagram.nativefw;

import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.ui.diagram.ColumnEntry;
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel;
import kotlin.Pair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * T-Dgm-02：JOIN 图快照注册表。
 *
 * <p>原生图框架经 {@link SqlJoinDiagramProvider}（注册于 diagram-optional.xml，仅 Diagrams 插件存在时加载）
 * 建图时只能拿到「元素标识（FQN）」，拿不到完整的 SQL 解析产物——因此打开图之前先把
 * JoinDiagramGenerator 输出的 {@link DiagramModel} 适配为 {@link Snapshot} 存入本表，快照 id 即图的 FQN，
 * Provider/DataModel 侧凭 id 回查。
 *
 * <p>本文件不 import com.intellij.diagram / com.intellij.uml（Community 无 Diagrams 插件时可安全加载），
 * 隔离边界与「图数据」保持一致：纯数据 + 纯适配，零平台依赖。
 */
public final class JoinDiagramSnapshotRegistry {

    public static final JoinDiagramSnapshotRegistry INSTANCE = new JoinDiagramSnapshotRegistry();

    private JoinDiagramSnapshotRegistry() {
    }

    /** 图节点实体（原生框架侧的表节点）。 */
    public static final class NativeEntity {
        private final String alias;
        private final String tableName;
        private final List<ColumnEntry> columns;
        private final boolean isMain;
        private final JoinType joinType;
        private final String tableComment;

        public NativeEntity(String alias, String tableName, List<ColumnEntry> columns,
                            boolean isMain, JoinType joinType, String tableComment) {
            this.alias = alias;
            this.tableName = tableName;
            this.columns = columns;
            this.isMain = isMain;
            this.joinType = joinType;
            this.tableComment = tableComment;
        }

        public String getAlias() {
            return alias;
        }

        public String getTableName() {
            return tableName;
        }

        public List<ColumnEntry> getColumns() {
            return columns;
        }

        public boolean isMain() {
            return isMain;
        }

        public JoinType getJoinType() {
            return joinType;
        }

        public String getTableComment() {
            return tableComment;
        }

        /** 显示名：别名与表名不同时显示 "tableName (alias)"，与自绘版语义一致。 */
        public String getDisplayName() {
            return !alias.equals(tableName) ? tableName + " (" + alias + ")" : tableName;
        }
    }

    /** 图关系边（主表 → JOIN 表的连接）。 */
    public static final class NativeEdge {
        private final String fromAlias;
        private final String toAlias;
        private final JoinType joinType;
        private final String label;

        public NativeEdge(String fromAlias, String toAlias, JoinType joinType, String label) {
            this.fromAlias = fromAlias;
            this.toAlias = toAlias;
            this.joinType = joinType;
            this.label = label;
        }

        public String getFromAlias() {
            return fromAlias;
        }

        public String getToAlias() {
            return toAlias;
        }

        public JoinType getJoinType() {
            return joinType;
        }

        public String getLabel() {
            return label;
        }
    }

    /** 一次 JOIN 图快照（一帧图 = 一张编辑器/标签页）。 */
    public static final class Snapshot {
        private final String id;
        private final String title;
        private final List<NativeEntity> entities;
        private final List<NativeEdge> edges;

        public Snapshot(String id, String title, List<NativeEntity> entities, List<NativeEdge> edges) {
            this.id = id;
            this.title = title;
            this.entities = entities;
            this.edges = edges;
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public List<NativeEntity> getEntities() {
            return entities;
        }

        public List<NativeEdge> getEdges() {
            return edges;
        }
    }

    private static final Map<String, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    /** 快照计数（id 生成用，进程内唯一即可）。 */
    private static final AtomicLong COUNTER = new AtomicLong(0);

    private static long seqOf(String key) {
        String suffix = key.replaceFirst("^snapshot", "");
        try {
            return Long.parseLong(suffix);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 将自绘版模型适配为快照并登记。
     *
     * @param model JoinDiagramGenerator 输出
     * @param title 图标签页标题（编辑器 tab / 独立标签页 presentable name）
     * @return 快照 id（即原生图框架侧的 FQN，格式保证无 `.`——避免 UmlEditorProvider 误判为 .uml 文件格式）
     */
    public static String put(DiagramModel model, String title) {
        String id = "snapshot" + COUNTER.incrementAndGet();
        List<NativeEntity> entities = new ArrayList<>();
        for (cn.song.mybatisplus.visualizer.ui.diagram.EntityNode e : model.getEntities()) {
            entities.add(new NativeEntity(e.getAlias(), e.getTableName(), e.getColumns(),
                    e.isMain(), e.getJoinType(), e.getTableComment()));
        }
        List<NativeEdge> edges = new ArrayList<>();
        for (cn.song.mybatisplus.visualizer.ui.diagram.RelationshipLine r : model.getRelationships()) {
            edges.add(new NativeEdge(r.getFromAlias(), r.getToAlias(), r.getJoinType(), r.getLabel()));
        }
        SNAPSHOTS.put(id, new Snapshot(id, title, entities, edges));
        return id;
    }

    /** 按快照 id（FQN）回查；不存在返回 null（调用方显示空图）。 */
    public static Snapshot get(String id) {
        return SNAPSHOTS.get(id);
    }

    /**
     * 按两端别名查找边（关系样式解析用）。
     * RelationshipManager 回调时拿不到快照上下文，按「最近登记的快照优先」扫描。
     */
    public static NativeEdge findEdge(String fromAlias, String toAlias) {
        List<String> ids = new ArrayList<>(SNAPSHOTS.keySet());
        ids.sort(Comparator.comparingLong((String it) -> {
            String suffix = it.replaceFirst("^snapshot", "");
            try {
                return Long.parseLong(suffix);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }).reversed());
        for (String id : ids) {
            Snapshot snapshot = SNAPSHOTS.get(id);
            if (snapshot == null) {
                continue;
            }
            for (NativeEdge edge : snapshot.getEdges()) {
                if (edge.getFromAlias().equals(fromAlias) && edge.getToAlias().equals(toAlias)) {
                    return edge;
                }
            }
        }
        return null;
    }

    /** 快照上限：防止长时间驻留的预览面板无限累积（LRU 语义：超限清除最早的若干个）。 */
    public static void evictBeyond(int limit) {
        if (SNAPSHOTS.size() <= limit) {
            return;
        }
        List<String> keys = new ArrayList<>(SNAPSHOTS.keySet());
        keys.sort(Comparator.comparingLong(JoinDiagramSnapshotRegistry::seqOf));
        int removeCount = SNAPSHOTS.size() - limit;
        for (String key : keys.subList(0, removeCount)) {
            SNAPSHOTS.remove(key);
        }
    }

    /** 仅供测试：清空全部快照。 */
    public static void clearForTest() {
        SNAPSHOTS.clear();
    }
}
