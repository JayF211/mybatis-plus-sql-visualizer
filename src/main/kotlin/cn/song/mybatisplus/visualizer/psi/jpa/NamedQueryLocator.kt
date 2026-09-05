package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.search.GlobalSearchScope

/**
 * JPA 命名查询信息（@NamedQuery / @NamedQueries 产物）。
 *
 * @param name       查询名（如 "UserEntity.findByUsernameNamed"）
 * @param query      JPQL 查询字符串
 * @param annotation 该命名查询所在的 @NamedQuery 注解（用于"光标下的那一条"定位 / 反查实体类）
 */
data class NamedQueryInfo(
    val name: String,
    val query: String,
    val annotation: PsiAnnotation? = null,
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

    /** @NamedQuery 注解全限定名（javax 老命名空间）。 */
    private const val NAMED_QUERY_JAVAX = "javax.persistence.NamedQuery"

    /** @NamedQuery 注解全限定名（jakarta 新命名空间，T-J12 双支持）。 */
    private const val NAMED_QUERY_JAKARTA = "jakarta.persistence.NamedQuery"

    /** @NamedQueries 容器注解全限定名（javax 老命名空间）。 */
    private const val NAMED_QUERIES_JAVAX = "javax.persistence.NamedQueries"

    /** @NamedQueries 容器注解全限定名（jakarta 新命名空间，T-J12 双支持）。 */
    private const val NAMED_QUERIES_JAKARTA = "jakarta.persistence.NamedQueries"

    /** @NamedQuery 全限定名集合（javax + jakarta）。 */
    private val NAMED_QUERY_FQNS: Set<String> = setOf(NAMED_QUERY_JAVAX, NAMED_QUERY_JAKARTA)

    /** @NamedQueries 全限定名集合（javax + jakarta）。 */
    private val NAMED_QUERIES_FQNS: Set<String> = setOf(NAMED_QUERIES_JAVAX, NAMED_QUERIES_JAKARTA)

    /** 容器注解短名（LightPlatform 测试环境 / 粘贴片段下 FQN 不可解析时兜底）。 */
    private const val NAMED_QUERIES_SHORT_NAME = "NamedQueries"

    /** 单注解短名。 */
    private const val NAMED_QUERY_SHORT_NAME = "NamedQuery"

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
            when (resolveAnnotationKind(annotation)) {
                AnnotationKind.NAMED_QUERIES -> extractNamedQueriesContainer(annotation, results)
                AnnotationKind.NAMED_QUERY -> extractNamedQuery(annotation, results)
                null -> {}
            }
        }
        return results
    }

    /**
     * 按命名查询名查找（T-PreviewShortcut：`session.createNamedQuery("name")` 跨文件按名回查）。
     *
     * JPA 规范命名查询名默认即 `<实体简单名>.<方法名>`（如 `"UserEntity.findByUsernameNamed"`），
     * 取 `.` 前缀解析实体类，再由 [locate] 按名字精确匹配。解析不到时返回 null（调用方优雅降级）。
     *
     * @param project   项目
     * @param queryName 命名查询名
     * @return 命中的命名查询（annotation 字段已回填，可反查实体类）；未命中返回 null
     */
    fun findByQueryName(project: Project, queryName: String?): NamedQueryInfo? {
        if (queryName == null || queryName.isBlank()) return null
        val entitySimpleName = queryName.substringBefore('.', "").trim()
        if (entitySimpleName.isBlank()) return null
        val classes = runCatching {
            JavaPsiFacade.getInstance(project).findClasses(entitySimpleName, GlobalSearchScope.projectScope(project))
        }.getOrNull() ?: return null
        for (cls in classes) {
            val hit = locate(cls).firstOrNull { it.name == queryName }
            if (hit != null) return hit
        }
        return null
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
        results += NamedQueryInfo(name = name, query = query, annotation = annotation)
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

    /** 识别出的命名查询注解类别。 */
    private enum class AnnotationKind { NAMED_QUERY, NAMED_QUERIES }

    /**
     * 解析注解类别（T-J12：javax / jakarta 双 FQN + 短名兜底，沿用 JpaEntityResolver 惯例）。
     *
     * 匹配顺序：
     *  1. qualifiedName 精确匹配 javax / jakarta 全限定名；
     *  2. 兜底：短名匹配（LightPlatform 测试环境 / 粘贴片段下注解类不可解析，qualifiedName 为 null
     *     或为同文件裸短名，用 nameReferenceElement.referenceName 兜底——与旧短名行为保持一致）。
     *
     * @param annotation 注解实例
     * @return 注解类别；非命名查询注解返回 null
     */
    private fun resolveAnnotationKind(annotation: PsiAnnotation): AnnotationKind? {
        val qualifiedName = annotation.qualifiedName
        if (qualifiedName != null) {
            // 优先全限定名精确匹配
            if (qualifiedName in NAMED_QUERY_FQNS) return AnnotationKind.NAMED_QUERY
            if (qualifiedName in NAMED_QUERIES_FQNS) return AnnotationKind.NAMED_QUERIES
        }
        // FQN 不匹配（不可解析 / 短名形态）→ 按短名兜底
        return when (annotation.nameReferenceElement?.referenceName) {
            NAMED_QUERY_SHORT_NAME -> AnnotationKind.NAMED_QUERY
            NAMED_QUERIES_SHORT_NAME -> AnnotationKind.NAMED_QUERIES
            else -> null
        }
    }
}
