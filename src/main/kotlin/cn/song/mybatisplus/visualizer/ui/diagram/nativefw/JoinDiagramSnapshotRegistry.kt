package cn.song.mybatisplus.visualizer.ui.diagram.nativefw

import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel

/**
 * T-Dgm-02：JOIN 图快照注册表。
 *
 * 原生图框架经 [SqlJoinDiagramProvider]（注册于 diagram-optional.xml，仅 Diagrams 插件存在时加载）
 * 建图时只能拿到「元素标识（FQN）」，拿不到完整的 SQL 解析产物——因此打开图之前先把
 * [JoinDiagramGenerator] 输出的 [DiagramModel] 适配为 [Snapshot] 存入本表，快照 id 即图的 FQN，
 * Provider/DataModel 侧凭 id 回查。
 *
 * 本文件不 import com.intellij.diagram / com.intellij.uml（Community 无 Diagrams 插件时可安全加载），
 * 隔离边界与「图数据」保持一致：纯数据 + 纯适配，零平台依赖。
 */
object JoinDiagramSnapshotRegistry {

    /** 图节点实体（原生框架侧的表节点）。 */
    data class NativeEntity(
        val alias: String,
        val tableName: String,
        val columns: List<cn.song.mybatisplus.visualizer.ui.diagram.ColumnEntry>,
        val isMain: Boolean,
        val joinType: JoinType?,
        val tableComment: String? = null,
    ) {
        /** 显示名：别名与表名不同时显示 "tableName (alias)"，与自绘版语义一致。 */
        val displayName: String
            get() = if (alias != tableName) "$tableName ($alias)" else tableName
    }

    /** 图关系边（主表 → JOIN 表的连接）。 */
    data class NativeEdge(
        val fromAlias: String,
        val toAlias: String,
        val joinType: JoinType,
        val label: String,
    )

    /** 一次 JOIN 图快照（一帧图 = 一张编辑器/标签页）。 */
    data class Snapshot(
        val id: String,
        val title: String,
        val entities: List<NativeEntity>,
        val edges: List<NativeEdge>,
    )

    private val snapshots = java.util.concurrent.ConcurrentHashMap<String, Snapshot>()

    /** 快照计数（id 生成用，进程内唯一即可）。 */
    private val counter = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 将自绘版模型适配为快照并登记。
     *
     * @param model [JoinDiagramGenerator] 输出
     * @param title 图标签页标题（编辑器 tab / 独立标签页 presentable name）
     * @return 快照 id（即原生图框架侧的 FQN，格式保证无 `.`——避免 UmlEditorProvider 误判为 .uml 文件格式）
     */
    fun put(model: DiagramModel, title: String): String {
        val id = "snapshot${counter.incrementAndGet()}"
        val entities = model.entities.map { e ->
            NativeEntity(
                alias = e.alias,
                tableName = e.tableName,
                columns = e.columns,
                isMain = e.isMain,
                joinType = e.joinType,
                tableComment = e.tableComment,
            )
        }
        val edges = model.relationships.map { r ->
            NativeEdge(fromAlias = r.fromAlias, toAlias = r.toAlias, joinType = r.joinType, label = r.label)
        }
        snapshots[id] = Snapshot(id = id, title = title, entities = entities, edges = edges)
        return id
    }

    /** 按快照 id（FQN）回查；不存在返回 null（调用方显示空图）。 */
    fun get(id: String): Snapshot? = snapshots[id]

    /**
     * 按两端别名查找边（关系样式解析用）。
     * RelationshipManager 回调时拿不到快照上下文，按「最近登记的快照优先」扫描。
     */
    fun findEdge(fromAlias: String, toAlias: String): NativeEdge? {
        for (id in snapshots.keys.sortedByDescending { it.removePrefix("snapshot").toLongOrNull() ?: 0L }) {
            val edge = snapshots[id]?.edges?.firstOrNull { it.fromAlias == fromAlias && it.toAlias == toAlias }
            if (edge != null) return edge
        }
        return null
    }

    /** 快照上限：防止长时间驻留的预览面板无限累积（LRU 语义：超限清除最早的若干个）。 */
    fun evictBeyond(limit: Int) {
        if (snapshots.size <= limit) return
        snapshots.keys.sortedBy { key -> key.removePrefix("snapshot").toLongOrNull() ?: 0L }
            .take(snapshots.size - limit)
            .forEach { snapshots.remove(it) }
    }

    /** 仅供测试：清空全部快照。 */
    fun clearForTest() = snapshots.clear()
}
