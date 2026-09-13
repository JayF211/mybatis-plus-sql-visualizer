package cn.song.mybatisplus.visualizer.psi.jpa;

import com.intellij.psi.PsiMethod;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 自定义 DAO 方法的 HQL 查询信息（CustomDaoHqlLocator 产物）。
 *
 * @param hql            HQL 字符串（字面量直接取值；变量引用或拼接时为拼接结果）
 * @param isLiteralHql   hql 是否为纯字面量（true=直接字面量；false=变量引用或拼接）
 * @param entityClassName 从 HQL 的 FROM 子句提取的实体类名（驼峰标识符）；null 表示未提取到
 * @param parameters     参数列表（以 HQL 中 :paramName 为主，值从方法参数中按名匹配）
 * @param method         HQL 所在 PsiMethod
 * @param paramSource    参数传递方式（name-value 交替 / Map / 位置参数 / 无额外参数）
 */
public final class CustomDaoHqlInfo {
    /** HQL 字符串（字面量直接取值；变量引用或拼接时为拼接结果）。 */
    private final @NotNull String hql;
    /** hql 是否为纯字面量（true=直接字面量；false=变量引用或拼接）。 */
    private final boolean literalHql;
    /** 从 HQL 的 FROM 子句提取的实体类名（驼峰标识符）；null 表示未提取到。 */
    private final @Nullable String entityClassName;
    /** 参数列表（以 HQL 中 :paramName 为主，值从方法参数中按名匹配）。 */
    private final @NotNull List<HqlParameter> parameters;
    /** HQL 所在 PsiMethod。 */
    private final @NotNull PsiMethod method;
    /** 参数传递方式（name-value 交替 / Map / 位置参数 / 无额外参数）。 */
    private final @NotNull ParamSource paramSource;

    private final @Nullable DaoQueryKind kind;

    public CustomDaoHqlInfo(@NotNull String hql, boolean isLiteralHql, @Nullable String entityClassName, @NotNull List<HqlParameter> parameters, @NotNull PsiMethod method, @NotNull ParamSource paramSource) {
        this(hql, isLiteralHql, entityClassName, parameters, method, paramSource, DaoQueryKind.HQL);
    }

    public CustomDaoHqlInfo(@NotNull String hql, boolean isLiteralHql, @Nullable String entityClassName, @NotNull List<HqlParameter> parameters, @NotNull PsiMethod method, @NotNull ParamSource paramSource, @Nullable DaoQueryKind kind) {

        this.hql = hql;
        this.literalHql = isLiteralHql;
        this.entityClassName = entityClassName;
        this.parameters = parameters;
        this.method = method;
        this.paramSource = paramSource;
        this.kind = kind;
    }

    public @NotNull String getHql() { return hql; }
    public @NotNull String hql() { return hql; }
    public boolean isLiteralHql() { return literalHql; }
    public @Nullable String getEntityClassName() { return entityClassName; }
    public @Nullable String entityClassName() { return entityClassName; }
    public @NotNull List<HqlParameter> getParameters() { return parameters; }
    public @NotNull List<HqlParameter> parameters() { return parameters; }
    public @NotNull PsiMethod getMethod() { return method; }
    public @NotNull PsiMethod method() { return method; }
    public @NotNull ParamSource getParamSource() { return paramSource; }
    public @Nullable DaoQueryKind getKind() { return kind; }
    public @Nullable DaoQueryKind kind() { return kind; }
    public @NotNull ParamSource paramSource() { return paramSource; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CustomDaoHqlInfo)) return false;
        CustomDaoHqlInfo that = (CustomDaoHqlInfo) o;
        return java.util.Objects.equals(this.hql, that.hql) && java.util.Objects.equals(this.literalHql, that.literalHql) && java.util.Objects.equals(this.entityClassName, that.entityClassName) && java.util.Objects.equals(this.parameters, that.parameters) && java.util.Objects.equals(this.method, that.method) && java.util.Objects.equals(this.paramSource, that.paramSource);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(hql, literalHql, entityClassName, parameters, method, paramSource);
    }

    @Override
    public String toString() {
        return "CustomDaoHqlInfo[" + "hql=" + hql + ", " + "isLiteralHql=" + literalHql + ", " + "entityClassName=" + entityClassName + ", " + "parameters=" + parameters + ", " + "method=" + method + ", " + "paramSource=" + paramSource + "]";
    }
}
