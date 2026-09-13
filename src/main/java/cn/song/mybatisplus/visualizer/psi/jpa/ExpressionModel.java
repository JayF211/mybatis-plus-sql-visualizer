package cn.song.mybatisplus.visualizer.psi.jpa;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * CriteriaBuilder 表达式中间表示（T-J17）。
 *
 * <p>覆盖字符串/数值/算术/转换/CASE 等表达式函数；叶子为列引用、字面量/变量值或降级原文。
 * 列名映射（实体字段 → 数据库列）由 converter 按表元数据完成，locator 只保留字段名。
 */
public sealed class ExpressionModel
        permits ExpressionModel.Column, ExpressionModel.Function, ExpressionModel.Case,
        ExpressionModel.Value, ExpressionModel.Raw {

    /** 列引用：root.get("field") / root.get(Xxx_.field)。 */
    public static final class Column extends ExpressionModel {
        private final @NotNull String fieldName;

        public Column(@NotNull String fieldName) {
            this.fieldName = fieldName;
        }

        public @NotNull String getFieldName() { return fieldName; }
        public @NotNull String fieldName() { return fieldName; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Column)) return false;
            Column that = (Column) o;
            return java.util.Objects.equals(this.fieldName, that.fieldName);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(fieldName);
        }

        @Override
        public String toString() {
            return "Column[" + "fieldName=" + fieldName + "]";
        }
    }

    /**
     * 函数/运算节点。
     *
     * @param function 规范化函数标记：普通函数用 SQL 名（LOWER/CONCAT/ABS...），算术用
     *                 ADD/SUB/MUL/DIV/MOD/NEG，CAST 以 modifier 携带目标类型，TRIM 以
     *                 modifier 携带 Trimspec
     * @param args     参数表达式
     * @param modifier 附加修饰（CAST 类型 / TRIM 规格等）
     */
    public static final class Function extends ExpressionModel {
        private final @NotNull String function;
        private final @NotNull List<ExpressionModel> args;
        private final @Nullable String modifier;

        public Function(@NotNull String function, @NotNull List<ExpressionModel> args, @Nullable String modifier) {
            this.function = function;
            this.args = args;
            this.modifier = modifier;
        }

        public Function(@NotNull String function) {
            this(function, List.of(), null);
        }

        public @NotNull String getFunction() { return function; }
        public @NotNull String function() { return function; }
        public @NotNull List<ExpressionModel> getArgs() { return args; }
        public @NotNull List<ExpressionModel> args() { return args; }
        public @Nullable String getModifier() { return modifier; }
        public @Nullable String modifier() { return modifier; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Function)) return false;
            Function that = (Function) o;
            return java.util.Objects.equals(this.function, that.function) && java.util.Objects.equals(this.args, that.args) && java.util.Objects.equals(this.modifier, that.modifier);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(function, args, modifier);
        }

        @Override
        public String toString() {
            return "Function[" + "function=" + function + ", " + "args=" + args + ", " + "modifier=" + modifier + "]";
        }
    }

    /**
     * CASE 表达式（T-J17）：selectCase().when(cond, r)...otherwise(e) 与 selectCase(expr).when(v, r)...。
     *
     * @param simpleExpr SimpleCase 基准表达式；searched case 为 null
     * @param whens      WHEN 分支（按调用顺序）
     * @param otherwise  ELSE 分支；无 otherwise 时为 null
     */
    public static final class Case extends ExpressionModel {
        private final @Nullable ExpressionModel simpleExpr;
        private final @NotNull List<CaseWhen> whens;
        private final @Nullable ExpressionModel otherwise;

        public Case(@Nullable ExpressionModel simpleExpr, @NotNull List<CaseWhen> whens,
                    @Nullable ExpressionModel otherwise) {
            this.simpleExpr = simpleExpr;
            this.whens = whens;
            this.otherwise = otherwise;
        }

        public @Nullable ExpressionModel getSimpleExpr() { return simpleExpr; }
        public @Nullable ExpressionModel simpleExpr() { return simpleExpr; }
        public @NotNull List<CaseWhen> getWhens() { return whens; }
        public @NotNull List<CaseWhen> whens() { return whens; }
        public @Nullable ExpressionModel getOtherwise() { return otherwise; }
        public @Nullable ExpressionModel otherwise() { return otherwise; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Case)) return false;
            Case that = (Case) o;
            return java.util.Objects.equals(this.simpleExpr, that.simpleExpr) && java.util.Objects.equals(this.whens, that.whens) && java.util.Objects.equals(this.otherwise, that.otherwise);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(simpleExpr, whens, otherwise);
        }

        @Override
        public String toString() {
            return "Case[" + "simpleExpr=" + simpleExpr + ", " + "whens=" + whens + ", " + "otherwise=" + otherwise + "]";
        }
    }

    /** 字面量/变量/参数等值叶子的 Java 源码文本（converter 按 sqlValue 求值）。 */
    public static final class Value extends ExpressionModel {
        private final @NotNull String expressionText;

        public Value(@NotNull String expressionText) {
            this.expressionText = expressionText;
        }

        public @NotNull String getExpressionText() { return expressionText; }
        public @NotNull String expressionText() { return expressionText; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Value)) return false;
            Value that = (Value) o;
            return java.util.Objects.equals(this.expressionText, that.expressionText);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(expressionText);
        }

        @Override
        public String toString() {
            return "Value[" + "expressionText=" + expressionText + "]";
        }
    }

    /** 无法结构化的降级原文（含集合 size 等注释降级）。 */
    public static final class Raw extends ExpressionModel {
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
