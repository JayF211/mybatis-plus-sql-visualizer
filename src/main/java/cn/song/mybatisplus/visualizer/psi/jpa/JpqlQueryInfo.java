package cn.song.mybatisplus.visualizer.psi.jpa;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Spring Data JPA @Query 注解的 JPQL 查询信息。
 *
 * @param methodName          方法名（如 `findByUsername`）
 * @param repositoryInterface 所属 Repository 接口
 * @param entityClass         解析出的实体类（泛型参数推断；解析失败时为 null）
 * @param jpql                @Query 注解中 value 属性的 JPQL 字符串
 * @param isNative            是否为原生 SQL（`nativeQuery = true`）
 * @param queryName           命名查询引用（`name` 属性），null 表示未指定
 * @param method              PsiMethod 本身
 */
public final class JpqlQueryInfo {
    /** 方法名（如 `findByUsername`）。 */
    private final @NotNull String methodName;
    /** 所属 Repository 接口。 */
    private final @NotNull PsiClass repositoryInterface;
    /** 解析出的实体类（泛型参数推断；解析失败时为 null）。 */
    private final @Nullable PsiClass entityClass;
    /** @Query 注解中 value 属性的 JPQL 字符串。 */
    private final @NotNull String jpql;
    /** 是否为原生 SQL（`nativeQuery = true`）。 */
    private final boolean native_;
    /** 命名查询引用（`name` 属性），null 表示未指定。 */
    private final @Nullable String queryName;
    /** PsiMethod 本身。 */
    private final @NotNull PsiMethod method;

    public JpqlQueryInfo(@NotNull String methodName, @NotNull PsiClass repositoryInterface, @Nullable PsiClass entityClass, @NotNull String jpql, boolean isNative, @Nullable String queryName, @NotNull PsiMethod method) {
        this.methodName = methodName;
        this.repositoryInterface = repositoryInterface;
        this.entityClass = entityClass;
        this.jpql = jpql;
        this.native_ = isNative;
        this.queryName = queryName;
        this.method = method;
    }

    public @NotNull String getMethodName() { return methodName; }
    public @NotNull String methodName() { return methodName; }
    public @NotNull PsiClass getRepositoryInterface() { return repositoryInterface; }
    public @NotNull PsiClass repositoryInterface() { return repositoryInterface; }
    public @Nullable PsiClass getEntityClass() { return entityClass; }
    public @Nullable PsiClass entityClass() { return entityClass; }
    public @NotNull String getJpql() { return jpql; }
    public @NotNull String jpql() { return jpql; }
    public boolean isNative() { return native_; }
    public @Nullable String getQueryName() { return queryName; }
    public @Nullable String queryName() { return queryName; }
    public @NotNull PsiMethod getMethod() { return method; }
    public @NotNull PsiMethod method() { return method; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpqlQueryInfo)) return false;
        JpqlQueryInfo that = (JpqlQueryInfo) o;
        return java.util.Objects.equals(this.methodName, that.methodName) && java.util.Objects.equals(this.repositoryInterface, that.repositoryInterface) && java.util.Objects.equals(this.entityClass, that.entityClass) && java.util.Objects.equals(this.jpql, that.jpql) && java.util.Objects.equals(this.native_, that.native_) && java.util.Objects.equals(this.queryName, that.queryName) && java.util.Objects.equals(this.method, that.method);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(methodName, repositoryInterface, entityClass, jpql, native_, queryName, method);
    }

    @Override
    public String toString() {
        return "JpqlQueryInfo[" + "methodName=" + methodName + ", " + "repositoryInterface=" + repositoryInterface + ", " + "entityClass=" + entityClass + ", " + "jpql=" + jpql + ", " + "isNative=" + native_ + ", " + "queryName=" + queryName + ", " + "method=" + method + "]";
    }
}
