package cn.song.mybatisplus.visualizer.model;

import java.util.Objects;

/**
 * Hibernate 软删除（T-J8 @SoftDelete，6.4+）。
 *
 * @param columnName 软删除列名（注解未指定时为 Hibernate 默认 "deleted"）
 * @param strategy   策略文本（DELETED / ACTIVE / VERSION，注解未指定时为默认 "DELETED"）
 */
public final class SoftDeleteInfo {
    private final String columnName;
    private final String strategy;

    public SoftDeleteInfo(String columnName, String strategy) {
        this.columnName = columnName;
        this.strategy = strategy;
    }

    public String getColumnName() {
        return columnName;
    }

    public String getStrategy() {
        return strategy;
    }

    public SoftDeleteInfo copy(String columnName, String strategy) {
        return new SoftDeleteInfo(columnName, strategy);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SoftDeleteInfo)) {
            return false;
        }
        SoftDeleteInfo that = (SoftDeleteInfo) o;
        return Objects.equals(columnName, that.columnName)
                && Objects.equals(strategy, that.strategy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(columnName, strategy);
    }

    @Override
    public String toString() {
        return "SoftDeleteInfo(columnName=" + columnName + ", strategy=" + strategy + ")";
    }
}
