package cn.song.mybatisplus.visualizer.converter.jpa;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 嵌套属性路径解析产物。
 *
 * @param associationPath 关联路径段（如 "address"），null 表示非嵌套属性
 * @param remainingPath   剩余属性路径（如 "City"），非嵌套时等于原始 propertyPath
 */
public record NestedPropertyPath(
        @Nullable String associationPath,
        @NotNull String remainingPath
) {
    /** 是否为嵌套属性（关联路径段非 null）。 */
    public boolean isNested() {
        return associationPath != null;
    }
}
