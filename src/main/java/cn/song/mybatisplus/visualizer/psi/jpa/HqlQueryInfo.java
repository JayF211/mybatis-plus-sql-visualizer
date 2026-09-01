package cn.song.mybatisplus.visualizer.psi.jpa;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Hibernate HQL 查询信息（HqlQueryLocator 产物）。
 *
 * @param methodName      HQL 所在方法名
 * @param hql             HQL 字符串（字面量直接取值；变量引用时为变量名）
 * @param isLiteralHql    hql 是否为字面量（true=已提取到字符串；false=变量引用，需运行时确定）
 * @param entityClass     返回实体类（createQuery(Xxx.class) 解析）；null 表示未指定
 * @param entityClassName 返回实体类名（可解析时非 null）
 * @param parameters      参数设置列表（setParameter/setInteger 等）
 * @param method          HQL 所在 PsiMethod
 * @param isNativeQuery   是否为原生 SQL（createNativeQuery）
 * @param createQueryMethodName createQuery/createNamedQuery/createNativeQuery 方法名
 */
public final class HqlQueryInfo {
    /** HQL 所在方法名。 */
    private final @NotNull String methodName;
    /** HQL 字符串。 */
    private final @NotNull String hql;
    /** hql 是否为字面量。 */
    private final boolean literalHql;
    /** 返回实体类（createQuery(Xxx.class) 解析）；null 表示未指定。 */
    private final @Nullable PsiClass entityClass;
    /** 返回实体类名（可解析时非 null）。 */
    private final @Nullable String entityClassName;
    /** 参数设置列表（setParameter/setInteger 等）。 */
    private final @NotNull List<HqlParameter> parameters;
    /** HQL 所在 PsiMethod。 */
    private final @NotNull PsiMethod method;
    /** 是否为原生 SQL（createNativeQuery）。 */
    private final boolean nativeQuery;
    /** createQuery/createNamedQuery/createNativeQuery 方法名。 */
    private final @NotNull String createQueryMethodName;

    public HqlQueryInfo(@NotNull String methodName, @NotNull String hql, boolean isLiteralHql, @Nullable PsiClass entityClass, @Nullable String entityClassName, @NotNull List<HqlParameter> parameters, @NotNull PsiMethod method, boolean isNativeQuery, @NotNull String createQueryMethodName) {
        this.methodName = methodName;
        this.hql = hql;
        this.literalHql = isLiteralHql;
        this.entityClass = entityClass;
        this.entityClassName = entityClassName;
        this.parameters = parameters;
        this.method = method;
        this.nativeQuery = isNativeQuery;
        this.createQueryMethodName = createQueryMethodName;
    }

    public @NotNull String getMethodName() { return methodName; }
    public @NotNull String methodName() { return methodName; }
    public @NotNull String getHql() { return hql; }
    public @NotNull String hql() { return hql; }
    public boolean isLiteralHql() { return literalHql; }
    public @Nullable PsiClass getEntityClass() { return entityClass; }
    public @Nullable PsiClass entityClass() { return entityClass; }
    public @Nullable String getEntityClassName() { return entityClassName; }
    public @Nullable String entityClassName() { return entityClassName; }
    public @NotNull List<HqlParameter> getParameters() { return parameters; }
    public @NotNull List<HqlParameter> parameters() { return parameters; }
    public @NotNull PsiMethod getMethod() { return method; }
    public @NotNull PsiMethod method() { return method; }
    public boolean isNativeQuery() { return nativeQuery; }
    public @NotNull String getCreateQueryMethodName() { return createQueryMethodName; }
    public @NotNull String createQueryMethodName() { return createQueryMethodName; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HqlQueryInfo)) return false;
        HqlQueryInfo that = (HqlQueryInfo) o;
        return java.util.Objects.equals(this.methodName, that.methodName) && java.util.Objects.equals(this.hql, that.hql) && java.util.Objects.equals(this.literalHql, that.literalHql) && java.util.Objects.equals(this.entityClass, that.entityClass) && java.util.Objects.equals(this.entityClassName, that.entityClassName) && java.util.Objects.equals(this.parameters, that.parameters) && java.util.Objects.equals(this.method, that.method) && java.util.Objects.equals(this.nativeQuery, that.nativeQuery) && java.util.Objects.equals(this.createQueryMethodName, that.createQueryMethodName);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(methodName, hql, literalHql, entityClass, entityClassName, parameters, method, nativeQuery, createQueryMethodName);
    }

    @Override
    public String toString() {
        return "HqlQueryInfo[" + "methodName=" + methodName + ", " + "hql=" + hql + ", " + "isLiteralHql=" + literalHql + ", " + "entityClass=" + entityClass + ", " + "entityClassName=" + entityClassName + ", " + "parameters=" + parameters + ", " + "method=" + method + ", " + "isNativeQuery=" + nativeQuery + ", " + "createQueryMethodName=" + createQueryMethodName + "]";
    }
}
