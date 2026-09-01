package cn.song.mybatisplus.visualizer.psi.jpa;

import org.jetbrains.annotations.NotNull;

/**
 * ORDER BY 中间表示。
 */
public final class OrderByModel {
    /** 实体字段名。 */
    private final @NotNull String fieldName;
    /** true=ASC / false=DESC。 */
    private final boolean asc;

    public OrderByModel(@NotNull String fieldName, boolean asc) {
        this.fieldName = fieldName;
        this.asc = asc;
    }

    public @NotNull String getFieldName() { return fieldName; }
    public @NotNull String fieldName() { return fieldName; }
    public boolean isAsc() { return asc; }
    public boolean asc() { return asc; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrderByModel)) return false;
        OrderByModel that = (OrderByModel) o;
        return java.util.Objects.equals(this.fieldName, that.fieldName) && java.util.Objects.equals(this.asc, that.asc);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fieldName, asc);
    }

    @Override
    public String toString() {
        return "OrderByModel[" + "fieldName=" + fieldName + ", " + "asc=" + asc + "]";
    }
}
