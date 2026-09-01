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
public record JpqlQueryInfo(
        /** 方法名（如 `findByUsername`）。 */
        @NotNull String methodName,
        /** 所属 Repository 接口。 */
        @NotNull PsiClass repositoryInterface,
        /** 解析出的实体类（泛型参数推断；解析失败时为 null）。 */
        @Nullable PsiClass entityClass,
        /** @Query 注解中 value 属性的 JPQL 字符串。 */
        @NotNull String jpql,
        /** 是否为原生 SQL（`nativeQuery = true`）。 */
        boolean isNative,
        /** 命名查询引用（`name` 属性），null 表示未指定。 */
        @Nullable String queryName,
        /** PsiMethod 本身。 */
        @NotNull PsiMethod method
) {
}
