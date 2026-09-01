package cn.song.mybatisplus.visualizer.converter.jpa;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * PartTree 解析产物。
 *
 * @param subject    Subject 段（操作类型、Distinct、限制条数）
 * @param predicates Predicate 段（Or/And 连接的条件列表）
 * @param orderBy    OrderBy 排序子句
 */
public record PartTree(
        @NotNull Subject subject,
        @NotNull List<PredicatePart> predicates,
        @NotNull List<OrderByPart> orderBy
) {
}
