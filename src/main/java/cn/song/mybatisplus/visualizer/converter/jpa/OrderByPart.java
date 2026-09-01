package cn.song.mybatisplus.visualizer.converter.jpa;

import org.jetbrains.annotations.NotNull;

/**
 * OrderBy 排序项。
 *
 * @param propertyPath 排序属性路径
 * @param direction    排序方向（ASC / DESC）
 */
public final class OrderByPart {
    private final @NotNull String propertyPath;
    private final @NotNull SortDirection direction;

    public OrderByPart(@NotNull String propertyPath, @NotNull SortDirection direction) {
        this.propertyPath = propertyPath;
        this.direction = direction;
    }

    public @NotNull String getPropertyPath() { return propertyPath; }
    public @NotNull String propertyPath() { return propertyPath; }
    public @NotNull SortDirection getDirection() { return direction; }
    public @NotNull SortDirection direction() { return direction; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrderByPart)) return false;
        OrderByPart that = (OrderByPart) o;
        return java.util.Objects.equals(this.propertyPath, that.propertyPath) && java.util.Objects.equals(this.direction, that.direction);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(propertyPath, direction);
    }

    @Override
    public String toString() {
        return "OrderByPart[" + "propertyPath=" + propertyPath + ", " + "direction=" + direction + "]";
    }
}
