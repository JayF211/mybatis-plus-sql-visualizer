package cn.song.mybatisplus.visualizer.psi.jpa;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * SELECT 列中间表示。
 */
public record SelectColumn(
        /** 实体字段名（驼峰，如 shareSchemeId）；聚合函数时为 null。 */
        @Nullable String fieldName,
        /** 列别名（当前未使用，预留）。 */
        @Nullable String alias,
        /** 聚合函数信息；null 表示非聚合列。 */
        @Nullable AggregateInfo aggregate
) {
}
