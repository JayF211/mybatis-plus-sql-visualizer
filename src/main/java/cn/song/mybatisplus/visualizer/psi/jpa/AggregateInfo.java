package cn.song.mybatisplus.visualizer.psi.jpa;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 聚合函数信息（P1-5 CriteriaQuery 聚合函数支持，T-J17 扩展）。
 */
public final class AggregateInfo {
    /** 聚合函数名（COUNT / SUM / MAX / MIN / AVG / greatest→MAX / least→MIN）。 */
    private final @NotNull String function;
    /** 聚合目标字段名；COUNT(*) 时为 null。 */
    private final @Nullable String fieldName;
    /** 是否 DISTINCT（countDistinct → COUNT(DISTINCT x)）。 */
    private final boolean distinct;
    /** 聚合前 CAST 目标类型（sumAsLong → BIGINT / sumAsDouble → DOUBLE）。 */
    private final @Nullable String castType;
    /** 聚合内层复杂表达式（如 AVG(COALESCE(age, 0))）；fieldName 直取列时为 null。 */
    private final @Nullable ExpressionModel expression;

    public AggregateInfo(@NotNull String function, @Nullable String fieldName, boolean distinct) {
        this(function, fieldName, distinct, null, null);
    }

    public AggregateInfo(@NotNull String function, @Nullable String fieldName, boolean distinct,
                         @Nullable String castType, @Nullable ExpressionModel expression) {
        this.function = function;
        this.fieldName = fieldName;
        this.distinct = distinct;
        this.castType = castType;
        this.expression = expression;
    }

    public @NotNull String getFunction() { return function; }
    public @NotNull String function() { return function; }
    public @Nullable String getFieldName() { return fieldName; }
    public @Nullable String fieldName() { return fieldName; }
    public boolean isDistinct() { return distinct; }
    public boolean distinct() { return distinct; }
    public @Nullable String getCastType() { return castType; }
    public @Nullable String castType() { return castType; }
    public @Nullable ExpressionModel getExpression() { return expression; }
    public @Nullable ExpressionModel expression() { return expression; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AggregateInfo)) return false;
        AggregateInfo that = (AggregateInfo) o;
        return java.util.Objects.equals(this.function, that.function) && java.util.Objects.equals(this.fieldName, that.fieldName) && java.util.Objects.equals(this.distinct, that.distinct) && java.util.Objects.equals(this.castType, that.castType) && java.util.Objects.equals(this.expression, that.expression);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(function, fieldName, distinct, castType, expression);
    }

    @Override
    public String toString() {
        return "AggregateInfo[" + "function=" + function + ", " + "fieldName=" + fieldName + ", " + "distinct=" + distinct + ", " + "castType=" + castType + ", " + "expression=" + expression + "]";
    }
}
