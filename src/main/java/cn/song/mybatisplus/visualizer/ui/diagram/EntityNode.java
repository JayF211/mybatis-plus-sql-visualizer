package cn.song.mybatisplus.visualizer.ui.diagram;

import cn.song.mybatisplus.visualizer.model.JoinType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** 实体节点（对应数据库表）。 */
public final class EntityNode {
    @NotNull public final String alias;
    @NotNull public final String tableName;
    @NotNull public final List<ColumnEntry> columns;
    public final boolean isMain;
    @Nullable public final JoinType joinType;

    public EntityNode(@NotNull String alias, @NotNull String tableName,
                      @NotNull List<ColumnEntry> columns, boolean isMain,
                      @Nullable JoinType joinType) {
        this.alias = alias;
        this.tableName = tableName;
        this.columns = columns;
        this.isMain = isMain;
        this.joinType = joinType;
    }

    /** 主表便捷构造：joinType 默认 null。 */
    public EntityNode(@NotNull String alias, @NotNull String tableName,
                      @NotNull List<ColumnEntry> columns, boolean isMain) {
        this(alias, tableName, columns, isMain, null);
    }

    @NotNull public String getAlias() { return alias; }
    @NotNull public String getTableName() { return tableName; }
    @NotNull public List<ColumnEntry> getColumns() { return columns; }
    public boolean isMain() { return isMain; }
    @Nullable public JoinType getJoinType() { return joinType; }

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
