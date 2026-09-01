package cn.song.mybatisplus.visualizer.psi.jpa;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * WHERE 条件中间表示。
 */
public sealed class PredicateModel
        permits PredicateModel.Simple, PredicateModel.IsNull, PredicateModel.Between,
        PredicateModel.Like, PredicateModel.In, PredicateModel.Group, PredicateModel.Raw {

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

    public Like(@NotNull String fieldName, boolean negated, @NotNull String patternExpression) {
        this.fieldName = fieldName;
        this.negated = negated;
        this.patternExpression = patternExpression;
    }

    public @NotNull String getFieldName() { return fieldName; }
    public @NotNull String fieldName() { return fieldName; }
    public boolean isNegated() { return negated; }
    public boolean negated() { return negated; }
    public @NotNull String getPatternExpression() { return patternExpression; }
    public @NotNull String patternExpression() { return patternExpression; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Like)) return false;
        Like that = (Like) o;
        return java.util.Objects.equals(this.fieldName, that.fieldName) && java.util.Objects.equals(this.negated, that.negated) && java.util.Objects.equals(this.patternExpression, that.patternExpression);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fieldName, negated, patternExpression);
    }

    @Override
    public String toString() {
        return "Like[" + "fieldName=" + fieldName + ", " + "negated=" + negated + ", " + "patternExpression=" + patternExpression + "]";
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
}
