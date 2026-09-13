package cn.song.mybatisplus.visualizer.psi.jpa;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * SELECT 列中间表示。
 */
public final class SelectColumn {
    /** 实体字段名（驼峰，如 shareSchemeId）；聚合函数/表达式列时为 null。 */
    private final @Nullable String fieldName;
    /** 列别名（当前未使用，预留）。 */
    private final @Nullable String alias;
    /** 聚合函数信息；null 表示非聚合列。 */
    private final @Nullable AggregateInfo aggregate;
    /** 复杂表达式列（T-J17：CASE / COALESCE / CONCAT / 算术运算等）；非 null 时由
     *  converter 渲染为 rawExpr。 */
    private final @Nullable ExpressionModel expression;

    public SelectColumn(@Nullable String fieldName, @Nullable String alias, @Nullable AggregateInfo aggregate) {
        this(fieldName, alias, aggregate, null);
    }

    public SelectColumn(@Nullable String fieldName, @Nullable String alias, @Nullable AggregateInfo aggregate,
                        @Nullable ExpressionModel expression) {
        this.fieldName = fieldName;
        this.alias = alias;
        this.aggregate = aggregate;
        this.expression = expression;
    }

    public @Nullable String getFieldName() { return fieldName; }
    public @Nullable String fieldName() { return fieldName; }
    public @Nullable String getAlias() { return alias; }
    public @Nullable String alias() { return alias; }
    public @Nullable AggregateInfo getAggregate() { return aggregate; }
    public @Nullable AggregateInfo aggregate() { return aggregate; }
    public @Nullable ExpressionModel getExpression() { return expression; }
    public @Nullable ExpressionModel expression() { return expression; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SelectColumn)) return false;
        SelectColumn that = (SelectColumn) o;
        return java.util.Objects.equals(this.fieldName, that.fieldName) && java.util.Objects.equals(this.alias, that.alias) && java.util.Objects.equals(this.aggregate, that.aggregate) && java.util.Objects.equals(this.expression, that.expression);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fieldName, alias, aggregate, expression);
    }

    @Override
    public String toString() {
        return "SelectColumn[" + "fieldName=" + fieldName + ", " + "alias=" + alias + ", " + "aggregate=" + aggregate + ", " + "expression=" + expression + "]";
    }
}
