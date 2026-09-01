package cn.song.mybatisplus.visualizer.psi.jpa;

import org.jetbrains.annotations.NotNull;

/**
 * HQL 参数设置信息（setParameter/setInteger 等调用）。
 *
 * @param paramName     参数名（命名参数）或位置索引字符串（位置参数）
 * @param valueExpr     参数值表达式文本
 * @param isPositional 是否为位置参数（setInteger(0, value) 等）
 */
public final class HqlParameter {
    /** 参数名（命名参数）或位置索引字符串（位置参数）。 */
    private final @NotNull String paramName;
    /** 参数值表达式文本。 */
    private final @NotNull String valueExpr;
    /** 是否为位置参数（setInteger(0, value) 等）。 */
    private final boolean positional;

    public HqlParameter(@NotNull String paramName, @NotNull String valueExpr, boolean isPositional) {
        this.paramName = paramName;
        this.valueExpr = valueExpr;
        this.positional = isPositional;
    }

    public @NotNull String getParamName() { return paramName; }
    public @NotNull String paramName() { return paramName; }
    public @NotNull String getValueExpr() { return valueExpr; }
    public @NotNull String valueExpr() { return valueExpr; }
    public boolean isPositional() { return positional; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HqlParameter)) return false;
        HqlParameter that = (HqlParameter) o;
        return java.util.Objects.equals(this.paramName, that.paramName) && java.util.Objects.equals(this.valueExpr, that.valueExpr) && java.util.Objects.equals(this.positional, that.positional);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(paramName, valueExpr, positional);
    }

    @Override
    public String toString() {
        return "HqlParameter[" + "paramName=" + paramName + ", " + "valueExpr=" + valueExpr + ", " + "isPositional=" + positional + "]";
    }
}
