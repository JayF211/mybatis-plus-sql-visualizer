package cn.song.mybatisplus.visualizer.psi.jpa;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 聚合函数信息（P1-5 CriteriaQuery 聚合函数支持）。
 */
public record AggregateInfo(
        /** 聚合函数名（COUNT / SUM / MAX / MIN / AVG）。 */
        @NotNull String function,
        /** 聚合目标字段名；COUNT(*) 时为 null。 */
        @Nullable String fieldName,
        /** 是否 DISTINCT。 */
        boolean distinct
) {
}
