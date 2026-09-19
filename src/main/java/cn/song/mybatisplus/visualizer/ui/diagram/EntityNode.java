package cn.song.mybatisplus.visualizer.ui.diagram;

import cn.song.mybatisplus.visualizer.model.JoinType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 实体节点（对应数据库表）。
 *
 * @param alias     表别名（已 sanitize，用于标识）
 * @param tableName 表名（用于显示）
 * @param columns   列列表（已裁剪 + 已富化：总数 &gt;{@link DiagramModelKt#DIAGRAM_MAX_COLUMNS} 时仅保留 PK）
 * @param isMain    是否主表（控制颜色：true = 绿色，false = 蓝色）
 * @param joinType  JOIN 类型（仅 JOIN 表有效，用于卡片标题标注）
 */
public final class EntityNode {
    @NotNull public final String alias;
    @NotNull public final String tableName;
    @NotNull public final List<ColumnEntry> columns;
    public final boolean isMain;
    @Nullable public final JoinType joinType;
    /** 表注释（T-Embed：数据库 COMMENT 优先，回落实体注释；null = 无）。 */
    @Nullable public final String tableComment;

    public EntityNode(@NotNull String alias, @NotNull String tableName,
                      @NotNull List<ColumnEntry> columns, boolean isMain,
                      @Nullable JoinType joinType, @Nullable String tableComment) {
        this.alias = alias;
        this.tableName = tableName;
        this.columns = columns;
        this.isMain = isMain;
        this.joinType = joinType;
        this.tableComment = tableComment;
    }

    /** 旧形态：无表注释。 */
    public EntityNode(@NotNull String alias, @NotNull String tableName,
                      @NotNull List<ColumnEntry> columns, boolean isMain,
                      @Nullable JoinType joinType) {
        this(alias, tableName, columns, isMain, joinType, null);
    }

    /** 主表便捷构造：joinType 默认 null。 */
    public EntityNode(@NotNull String alias, @NotNull String tableName,
                      @NotNull List<ColumnEntry> columns, boolean isMain) {
        this(alias, tableName, columns, isMain, null, null);
    }

    @NotNull public String getAlias() { return alias; }
    @NotNull public String getTableName() { return tableName; }
    @NotNull public List<ColumnEntry> getColumns() { return columns; }
    public boolean isMain() { return isMain; }
    @Nullable public JoinType getJoinType() { return joinType; }
    @Nullable public String getTableComment() { return tableComment; }

    /** 显示名：别名与表名不同时显示 "tableName (alias)"。 */
    @NotNull
    public String getDisplayName() {
        return !alias.equals(tableName) ? tableName + " (" + alias + ")" : tableName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EntityNode)) return false;
        EntityNode that = (EntityNode) o;
        return isMain == that.isMain && alias.equals(that.alias)
                && tableName.equals(that.tableName) && columns.equals(that.columns)
                && joinType == that.joinType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(alias, tableName, columns, isMain, joinType);
    }

    @Override
    public String toString() {
        return "EntityNode{alias='" + alias + "', tableName='" + tableName + "', columns=" + columns
                + ", isMain=" + isMain + ", joinType=" + joinType + '}';
    }
}
