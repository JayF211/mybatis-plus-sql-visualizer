package cn.song.mybatisplus.visualizer.converter.jpa;

import org.jetbrains.annotations.NotNull;

/**
 * OrderBy 排序项。
 *
 * @param propertyPath 排序属性路径
 * @param direction    排序方向（ASC / DESC）
 */
public record OrderByPart(
        @NotNull String propertyPath,
        @NotNull SortDirection direction
) {
}
