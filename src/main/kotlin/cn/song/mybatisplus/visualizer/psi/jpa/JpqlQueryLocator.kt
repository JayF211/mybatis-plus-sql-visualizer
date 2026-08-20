package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil

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
data class JpqlQueryInfo(
    val methodName: String,
    val repositoryInterface: PsiClass,
    val entityClass: PsiClass?,
    val jpql: String,
    val isNative: Boolean,
    val queryName: String?,
    val method: PsiMethod,
)

/**
 * Spring Data JPA @Query 注解定位器（F-JPA-02）。
 *
 * 从光标位置定位 Repository 接口中带有 `@Query` 注解的方法，提取 JPQL 字符串与实体上下文。
 *
 * 识别逻辑：
 *  1. 从光标位置找到 [PsiMethod]
 *  2. 检查方法是否标注 `@Query` 注解（`org.springframework.data.jpa.repository.Query`）
 *  3. 提取 `value` 属性中的 JPQL 字符串
 *  4. 检测 `nativeQuery = true`（原生 SQL，直接展示无需转换）
 *  5. 提取 `name` 属性（命名查询引用）
 *  6. 从 Repository 接口泛型参数解析实体类
 */
object JpqlQueryLocator {

    /** Spring Data @Query 注解全限定名。 */
    private const val QUERY_ANNOTATION = "org.springframework.data.jpa.repository.Query"

    // ------------------------------------------------------------------
    // 公开 API
    // ------------------------------------------------------------------

    /**
     * 从光标位置定位 @Query 注解方法。
     *
     * @param file   目标 PSI 文件
     * @param offset 光标偏移量
     * @return JpqlQueryInfo 或 null（如果不是 @Query 注解方法）
     */
    fun locate(file: PsiFile, offset: Int): JpqlQueryInfo? {
        val element = file.findElementAt(offset) ?: return null
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return null
        return locateFromMethod(method)
    }

    /**
     * 判断方法是否标注 @Query 注解。
     *
     * 优先全限定名匹配；LightPlatform 测试环境注解类不可解析时按短名兜底。
     *
     * @param method 目标方法
     * @return 是否标注 @Query 注解
     */
    fun hasQueryAnnotation(method: PsiMethod): Boolean {
        val annotations = method.modifierList.annotations
        for (annotation in annotations) {
            val qualifiedName = annotation.qualifiedName
            if (qualifiedName != null && qualifiedName == QUERY_ANNOTATION) return true
            // 短名兜底（LightPlatform 测试环境）
            val shortName = annotation.nameReferenceElement?.referenceName
            if (shortName == "Query") return true
        }
        return false
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 从指定方法定位 @Query 查询信息。
     *
     * 完整校验链：是否为 @Query 注解方法 + 提取属性。
     */
    private fun locateFromMethod(method: PsiMethod): JpqlQueryInfo? {
        if (!hasQueryAnnotation(method)) return null

        val containingClass = method.containingClass ?: return null
        val queryAnnotation = findQueryAnnotation(method) ?: return null

        val jpql = extractJpql(queryAnnotation) ?: return null
        val isNative = isNativeQuery(queryAnnotation)
        val queryName = extractQueryName(queryAnnotation)
        val entityClass = DerivedMethodLocator.resolveEntityClass(containingClass)

        return JpqlQueryInfo(
            methodName = method.name,
            repositoryInterface = containingClass,
            entityClass = entityClass,
            jpql = jpql,
            isNative = isNative,
            queryName = queryName,
            method = method,
        )
    }

    /**
     * 查找 @Query 注解实例。
     */
    private fun findQueryAnnotation(method: PsiMethod): PsiAnnotation? {
        val annotations = method.modifierList.annotations
        for (annotation in annotations) {
            val qualifiedName = annotation.qualifiedName
            if (qualifiedName != null && qualifiedName == QUERY_ANNOTATION) return annotation
            // 短名兜底
            val shortName = annotation.nameReferenceElement?.referenceName
            if (shortName == "Query") return annotation
        }
        return null
    }

    /**
     * 提取 @Query 注解的 value 属性（JPQL 字符串）。
     */
    private fun extractJpql(annotation: PsiAnnotation): String? {
        val memberValue = annotation.findAttributeValue("value") ?: return null
        return extractStringValue(memberValue)
    }

    /**
     * 检测是否为原生 SQL 查询（`nativeQuery = true`）。
     */
    private fun isNativeQuery(annotation: PsiAnnotation): Boolean {
        val memberValue = annotation.findAttributeValue("nativeQuery") ?: return false
        val literal = memberValue as? PsiLiteralExpression ?: return false
        val value = literal.value as? Boolean ?: return false
        return value
    }

    /**
     * 提取 @Query 注解的 name 属性（命名查询引用）。
     */
    private fun extractQueryName(annotation: PsiAnnotation): String? {
        val memberValue = annotation.findAttributeValue("name") ?: return null
        return extractStringValue(memberValue)
    }

    /**
     * 从注解属性值中提取字符串。
     * 支持：
     *  - 单字符串字面量：@Query("SELECT ...")
     *  - 字符串拼接：@Query("SELECT ..." + "FROM ...")（Java 编译时常量折叠前的形式）
     */
    private fun extractStringValue(memberValue: PsiAnnotationMemberValue): String? {
        // 路径 1：直接字面量
        val literal = memberValue as? PsiLiteralExpression
        if (literal != null) {
            val value = literal.value as? String ?: return null
            return value.takeIf { it.isNotBlank() }
        }
        // 路径 2：字符串拼接表达式——递归收集所有 PsiLiteralExpression 子节点的字符串值
        val parts = collectStringLiterals(memberValue)
        if (parts.isNotEmpty()) return parts.joinToString("")
        return null
    }

    /**
     * 递归收集 PSI 树中所有 PsiLiteralExpression 的字符串值。
     * 用于处理 @Query("..." + "...") 拼接表达式。
     */
    private fun collectStringLiterals(element: com.intellij.psi.PsiElement): List<String> {
        if (element is PsiLiteralExpression) {
            val value = element.value as? String ?: return emptyList()
            return listOf(value)
        }
        return element.children.flatMap { collectStringLiterals(it) }
    }
}
