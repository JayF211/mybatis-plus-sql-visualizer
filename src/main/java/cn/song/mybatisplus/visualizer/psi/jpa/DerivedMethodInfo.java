package cn.song.mybatisplus.visualizer.psi.jpa;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Spring Data Repository 推导方法信息。
 *
 * @param methodName          方法名（如 `findByUsername`）
 * @param repositoryInterface 所属 Repository 接口
 * @param entityClass         解析出的实体类（泛型参数推断；解析失败时为 null）
 * @param method              PsiMethod 本身
 */
public final class DerivedMethodInfo {
    /** 方法名（如 `findByUsername`）。 */
    private final @NotNull String methodName;
    /** 所属 Repository 接口。 */
    private final @NotNull PsiClass repositoryInterface;
    /** 解析出的实体类（泛型参数推断；解析失败时为 null）。 */
    private final @Nullable PsiClass entityClass;
    /** PsiMethod 本身。 */
    private final @NotNull PsiMethod method;

    public DerivedMethodInfo(@NotNull String methodName, @NotNull PsiClass repositoryInterface, @Nullable PsiClass entityClass, @NotNull PsiMethod method) {
        this.methodName = methodName;
        this.repositoryInterface = repositoryInterface;
        this.entityClass = entityClass;
        this.method = method;
    }

    public @NotNull String getMethodName() { return methodName; }
    public @NotNull String methodName() { return methodName; }
    public @NotNull PsiClass getRepositoryInterface() { return repositoryInterface; }
    public @NotNull PsiClass repositoryInterface() { return repositoryInterface; }
    public @Nullable PsiClass getEntityClass() { return entityClass; }
    public @Nullable PsiClass entityClass() { return entityClass; }
    public @NotNull PsiMethod getMethod() { return method; }
    public @NotNull PsiMethod method() { return method; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DerivedMethodInfo)) return false;
        DerivedMethodInfo that = (DerivedMethodInfo) o;
        return java.util.Objects.equals(this.methodName, that.methodName) && java.util.Objects.equals(this.repositoryInterface, that.repositoryInterface) && java.util.Objects.equals(this.entityClass, that.entityClass) && java.util.Objects.equals(this.method, that.method);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(methodName, repositoryInterface, entityClass, method);
    }

    @Override
    public String toString() {
        return "DerivedMethodInfo[" + "methodName=" + methodName + ", " + "repositoryInterface=" + repositoryInterface + ", " + "entityClass=" + entityClass + ", " + "method=" + method + "]";
    }
}
