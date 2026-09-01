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
    public record Simple(
            /** 实体字段名。 */
            @NotNull String fieldName,
            /** 比较运算符（= / != / &gt; / &gt;= / &lt; / &lt;=）。 */
            @NotNull String op,
            /** 值表达式文本。 */
            @NotNull String valueExpression
    ) {
    }

    /** 一元条件：IS NULL / IS NOT NULL。 */
    public record IsNull(
            /** 实体字段名。 */
            @NotNull String fieldName,
            /** 是否取反（IS NOT NULL）。 */
            boolean negated
    ) {
    }

    /** BETWEEN 范围条件。 */
    public record Between(
            /** 实体字段名。 */
            @NotNull String fieldName,
            /** 下界表达式。 */
            @NotNull String loExpression,
            /** 上界表达式。 */
            @NotNull String hiExpression
    ) {
    }

    /** LIKE 模糊匹配。 */
    public record Like(
            /** 实体字段名。 */
            @NotNull String fieldName,
            /** 是否取反（NOT LIKE）。 */
            boolean negated,
            /** 匹配模式表达式。 */
            @NotNull String patternExpression
    ) {
    }

    /** IN 列表条件。 */
    public record In(
            /** 实体字段名。 */
            @NotNull String fieldName,
            /** 是否取反（NOT IN）。 */
            boolean negated,
            /** 值表达式列表。 */
            @NotNull List<String> valueExpressions
    ) {
    }

    /** 逻辑分组（and / or）。 */
    public record Group(
            /** 连接符（AND / OR）。 */
            @NotNull GroupConnector connector,
            /** 子条件列表。 */
            @NotNull List<PredicateModel> children,
            /** 是否取反（NOT 包裹）。 */
            boolean negated
    ) {
    }

    /** 无法识别的调用降级。 */
    public record Raw(
            /** 原始表达式文本。 */
            @NotNull String expressionText
    ) {
    }
}
