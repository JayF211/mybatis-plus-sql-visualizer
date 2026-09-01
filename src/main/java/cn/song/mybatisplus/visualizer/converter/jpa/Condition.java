package cn.song.mybatisplus.visualizer.converter.jpa;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 单个条件。
 *
 * @param propertyPath 属性路径（如 "lastName" 或 "address.city"）
 * @param type         条件类型（27 种）
 * @param ignoreCase   是否忽略大小写
 * @param nestedPath   嵌套路径解析结果（null 表示未尝试拆分）
 */
public final class Condition {
    private final @NotNull String propertyPath;
    private final @NotNull PartType type;
    private final boolean ignoreCase;
    private final @Nullable NestedPropertyPath nestedPath;

    public Condition(@NotNull String propertyPath, @NotNull PartType type, boolean ignoreCase, @Nullable NestedPropertyPath nestedPath) {
        this.propertyPath = propertyPath;
        this.type = type;
        this.ignoreCase = ignoreCase;
        this.nestedPath = nestedPath;
    }

    public @NotNull String getPropertyPath() { return propertyPath; }
    public @NotNull String propertyPath() { return propertyPath; }
    public @NotNull PartType getType() { return type; }
    public @NotNull PartType type() { return type; }
    public boolean isIgnoreCase() { return ignoreCase; }
    public boolean ignoreCase() { return ignoreCase; }
    public @Nullable NestedPropertyPath getNestedPath() { return nestedPath; }
    public @Nullable NestedPropertyPath nestedPath() { return nestedPath; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Condition)) return false;
        Condition that = (Condition) o;
        return java.util.Objects.equals(this.propertyPath, that.propertyPath) && java.util.Objects.equals(this.type, that.type) && java.util.Objects.equals(this.ignoreCase, that.ignoreCase) && java.util.Objects.equals(this.nestedPath, that.nestedPath);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(propertyPath, type, ignoreCase, nestedPath);
    }

    @Override
    public String toString() {
        return "Condition[" + "propertyPath=" + propertyPath + ", " + "type=" + type + ", " + "ignoreCase=" + ignoreCase + ", " + "nestedPath=" + nestedPath + "]";
    }
}
