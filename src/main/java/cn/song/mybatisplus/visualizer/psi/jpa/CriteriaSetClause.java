package cn.song.mybatisplus.visualizer.psi.jpa;

import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * UPDATE SET 赋值中间表示（T-J6）。
 *
 * @param fieldName       目标实体字段名（set(root.get("name"), v) / set("name", v) 的字段）
 * @param valueExpression 值的 Java 源码文本；setNull(path) 或 set(path, null) 时为 "null"
 */
public final class CriteriaSetClause {
    private final @NotNull String fieldName;
    private final @Nullable String valueExpression;

    public CriteriaSetClause(@NotNull String fieldName, @Nullable String valueExpression) {
        this.fieldName = fieldName;
        this.valueExpression = valueExpression;
    }

    public CriteriaSetClause(@NotNull String fieldName) {
        this(fieldName, null);
    }

    public @NotNull String getFieldName() { return fieldName; }
    public @NotNull String fieldName() { return fieldName; }
    public @Nullable String getValueExpression() { return valueExpression; }
    public @Nullable String valueExpression() { return valueExpression; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CriteriaSetClause)) return false;
        CriteriaSetClause that = (CriteriaSetClause) o;
        return Objects.equals(this.fieldName, that.fieldName) && Objects.equals(this.valueExpression, that.valueExpression);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldName, valueExpression);
    }

    @Override
    public String toString() {
        return "CriteriaSetClause[" + "fieldName=" + fieldName + ", " + "valueExpression=" + valueExpression + "]";
    }
}
