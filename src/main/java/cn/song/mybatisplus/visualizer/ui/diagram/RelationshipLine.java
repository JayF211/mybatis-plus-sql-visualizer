package cn.song.mybatisplus.visualizer.ui.diagram;

import cn.song.mybatisplus.visualizer.model.JoinType;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** 关系线（主表 → JOIN 表的连接）。 */
public final class RelationshipLine {
    @NotNull public final String fromAlias;
    @NotNull public final String toAlias;
    @NotNull public final JoinType joinType;
    @NotNull public final String label;

    public RelationshipLine(@NotNull String fromAlias, @NotNull String toAlias,
                            @NotNull JoinType joinType, @NotNull String label) {
        this.fromAlias = fromAlias;
        this.toAlias = toAlias;
        this.joinType = joinType;
        this.label = label;
    }

    @NotNull public String getFromAlias() { return fromAlias; }
    @NotNull public String getToAlias() { return toAlias; }
    @NotNull public JoinType getJoinType() { return joinType; }
    @NotNull public String getLabel() { return label; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RelationshipLine)) return false;
        RelationshipLine that = (RelationshipLine) o;
        return fromAlias.equals(that.fromAlias) && toAlias.equals(that.toAlias)
                && joinType == that.joinType && label.equals(that.label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromAlias, toAlias, joinType, label);
    }

    @Override
    public String toString() {
        return "RelationshipLine{fromAlias='" + fromAlias + "', toAlias='" + toAlias
                + "', joinType=" + joinType + ", label='" + label + "'}";
    }
}
