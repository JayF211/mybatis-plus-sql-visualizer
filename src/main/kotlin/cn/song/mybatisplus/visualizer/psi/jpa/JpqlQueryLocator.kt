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

    /**
     * SD7（M44 止血）：Spring Data JDBC / R2DBC 的 @Query 全限定名。
     *
     * javap 实证（spring-data-jdbc 4.1.1 / spring-data-r2dbc 4.1.1）：两者均**无 nativeQuery 属性**
     * （jdbc 版属性 = value/name/rowMapper 族，r2dbc 版仅 value），查询体本就是原生 SQL——
     * 此前短名 "Query" 兜底命中后被当 JPQL 喂 JpqlConverter，`SELECT * FROM ...` 等非 JPQL 语法
     * 产出错误结果（矩阵 §1.3 最危险假阳性链路）。识别后 [JpqlQueryInfo.isNative] 置 true，
     * 使 previewJpql 走**原生透传分支**（convertNativeSqlParameters 命名参数替换），不做 JPQL 转换。
     */
    private val NATIVE_QUERY_ANNOTATIONS: Set<String> = setOf(
        "org.springframework.data.jdbc.repository.query.Query",
        "org.springframework.data.r2dbc.repository.Query",
    )

    /** SD7：JDBC/R2DBC @Query 的 FQN 前缀（qualifiedName 仅在注解类可解析时可靠，前缀兜底 import 场景）。 */
    private val NATIVE_QUERY_FQN_PREFIXES: List<String> = listOf(
        "org.springframework.data.jdbc.repository.query.Query",
        "org.springframework.data.r2dbc.repository.Query",
    )

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
     * SD7（M44）：JDBC/R2DBC @Query 识别为原生 SQL（[isNativeQueryAnnotation]），
     * isNative 置 true 走原生透传分支；nativeQuery 属性仅在 JPA @Query 上读取。
     */
    private fun locateFromMethod(method: PsiMethod): JpqlQueryInfo? {
        if (!hasQueryAnnotation(method)) return null

        val containingClass = method.containingClass ?: return null
        val queryAnnotation = findQueryAnnotation(method) ?: return null

        val jpql = extractJpql(queryAnnotation) ?: return null
        val isNative = isNativeQueryAnnotation(queryAnnotation) || isNativeQuery(queryAnnotation)
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
     * SD7（M44）：注解是否为 Spring Data JDBC / R2DBC @Query（原生 SQL 语义）。
     *
     * 判定路径：
     *  1. qualifiedName 精确 / 前缀匹配（注解类可解析或注解以全限定名书写时可靠）；
     *  2. 兜底：宿主文件的 import 语句含 JDBC/R2DBC Query FQN（`@Query` 短名书写 +
     *     注解类不可解析的测试/跨模块场景——qualifiedName 退化为短名文本，只能靠 import 定包）。
     */
    private fun isNativeQueryAnnotation(annotation: PsiAnnotation): Boolean {
        val qualifiedName = annotation.qualifiedName ?: return hasNativeQueryImport(annotation)
        if (qualifiedName in NATIVE_QUERY_ANNOTATIONS) return true
        if (NATIVE_QUERY_FQN_PREFIXES.any { qualifiedName.startsWith(it) }) return true
        // 短名兜底场景（qualifiedName 无包名或与 JPA Query 不匹配时按 import 判定）
        if (qualifiedName.substringAfterLast('.') != "Query") return false
        return hasNativeQueryImport(annotation)
    }

    /** 宿主文件 import 列表是否引入 JDBC/R2DBC @Query（短名兜底场景的包归属判定）。 */
    private fun hasNativeQueryImport(annotation: PsiAnnotation): Boolean {
        val javaFile = annotation.containingFile as? com.intellij.psi.PsiJavaFile ?: return false
        val imports = javaFile.importList?.allImportStatements ?: return false
        return imports.filterIsInstance<com.intellij.psi.PsiImportStatement>()
            .any { it.qualifiedName in NATIVE_QUERY_ANNOTATIONS }
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
