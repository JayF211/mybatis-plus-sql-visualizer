package cn.song.mybatisplus.visualizer.ui.diagram

import cn.song.mybatisplus.visualizer.model.JoinType

/**
 * F-xx：JOIN 关系图结构化中间模型。
 *
 * 替代 PlantUML 文本作为 [cn.song.mybatisplus.visualizer.sql.JoinDiagramGenerator] 的输出
 * 与 [DiagramRenderer] 的输入之间的桥梁。纯数据类，零外部依赖。
 *
 * @param entities      实体节点列表（主表 + JOIN 表）
 * @param relationships 关系列表（主表 → JOIN 表的连接）
 */
data class DiagramModel(
    val entities: List<EntityNode>,
    val relationships: List<RelationshipLine>,
) {
    /** 无实体时返回 true（调用方可据此决定是否展示标签页）。 */
    val isEmpty: Boolean get() = entities.isEmpty()
}

/**
 * 实体节点（对应数据库表）。
 *
 * @param alias     表别名（已 sanitize，用于标识）
 * @param tableName 表名（用于显示）
 * @param columns   列列表（已裁剪：总数 >50 时仅保留 PK）
 * @param isMain    是否主表（控制颜色：true = 绿色，false = 蓝色）
 * @param joinType  JOIN 类型（仅 JOIN 表有效，用于卡片标题标注）
 */
data class EntityNode(
    val alias: String,
    val tableName: String,
    val columns: List<ColumnEntry>,
    val isMain: Boolean,
    val joinType: JoinType? = null,
) {
    /** 显示名：别名与表名不同时显示 "tableName (alias)"。 */
    val displayName: String
        get() = if (alias != tableName) "$tableName ($alias)" else tableName
}

/**
 * 列信息。
 *
 * @param name  列名
 * @param type  字段类型字符串（可为 null）
 * @param isPk     是否主键
 * @param comment  字段注释（DB COMMENT，对齐 IDEA Database 原生图显示）
 */
data class ColumnEntry(
    val name: String,
    val type: String?,
    val isPk: Boolean,
    val comment: String? = null,
)

/**
 * 关系线（主表 → JOIN 表的连接）。
 *
 * @param fromAlias 起始实体 alias（始终为主表）
 * @param toAlias   目标实体 alias（JOIN 表）
 * @param joinType  JOIN 类型（决定箭头样式）
 * @param label     关系线标签（ON 条件文本）
 */
data class RelationshipLine(
    val fromAlias: String,
    val toAlias: String,
    val joinType: JoinType,
    val label: String,
)
