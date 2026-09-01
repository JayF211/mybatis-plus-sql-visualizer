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
public record Condition(
        @NotNull String propertyPath,
        @NotNull PartType type,
        boolean ignoreCase,
        @Nullable NestedPropertyPath nestedPath
) {
}
