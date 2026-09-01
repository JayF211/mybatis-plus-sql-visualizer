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
public record CustomDaoHqlInfo(
        /** HQL 字符串（字面量直接取值；变量引用或拼接时为拼接结果）。 */
        @NotNull String hql,
        /** hql 是否为纯字面量（true=直接字面量；false=变量引用或拼接）。 */
        boolean isLiteralHql,
        /** 从 HQL 的 FROM 子句提取的实体类名（驼峰标识符）；null 表示未提取到。 */
        @Nullable String entityClassName,
        /** 参数列表（以 HQL 中 :paramName 为主，值从方法参数中按名匹配）。 */
        @NotNull List<HqlParameter> parameters,
        /** HQL 所在 PsiMethod。 */
        @NotNull PsiMethod method,
        /** 参数传递方式（name-value 交替 / Map / 位置参数 / 无额外参数）。 */
        @NotNull ParamSource paramSource
) {
}
