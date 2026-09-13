package cn.song.mybatisplus.visualizer.psi.jpa;

import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * CASE WHEN 分支（T-J17）。
 *
 * <p>searched case（selectCase()）时 predicate 非空；simple case（selectCase(expr)）时
 * condition 非空，两者互斥。result 为 THEN 结果。
 */
public final class CaseWhen {
    private final @Nullable ExpressionModel condition;
    private final @Nullable PredicateModel predicate;
    private final @Nullable ExpressionModel result;

    public CaseWhen(@Nullable ExpressionModel condition, @Nullable PredicateModel predicate,
                    @Nullable ExpressionModel result) {
        this.condition = condition;
        this.predicate = predicate;
        this.result = result;
    }

    public @Nullable ExpressionModel getCondition() { return condition; }
    public @Nullable ExpressionModel condition() { return condition; }
    public @Nullable PredicateModel getPredicate() { return predicate; }
    public @Nullable PredicateModel predicate() { return predicate; }
    public @Nullable ExpressionModel getResult() { return result; }
    public @Nullable ExpressionModel result() { return result; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CaseWhen)) return false;
        CaseWhen that = (CaseWhen) o;
        return Objects.equals(this.condition, that.condition) && Objects.equals(this.predicate, that.predicate) && Objects.equals(this.result, that.result);
    }

    @Override
    public int hashCode() {
        return Objects.hash(condition, predicate, result);
    }

    @Override
    public String toString() {
        return "CaseWhen[" + "condition=" + condition + ", " + "predicate=" + predicate + ", " + "result=" + result + "]";
    }
}
