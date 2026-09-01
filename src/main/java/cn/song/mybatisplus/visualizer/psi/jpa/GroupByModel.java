package cn.song.mybatisplus.visualizer.psi.jpa;

import org.jetbrains.annotations.NotNull;

/**
 * GROUP BY 中间表示。
 */
public record GroupByModel(
        /** 实体字段名（驼峰，如 "status"）。 */
        @NotNull String fieldName
) {
}
