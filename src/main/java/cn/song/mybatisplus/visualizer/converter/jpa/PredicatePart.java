package cn.song.mybatisplus.visualizer.converter.jpa;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Predicate 段中的单个 OrPart。
 *
 * 多个 PredicatePart 之间为 OR 连接，内部 conditions 之间为 AND 连接。
 * [connector] 表示该 Part 与前一个 Part 的连接符（第一个恒为 AND）。
 *
 * @param connector  与前一个 OrPart 的连接符（第一个恒为 AND）
 * @param conditions 该 OrPart 内的 AND 条件列表
 */
public record PredicatePart(
        @NotNull Connector connector,
        @NotNull List<Condition> conditions
) {
}
