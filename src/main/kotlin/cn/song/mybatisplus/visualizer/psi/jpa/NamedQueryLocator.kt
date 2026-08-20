package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiLiteralExpression

/**
 * JPA 命名查询信息（@NamedQuery / @NamedQueries 产物）。
 *
 * @param name   查询名（如 "UserEntity.findByUsernameNamed"）
 * @param query  JPQL 查询字符串
 */
data class NamedQueryInfo(
    val name: String,
    val query: String,
)

/**
 * JPA @NamedQuery / @NamedQueries 注解定位器（F-JPA-02 / P2-1）。
 *
 * 从实体类上定位 @NamedQuery / @NamedQueries 注解，提取命名查询的 name 和 JPQL 字符串。
 *
 * 识别模式：
 *  - `@NamedQuery(name="xxx", query="JPQL")`：单一定位，提取 name 与 query 属性。
 *  - `@NamedQueries({@NamedQuery(...), @NamedQuery(...)})`：容器注解，遍历 value 数组提取。
 *
 * 解析策略：
 *  1. 遍历实体类 modifierList 上的全部注解；
 *  2. 按短名（NamedQuery / NamedQueries）匹配——LightPlatform 测试环境下注解类不可解析时
 *     qualifiedName 为 null，按 referenceName 兜底；
 *  3. 对 @NamedQueries 容器注解，取 value 属性（PsiArrayInitializerMemberValue），遍历子注解；
 *  4. 对 @NamedQuery 注解，取 name / query 属性（PsiLiteralExpression 字符串值）。
 */
object NamedQueryLocator {

    /** @NamedQuery 注解全限定名。 */
    private const val NAMED_QUERY = "javax.persistence.NamedQuery"

    /** @NamedQueries 容器注解全限定名。 */
    private const val NAMED_QUERIES = "javax.persistence.NamedQueries"

    // ------------------------------------------------------------------
    // 公开 API
    // ------------------------------------------------------------------

    /**
     * 从实体类提取全部 @NamedQuery / @NamedQueries 命名查询。
     *
     * 同时扫描 @NamedQueries 容器与独立 @NamedQuery 注解（JPA 规范允许两种写法并存）。
     *
     * @param entityClass 实体类 PsiClass
     * @return 命名查询信息列表；无注解时返回空列表
     */
    fun locate(entityClass: PsiClass): List<NamedQueryInfo> {
        val results = mutableListOf<NamedQueryInfo>()
        for (annotation in entityClass.modifierList?.annotations ?: emptyArray()) {
            val simpleName = resolveAnnotationSimpleName(annotation) ?: continue
            when (simpleName) {
                "NamedQueries" -> extractNamedQueriesContainer(annotation, results)
                "NamedQuery" -> extractNamedQuery(annotation, results)
            }
        }
        return results
    }

    // ------------------------------------------------------------------
    // 容器注解解析
    // ------------------------------------------------------------------

    /**
     * 从 @NamedQueries 容器注解中提取子 @NamedQuery 列表。
     *
     * @param annotation @NamedQueries 注解实例
     * @param results   输出列表（原地追加）
     */
    private fun extractNamedQueriesContainer(
        annotation: PsiAnnotation,
        results: MutableList<NamedQueryInfo>,
    ) {
        val valueAttr = annotation.findAttributeValue("value") ?: return
        // value 属性是数组初始化器：{ @NamedQuery(...), @NamedQuery(...) }
        if (valueAttr is PsiArrayInitializerMemberValue) {
            for (element in valueAttr.initializers) {
                (element as? PsiAnnotation)?.let { extractNamedQuery(it, results) }
            }
        } else {
            // 单元素退化形态：@NamedQueries(@NamedQuery(...))（JPA 允许单值省略花括号）
            (valueAttr as? PsiAnnotation)?.let { extractNamedQuery(it, results) }
        }
    }

    // ------------------------------------------------------------------
    // 单注解解析
    // ------------------------------------------------------------------

    /**
     * 从单个 @NamedQuery 注解提取 name / query 属性并追加到结果列表。
     *
     * @param annotation @NamedQuery 注解实例
     * @param results   输出列表（原地追加）
     */
    private fun extractNamedQuery(
        annotation: PsiAnnotation,
        results: MutableList<NamedQueryInfo>,
    ) {
        val name = extractStringAttribute(annotation, "name") ?: return
        val query = extractStringAttribute(annotation, "query") ?: return
        if (name.isBlank() || query.isBlank()) return
        results += NamedQueryInfo(name = name, query = query)
    }

    // ------------------------------------------------------------------
    // 属性提取
    // ------------------------------------------------------------------

    /**
     * 从注解实例提取指定字符串属性的值。
     *
     * 支持 PsiLiteralExpression 字面量与纯文本回退（注解类不可解析时 literal 类型可能缺失）。
     *
     * @param annotation 注解实例
     * @param attrName   属性名
     * @return 字符串值；属性缺失或值空白时返回 null
     */
    private fun extractStringAttribute(annotation: PsiAnnotation, attrName: String): String? {
        val memberValue = annotation.findAttributeValue(attrName) ?: return null
        return extractStringValue(memberValue)
    }

    /**
     * 从注解属性值中提取字符串文本。
     *
     * 优先 PsiLiteralExpression 取值；不可用时回退去引号文本。
     */
    private fun extractStringValue(memberValue: PsiAnnotationMemberValue): String? {
        // 路径 1：PsiLiteralExpression 直接取值（LightPlatform 常见形态）
        val literal = memberValue as? PsiLiteralExpression
        if (literal != null) {
            val value = literal.value as? String
            return value?.takeIf { it.isNotBlank() }
        }
        // 路径 2：纯文本回退（去掉字面量引号）
        val text = memberValue.text?.trim() ?: return null
        val unquoted = if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) {
            text.substring(1, text.length - 1)
        } else {
            text
        }
        return unquoted.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------------
    // 注解名解析
    // ------------------------------------------------------------------

    /**
     * 解析注解的短名（simpleName）。
     *
     * 优先 qualifiedName 截取（完整 IDE）；LightPlatform 测试环境下 qualifiedName 可能为 null，
     * 回退使用 nameReferenceElement.referenceName（如 NamedQuery / NamedQueries / NamedEntityGraph 等）。
     *
     * @param annotation 注解实例
     * @return 短名；无法解析返回 null
     */
    private fun resolveAnnotationSimpleName(annotation: PsiAnnotation): String? {
        annotation.qualifiedName?.let { return it.substringAfterLast('.') }
        return annotation.nameReferenceElement?.referenceName
    }
}
