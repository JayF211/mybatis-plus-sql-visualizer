package cn.song.mybatisplus.visualizer.psi.jpa;

import cn.song.mybatisplus.visualizer.model.JoinType;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * JOIN 中间表示。
 */
public final class JoinModel {
    /** 关联属性名（如 "department"）。 */
    private final @NotNull String fieldName;
    /** 关联实体类名（可解析时非 null）。 */
    private final @Nullable String entityClassName;
    /** 联表类型（无 JoinType 实参的 join(String) 按规范默认 INNER，T-J7）。 */
    private final @NotNull JoinType joinType;
    /** 关联表别名（未指定时为 null）。 */
    private final @Nullable String alias;
    /** 是否 fetch 调用（T-J7：FetchParent.fetch 语义为 EAGER 抓取合并入主查询，
     *  渲染上与 join 同形）。 */
    private final boolean fetch;
    /** Join.on(...) 显式 ON 谓词（T-J7）；非空时优先于外键推断。 */
    private final @NotNull List<PredicateModel> onPredicates;
    /** join/fetch 返回值赋值的变量名（deptJoin 等）；显式 ON 的列别名归属判定用。 */
    private final @Nullable String variableName;

    public JoinModel(@NotNull String fieldName, @Nullable String entityClassName,
                     @NotNull JoinType joinType, @Nullable String alias) {
        this(fieldName, entityClassName, joinType, alias, false, List.of(), null);
    }

    public JoinModel(@NotNull String fieldName, @Nullable String entityClassName,
                     @NotNull JoinType joinType, @Nullable String alias, boolean fetch,
                     @NotNull List<PredicateModel> onPredicates, @Nullable String variableName) {
        this.fieldName = fieldName;
        this.entityClassName = entityClassName;
        this.joinType = joinType;
        this.alias = alias;
        this.fetch = fetch;
        this.onPredicates = onPredicates;
        this.variableName = variableName;
    }

    public @NotNull String getFieldName() { return fieldName; }
    public @NotNull String fieldName() { return fieldName; }
    public @Nullable String getEntityClassName() { return entityClassName; }
    public @Nullable String entityClassName() { return entityClassName; }
    public @NotNull JoinType getJoinType() { return joinType; }
    public @NotNull JoinType joinType() { return joinType; }
    public @Nullable String getAlias() { return alias; }
    public @Nullable String alias() { return alias; }
    public boolean isFetch() { return fetch; }
    public boolean fetch() { return fetch; }
    public @NotNull List<PredicateModel> getOnPredicates() { return onPredicates; }
    public @NotNull List<PredicateModel> onPredicates() { return onPredicates; }
    public @Nullable String getVariableName() { return variableName; }
    public @Nullable String variableName() { return variableName; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JoinModel)) return false;
        JoinModel that = (JoinModel) o;
        return java.util.Objects.equals(this.fieldName, that.fieldName) && java.util.Objects.equals(this.entityClassName, that.entityClassName) && java.util.Objects.equals(this.joinType, that.joinType) && java.util.Objects.equals(this.alias, that.alias) && this.fetch == that.fetch && java.util.Objects.equals(this.onPredicates, that.onPredicates) && java.util.Objects.equals(this.variableName, that.variableName);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fieldName, entityClassName, joinType, alias, fetch, onPredicates, variableName);
    }

    @Override
    public String toString() {
        return "JoinModel[" + "fieldName=" + fieldName + ", " + "entityClassName=" + entityClassName + ", " + "joinType=" + joinType + ", " + "alias=" + alias + ", " + "fetch=" + fetch + ", " + "onPredicates=" + onPredicates + ", " + "variableName=" + variableName + "]";
    }
}
