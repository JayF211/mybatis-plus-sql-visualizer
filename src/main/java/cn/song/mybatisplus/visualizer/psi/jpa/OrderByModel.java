package cn.song.mybatisplus.visualizer.psi.jpa;

import org.jetbrains.annotations.NotNull;

/**
 * ORDER BY 中间表示。
 */
public record OrderByModel(
        /** 实体字段名。 */
        @NotNull String fieldName,
        /** true=ASC / false=DESC。 */
        boolean asc
) {
}
