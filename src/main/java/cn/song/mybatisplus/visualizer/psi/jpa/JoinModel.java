package cn.song.mybatisplus.visualizer.psi.jpa;

import cn.song.mybatisplus.visualizer.model.JoinType;
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
    /** 联表类型。 */
    private final @NotNull JoinType joinType;
    /** 关联表别名（未指定时为 null）。 */
    private final @Nullable String alias;

    public JoinModel(@NotNull String fieldName, @Nullable String entityClassName, @NotNull JoinType joinType, @Nullable String alias) {
        this.fieldName = fieldName;
        this.entityClassName = entityClassName;
        this.joinType = joinType;
        this.alias = alias;
    }

    public @NotNull String getFieldName() { return fieldName; }
    public @NotNull String fieldName() { return fieldName; }
    public @Nullable String getEntityClassName() { return entityClassName; }
    public @Nullable String entityClassName() { return entityClassName; }
    public @NotNull JoinType getJoinType() { return joinType; }
    public @NotNull JoinType joinType() { return joinType; }
    public @Nullable String getAlias() { return alias; }
    public @Nullable String alias() { return alias; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JoinModel)) return false;
        JoinModel that = (JoinModel) o;
        return java.util.Objects.equals(this.fieldName, that.fieldName) && java.util.Objects.equals(this.entityClassName, that.entityClassName) && java.util.Objects.equals(this.joinType, that.joinType) && java.util.Objects.equals(this.alias, that.alias);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fieldName, entityClassName, joinType, alias);
    }

    @Override
    public String toString() {
        return "JoinModel[" + "fieldName=" + fieldName + ", " + "entityClassName=" + entityClassName + ", " + "joinType=" + joinType + ", " + "alias=" + alias + "]";
    }
}
