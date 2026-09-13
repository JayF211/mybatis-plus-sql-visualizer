package cn.song.mybatisplus.visualizer.psi.jpa;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * WHERE 条件中间表示。
 */
public sealed class PredicateModel
        permits PredicateModel.Simple, PredicateModel.ExpressionComparison, PredicateModel.ExpressionBetween,
        PredicateModel.IsNull, PredicateModel.Between, PredicateModel.Like, PredicateModel.In,
        PredicateModel.BooleanConstant, PredicateModel.ColumnEquals, PredicateModel.Exists,
        PredicateModel.InSubQuery, PredicateModel.Group, PredicateModel.Raw {

    /** 简单二元比较：column op value。 */
    public static final class Simple extends PredicateModel {
    /** 实体字段名。 */
    private final @NotNull String fieldName;
    /** 比较运算符（= / != / &gt; / &gt;= / &lt; / &lt;=）。 */
    private final @NotNull String op;
    /** 值表达式文本。 */
    private final @NotNull String valueExpression;

    public Simple(@NotNull String fieldName, @NotNull String op, @NotNull String valueExpression) {
        this.fieldName = fieldName;
        this.op = op;
        this.valueExpression = valueExpression;
    }

    public @NotNull String getFieldName() { return fieldName; }
    public @NotNull String fieldName() { return fieldName; }
    public @NotNull String getOp() { return op; }
    public @NotNull String op() { return op; }
    public @NotNull String getValueExpression() { return valueExpression; }
    public @NotNull String valueExpression() { return valueExpression; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Simple)) return false;
        Simple that = (Simple) o;
        return java.util.Objects.equals(this.fieldName, that.fieldName) && java.util.Objects.equals(this.op, that.op) && java.util.Objects.equals(this.valueExpression, that.valueExpression);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fieldName, op, valueExpression);
    }

    @Override
    public String toString() {
        return "Simple[" + "fieldName=" + fieldName + ", " + "op=" + op + ", " + "valueExpression=" + valueExpression + "]";
    }
}

    /** 一元条件：IS NULL / IS NOT NULL。 */
    public static final class IsNull extends PredicateModel {
    /** 实体字段名。 */
    private final @NotNull String fieldName;
    /** 是否取反（IS NOT NULL）。 */
    private final boolean negated;

    public IsNull(@NotNull String fieldName, boolean negated) {
        this.fieldName = fieldName;
        this.negated = negated;
    }

    public @NotNull String getFieldName() { return fieldName; }
    public @NotNull String fieldName() { return fieldName; }
    public boolean isNegated() { return negated; }
    public boolean negated() { return negated; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IsNull)) return false;
        IsNull that = (IsNull) o;
        return java.util.Objects.equals(this.fieldName, that.fieldName) && java.util.Objects.equals(this.negated, that.negated);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fieldName, negated);
    }

    @Override
    public String toString() {
        return "IsNull[" + "fieldName=" + fieldName + ", " + "negated=" + negated + "]";
    }
}

    /** BETWEEN 范围条件。 */
    public static final class Between extends PredicateModel {
    /** 实体字段名。 */
    private final @NotNull String fieldName;
    /** 下界表达式。 */
    private final @NotNull String loExpression;
    /** 上界表达式。 */
    private final @NotNull String hiExpression;

    public Between(@NotNull String fieldName, @NotNull String loExpression, @NotNull String hiExpression) {
        this.fieldName = fieldName;
        this.loExpression = loExpression;
        this.hiExpression = hiExpression;
    }

    public @NotNull String getFieldName() { return fieldName; }
    public @NotNull String fieldName() { return fieldName; }
    public @NotNull String getLoExpression() { return loExpression; }
    public @NotNull String loExpression() { return loExpression; }
    public @NotNull String getHiExpression() { return hiExpression; }
    public @NotNull String hiExpression() { return hiExpression; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Between)) return false;
        Between that = (Between) o;
        return java.util.Objects.equals(this.fieldName, that.fieldName) && java.util.Objects.equals(this.loExpression, that.loExpression) && java.util.Objects.equals(this.hiExpression, that.hiExpression);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fieldName, loExpression, hiExpression);
    }

    @Override
    public String toString() {
        return "Between[" + "fieldName=" + fieldName + ", " + "loExpression=" + loExpression + ", " + "hiExpression=" + hiExpression + "]";
    }
}

    /** LIKE 模糊匹配。 */
    public static final class Like extends PredicateModel {
    /** 实体字段名。 */
    private final @NotNull String fieldName;
    /** 是否取反（NOT LIKE）。 */
    private final boolean negated;
    /** 匹配模式表达式。 */
    private final @NotNull String patternExpression;
    /**
     * escapeChar 重载的转义字符源码文本（T-J17：cb.like(x, p, '!') → ESCAPE）；null 表示无。
     */
    private final @Nullable String escapeChar;

    public Like(@NotNull String fieldName, boolean negated, @NotNull String patternExpression) {
        this(fieldName, negated, patternExpression, null);
    }

    public Like(@NotNull String fieldName, boolean negated, @NotNull String patternExpression,
                @Nullable String escapeChar) {
        this.fieldName = fieldName;
        this.negated = negated;
        this.patternExpression = patternExpression;
        this.escapeChar = escapeChar;
    }

    public @NotNull String getFieldName() { return fieldName; }
    public @NotNull String fieldName() { return fieldName; }
    public boolean isNegated() { return negated; }
    public boolean negated() { return negated; }
    public @NotNull String getPatternExpression() { return patternExpression; }
    public @NotNull String patternExpression() { return patternExpression; }
    public @Nullable String getEscapeChar() { return escapeChar; }
    public @Nullable String escapeChar() { return escapeChar; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Like)) return false;
        Like that = (Like) o;
        return java.util.Objects.equals(this.fieldName, that.fieldName) && java.util.Objects.equals(this.negated, that.negated) && java.util.Objects.equals(this.patternExpression, that.patternExpression) && java.util.Objects.equals(this.escapeChar, that.escapeChar);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fieldName, negated, patternExpression, escapeChar);
    }

    @Override
    public String toString() {
        return "Like[" + "fieldName=" + fieldName + ", " + "negated=" + negated + ", " + "patternExpression=" + patternExpression + ", " + "escapeChar=" + escapeChar + "]";
    }
}

    /** IN 列表条件。 */
    public static final class In extends PredicateModel {
    /** 实体字段名。 */
    private final @NotNull String fieldName;
    /** 是否取反（NOT IN）。 */
    private final boolean negated;
    /** 值表达式列表。 */
    private final @NotNull List<String> valueExpressions;

    public In(@NotNull String fieldName, boolean negated, @NotNull List<String> valueExpressions) {
        this.fieldName = fieldName;
        this.negated = negated;
        this.valueExpressions = valueExpressions;
    }

    public @NotNull String getFieldName() { return fieldName; }
    public @NotNull String fieldName() { return fieldName; }
    public boolean isNegated() { return negated; }
    public boolean negated() { return negated; }
    public @NotNull List<String> getValueExpressions() { return valueExpressions; }
    public @NotNull List<String> valueExpressions() { return valueExpressions; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof In)) return false;
        In that = (In) o;
        return java.util.Objects.equals(this.fieldName, that.fieldName) && java.util.Objects.equals(this.negated, that.negated) && java.util.Objects.equals(this.valueExpressions, that.valueExpressions);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fieldName, negated, valueExpressions);
    }

    @Override
    public String toString() {
        return "In[" + "fieldName=" + fieldName + ", " + "negated=" + negated + ", " + "valueExpressions=" + valueExpressions + "]";
    }
}

    /** 逻辑分组（and / or）。 */
    public static final class Group extends PredicateModel {
    /** 连接符（AND / OR）。 */
    private final @NotNull GroupConnector connector;
    /** 子条件列表。 */
    private final @NotNull List<PredicateModel> children;
    /** 是否取反（NOT 包裹）。 */
    private final boolean negated;

    public Group(@NotNull GroupConnector connector, @NotNull List<PredicateModel> children, boolean negated) {
        this.connector = connector;
        this.children = children;
        this.negated = negated;
    }

    public @NotNull GroupConnector getConnector() { return connector; }
    public @NotNull GroupConnector connector() { return connector; }
    public @NotNull List<PredicateModel> getChildren() { return children; }
    public @NotNull List<PredicateModel> children() { return children; }
    public boolean isNegated() { return negated; }
    public boolean negated() { return negated; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Group)) return false;
        Group that = (Group) o;
        return java.util.Objects.equals(this.connector, that.connector) && java.util.Objects.equals(this.children, that.children) && java.util.Objects.equals(this.negated, that.negated);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(connector, children, negated);
    }

    @Override
    public String toString() {
        return "Group[" + "connector=" + connector + ", " + "children=" + children + ", " + "negated=" + negated + "]";
    }
}

    /** 无法识别的调用降级。 */
    public static final class Raw extends PredicateModel {
    /** 原始表达式文本。 */
    private final @NotNull String expressionText;

    public Raw(@NotNull String expressionText) {
        this.expressionText = expressionText;
    }

    public @NotNull String getExpressionText() { return expressionText; }
    public @NotNull String expressionText() { return expressionText; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Raw)) return false;
        Raw that = (Raw) o;
        return java.util.Objects.equals(this.expressionText, that.expressionText);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(expressionText);
    }

    @Override
    public String toString() {
        return "Raw[" + "expressionText=" + expressionText + "]";
    }
}

    /**
     * 表达式比较（T-J17）：左值为函数/运算/CASE 表达式（LOWER(name)、(a + b)、CASE ...）。
     *
     * <p>op 另支持 "IS NULL" / "IS NOT NULL" / "LIKE" / "NOT LIKE"（右值仅 LIKE 需要）。
     */
    public static final class ExpressionComparison extends PredicateModel {
    private final @NotNull ExpressionModel left;
    private final @NotNull String op;
    private final @NotNull ExpressionModel right;

    public ExpressionComparison(@NotNull ExpressionModel left, @NotNull String op, @NotNull ExpressionModel right) {
        this.left = left;
        this.op = op;
        this.right = right;
    }

    public @NotNull ExpressionModel getLeft() { return left; }
    public @NotNull ExpressionModel left() { return left; }
    public @NotNull String getOp() { return op; }
    public @NotNull String op() { return op; }
    public @NotNull ExpressionModel getRight() { return right; }
    public @NotNull ExpressionModel right() { return right; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExpressionComparison)) return false;
        ExpressionComparison that = (ExpressionComparison) o;
        return java.util.Objects.equals(this.left, that.left) && java.util.Objects.equals(this.op, that.op) && java.util.Objects.equals(this.right, that.right);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(left, op, right);
    }

    @Override
    public String toString() {
        return "ExpressionComparison[" + "left=" + left + ", " + "op=" + op + ", " + "right=" + right + "]";
    }
}

    /** 表达式 BETWEEN（T-J17：cb.between(cb.lower(x), lo, hi) 等左值为表达式的形态）。 */
    public static final class ExpressionBetween extends PredicateModel {
    private final @NotNull ExpressionModel left;
    private final @NotNull ExpressionModel lo;
    private final @NotNull ExpressionModel hi;

    public ExpressionBetween(@NotNull ExpressionModel left, @NotNull ExpressionModel lo, @NotNull ExpressionModel hi) {
        this.left = left;
        this.lo = lo;
        this.hi = hi;
    }

    public @NotNull ExpressionModel getLeft() { return left; }
    public @NotNull ExpressionModel left() { return left; }
    public @NotNull ExpressionModel getLo() { return lo; }
    public @NotNull ExpressionModel lo() { return lo; }
    public @NotNull ExpressionModel getHi() { return hi; }
    public @NotNull ExpressionModel hi() { return hi; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExpressionBetween)) return false;
        ExpressionBetween that = (ExpressionBetween) o;
        return java.util.Objects.equals(this.left, that.left) && java.util.Objects.equals(this.lo, that.lo) && java.util.Objects.equals(this.hi, that.hi);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(left, lo, hi);
    }

    @Override
    public String toString() {
        return "ExpressionBetween[" + "left=" + left + ", " + "lo=" + lo + ", " + "hi=" + hi + "]";
    }
}

    /** 布尔常量谓词（T-J17）：conjunction → 1 = 1 / disjunction → 1 = 0。 */
    public static final class BooleanConstant extends PredicateModel {
    private final boolean value;

    public BooleanConstant(boolean value) {
        this.value = value;
    }

    public boolean isValue() { return value; }
    public boolean value() { return value; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BooleanConstant)) return false;
        BooleanConstant that = (BooleanConstant) o;
        return this.value == that.value;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(value);
    }

    @Override
    public String toString() {
        return "BooleanConstant[" + "value=" + value + "]";
    }
}

    /** 列对列比较（Join.on 显式 ON / root.get 与 join.get 互比；T-J7）。 */
    public static final class ColumnEquals extends PredicateModel {
    private final @NotNull String leftFieldName;
    private final @NotNull String rightFieldName;
    private final @NotNull String op;
    /** 左/右值 get 调用的接收者变量名（root / join 变量），converter 据此映射表别名。 */
    private final @Nullable String leftVariable;
    private final @Nullable String rightVariable;

    public ColumnEquals(@NotNull String leftFieldName, @NotNull String rightFieldName) {
        this(leftFieldName, rightFieldName, "=", null, null);
    }

    public ColumnEquals(@NotNull String leftFieldName, @NotNull String rightFieldName, @NotNull String op,
                        @Nullable String leftVariable, @Nullable String rightVariable) {
        this.leftFieldName = leftFieldName;
        this.rightFieldName = rightFieldName;
        this.op = op;
        this.leftVariable = leftVariable;
        this.rightVariable = rightVariable;
    }

    public @NotNull String getLeftFieldName() { return leftFieldName; }
    public @NotNull String leftFieldName() { return leftFieldName; }
    public @NotNull String getRightFieldName() { return rightFieldName; }
    public @NotNull String rightFieldName() { return rightFieldName; }
    public @NotNull String getOp() { return op; }
    public @NotNull String op() { return op; }
    public @Nullable String getLeftVariable() { return leftVariable; }
    public @Nullable String leftVariable() { return leftVariable; }
    public @Nullable String getRightVariable() { return rightVariable; }
    public @Nullable String rightVariable() { return rightVariable; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ColumnEquals)) return false;
        ColumnEquals that = (ColumnEquals) o;
        return java.util.Objects.equals(this.leftFieldName, that.leftFieldName) && java.util.Objects.equals(this.rightFieldName, that.rightFieldName) && java.util.Objects.equals(this.op, that.op) && java.util.Objects.equals(this.leftVariable, that.leftVariable) && java.util.Objects.equals(this.rightVariable, that.rightVariable);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(leftFieldName, rightFieldName, op, leftVariable, rightVariable);
    }

    @Override
    public String toString() {
        return "ColumnEquals[" + "leftFieldName=" + leftFieldName + ", " + "rightFieldName=" + rightFieldName + ", " + "op=" + op + ", " + "leftVariable=" + leftVariable + ", " + "rightVariable=" + rightVariable + "]";
    }
}

    /** EXISTS 子查询（T-J17/J13 有界实现：单层子查询）。 */
    public static final class Exists extends PredicateModel {
    private final @NotNull SubqueryModel subQuery;
    private final boolean negated;

    public Exists(@NotNull SubqueryModel subQuery) {
        this(subQuery, false);
    }

    public Exists(@NotNull SubqueryModel subQuery, boolean negated) {
        this.subQuery = subQuery;
        this.negated = negated;
    }

    public @NotNull SubqueryModel getSubQuery() { return subQuery; }
    public @NotNull SubqueryModel subQuery() { return subQuery; }
    public boolean isNegated() { return negated; }
    public boolean negated() { return negated; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Exists)) return false;
        Exists that = (Exists) o;
        return java.util.Objects.equals(this.subQuery, that.subQuery) && this.negated == that.negated;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(subQuery, negated);
    }

    @Override
    public String toString() {
        return "Exists[" + "subQuery=" + subQuery + ", " + "negated=" + negated + "]";
    }
}

    /** 列 IN 子查询（T-J17/J13：path.get("x").in(subquery)）。 */
    public static final class InSubQuery extends PredicateModel {
    private final @NotNull String fieldName;
    private final @NotNull SubqueryModel subQuery;
    private final boolean negated;

    public InSubQuery(@NotNull String fieldName, @NotNull SubqueryModel subQuery) {
        this(fieldName, subQuery, false);
    }

    public InSubQuery(@NotNull String fieldName, @NotNull SubqueryModel subQuery, boolean negated) {
        this.fieldName = fieldName;
        this.subQuery = subQuery;
        this.negated = negated;
    }

    public @NotNull String getFieldName() { return fieldName; }
    public @NotNull String fieldName() { return fieldName; }
    public @NotNull SubqueryModel getSubQuery() { return subQuery; }
    public @NotNull SubqueryModel subQuery() { return subQuery; }
    public boolean isNegated() { return negated; }
    public boolean negated() { return negated; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InSubQuery)) return false;
        InSubQuery that = (InSubQuery) o;
        return java.util.Objects.equals(this.fieldName, that.fieldName) && java.util.Objects.equals(this.subQuery, that.subQuery) && this.negated == that.negated;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fieldName, subQuery, negated);
    }

    @Override
    public String toString() {
        return "InSubQuery[" + "fieldName=" + fieldName + ", " + "subQuery=" + subQuery + ", " + "negated=" + negated + "]";
    }
}
}
