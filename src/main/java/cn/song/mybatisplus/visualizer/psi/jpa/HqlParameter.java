package cn.song.mybatisplus.visualizer.psi.jpa;

import org.jetbrains.annotations.NotNull;

/**
 * HQL 参数设置信息（setParameter/setInteger 等调用）。
 *
 * @param paramName     参数名（命名参数）或位置索引字符串（位置参数）
 * @param valueExpr     参数值表达式文本
 * @param isPositional 是否为位置参数（setInteger(0, value) 等）
 */
public record HqlParameter(
        /** 参数名（命名参数）或位置索引字符串（位置参数）。 */
        @NotNull String paramName,
        /** 参数值表达式文本。 */
        @NotNull String valueExpr,
        /** 是否为位置参数（setInteger(0, value) 等）。 */
        boolean isPositional
) {
}
