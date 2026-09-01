package cn.song.mybatisplus.visualizer.converter.jpa;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Subject 段：方法名中 `By` 之前的部分。
 *
 * @param operation 操作类型（FIND / COUNT / EXISTS / DELETE）
 * @param distinct  是否去重
 * @param limit     限制条数（Top N / First N），null 表示不限制
 */
public record Subject(
        @NotNull Operation operation,
        boolean distinct,
        @Nullable Integer limit
) {
}
