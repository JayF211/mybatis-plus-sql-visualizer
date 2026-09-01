package cn.song.mybatisplus.visualizer.psi.jpa;

import cn.song.mybatisplus.visualizer.model.JoinType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * JOIN 中间表示。
 */
public record JoinModel(
        /** 关联属性名（如 "department"）。 */
        @NotNull String fieldName,
        /** 关联实体类名（可解析时非 null）。 */
        @Nullable String entityClassName,
        /** 联表类型。 */
        @NotNull JoinType joinType,
        /** 关联表别名（未指定时为 null）。 */
        @Nullable String alias
) {
}
