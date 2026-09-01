package cn.song.mybatisplus.visualizer.psi.jpa;

import org.jetbrains.annotations.NotNull;

/**
 * GROUP BY 中间表示。
 */
public final class GroupByModel {
    /** 实体字段名（驼峰，如 "status"）。 */
    private final @NotNull String fieldName;

    public GroupByModel(@NotNull String fieldName) {
        this.fieldName = fieldName;
    }

    public @NotNull String getFieldName() { return fieldName; }
    public @NotNull String fieldName() { return fieldName; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GroupByModel)) return false;
        GroupByModel that = (GroupByModel) o;
        return java.util.Objects.equals(this.fieldName, that.fieldName);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fieldName);
    }

    @Override
    public String toString() {
        return "GroupByModel[" + "fieldName=" + fieldName + "]";
    }
}
