package cn.song.mybatisplus.visualizer.converter.jpa;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 嵌套属性路径解析产物。
 *
 * @param associationPath 关联路径段（如 "address"），null 表示非嵌套属性
 * @param remainingPath   剩余属性路径（如 "City"），非嵌套时等于原始 propertyPath
 */
public final class NestedPropertyPath {
    private final @Nullable String associationPath;
    private final @NotNull String remainingPath;

    public NestedPropertyPath(@Nullable String associationPath, @NotNull String remainingPath) {
        this.associationPath = associationPath;
        this.remainingPath = remainingPath;
    }

    public @Nullable String getAssociationPath() { return associationPath; }
    public @Nullable String associationPath() { return associationPath; }
    public @NotNull String getRemainingPath() { return remainingPath; }
    public @NotNull String remainingPath() { return remainingPath; }

/** 是否为嵌套属性（关联路径段非 null）。 */
    public boolean isNested() {
        return associationPath != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NestedPropertyPath)) return false;
        NestedPropertyPath that = (NestedPropertyPath) o;
        return java.util.Objects.equals(this.associationPath, that.associationPath) && java.util.Objects.equals(this.remainingPath, that.remainingPath);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(associationPath, remainingPath);
    }

    @Override
    public String toString() {
        return "NestedPropertyPath[" + "associationPath=" + associationPath + ", " + "remainingPath=" + remainingPath + "]";
    }
}
