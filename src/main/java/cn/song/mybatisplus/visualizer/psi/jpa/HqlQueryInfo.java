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
public record HqlQueryInfo(
        /** HQL 所在方法名。 */
        @NotNull String methodName,
        /** HQL 字符串。 */
        @NotNull String hql,
        /** hql 是否为字面量。 */
        boolean isLiteralHql,
        /** 返回实体类（createQuery(Xxx.class) 解析）；null 表示未指定。 */
        @Nullable PsiClass entityClass,
        /** 返回实体类名（可解析时非 null）。 */
        @Nullable String entityClassName,
        /** 参数设置列表（setParameter/setInteger 等）。 */
        @NotNull List<HqlParameter> parameters,
        /** HQL 所在 PsiMethod。 */
        @NotNull PsiMethod method,
        /** 是否为原生 SQL（createNativeQuery）。 */
        boolean isNativeQuery,
        /** createQuery/createNamedQuery/createNativeQuery 方法名。 */
        @NotNull String createQueryMethodName
) {
}
