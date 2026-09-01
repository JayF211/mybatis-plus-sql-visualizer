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
public record DerivedMethodInfo(
        /** 方法名（如 `findByUsername`）。 */
        @NotNull String methodName,
        /** 所属 Repository 接口。 */
        @NotNull PsiClass repositoryInterface,
        /** 解析出的实体类（泛型参数推断；解析失败时为 null）。 */
        @Nullable PsiClass entityClass,
        /** PsiMethod 本身。 */
        @NotNull PsiMethod method
) {
}
