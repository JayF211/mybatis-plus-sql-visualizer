package cn.song.mybatisplus.visualizer.converter.jpa;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Predicate 段中的单个 OrPart。
 *
 * 多个 PredicatePart 之间为 OR 连接，内部 conditions 之间为 AND 连接。
 * [connector] 表示该 Part 与前一个 Part 的连接符（第一个恒为 AND）。
 *
 * @param connector  与前一个 OrPart 的连接符（第一个恒为 AND）
 * @param conditions 该 OrPart 内的 AND 条件列表
 */
public final class PredicatePart {
    private final @NotNull Connector connector;
    private final @NotNull List<Condition> conditions;

    public PredicatePart(@NotNull Connector connector, @NotNull List<Condition> conditions) {
        this.connector = connector;
        this.conditions = conditions;
    }

    public @NotNull Connector getConnector() { return connector; }
    public @NotNull Connector connector() { return connector; }
    public @NotNull List<Condition> getConditions() { return conditions; }
    public @NotNull List<Condition> conditions() { return conditions; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PredicatePart)) return false;
        PredicatePart that = (PredicatePart) o;
        return java.util.Objects.equals(this.connector, that.connector) && java.util.Objects.equals(this.conditions, that.conditions);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(connector, conditions);
    }

    @Override
    public String toString() {
        return "PredicatePart[" + "connector=" + connector + ", " + "conditions=" + conditions + "]";
    }
}
