package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.util.PsiTreeUtil

/**
 * Spring Data Repository 推导方法信息。
 *
 * @param methodName          方法名（如 `findByUsername`）
 * @param repositoryInterface 所属 Repository 接口
 * @param entityClass         解析出的实体类（泛型参数推断；解析失败时为 null）
 * @param method              PsiMethod 本身
 */
data class DerivedMethodInfo(
    val methodName: String,
    val repositoryInterface: PsiClass,
    val entityClass: PsiClass?,
    val method: PsiMethod,
)

/**
 * Spring Data JPA Repository 推导查询方法定位器（F-JPA-01）。
 *
 * 从光标位置定位 Repository 接口中的推导查询方法（Derived Query Method），
 * 判断是否为可预览的推导方法，并提取方法名与实体类。
 *
 * 识别逻辑：
 *  1. 从光标位置找到 [PsiMethod]
 *  2. 判断 containing class 是否为 Repository 接口（superType 含 Spring Data 基类 / 接口名后缀 Repository）
 *  3. 判断方法是否为推导查询（非 @Query、非 default、非 static、符合 PartTree 前缀；
 *     含 `By` 的标准形态，或 T-J3 放行的无 By 全量/计数形态——后者强制宿主为 Repository 接口）
 *  4. 从返回类型泛型参数解析实体类
 */
object DerivedMethodLocator {

    /** Spring Data Repository 基类全限定名（superType 匹配用）。 */
    private val REPOSITORY_BASE_TYPES: Set<String> = setOf(
        "org.springframework.data.repository.Repository",
        "org.springframework.data.repository.CrudRepository",
        "org.springframework.data.repository.PagingAndSortingRepository",
        "org.springframework.data.jpa.repository.JpaRepository",
        "org.springframework.data.jpa.repository.QueryExecutorAdapter",
    )

    /** Spring Data @Query 注解全限定名（排除用）。 */
    private const val QUERY_ANNOTATION = "org.springframework.data.jpa.repository.Query"

    /** PartTree 推导查询方法名前缀（Spring Data JPA 官方支持；T-J3 补 query/search/stream）。 */
    private val PART_TREE_PREFIXES: Set<String> = setOf(
        "find", "findAll", "read", "get", "count", "exists", "delete", "remove",
        "query", "search", "stream",
    )

    /**
     * T-J3：无 `By` 形态允许的 Subject 关键字（`findAll()` / `readAll()` / `getAll()` /
     * `queryAll()` / `searchAll()` / `streamAll()` / `countAll()` 的词干 + 裸 `count()`）。
     *
     * 仅这些形态可放行「无 By」门控；其余无 By 方法名（getUsername/getOne 等）仍不识别。
     */
    private val NO_BY_SUBJECT_KEYWORDS: Set<String> = setOf(
        "find", "read", "get", "query", "search", "stream", "count",
    )

    /** T-J3：无 `By` 形态的统一 `All` 后缀。 */
    private const val ALL_SUFFIX = "All"

    // ------------------------------------------------------------------
    // 公开 API
    // ------------------------------------------------------------------

    /**
     * 从光标位置定位推导方法。
     *
     * @param file   目标 PSI 文件
     * @param offset 光标偏移量
     * @return DerivedMethodInfo 或 null（如果不是推导方法）
     */
    fun locate(file: PsiFile, offset: Int): DerivedMethodInfo? {
        val element = file.findElementAt(offset) ?: return null
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return null
        return locateFromMethod(method)
    }

    /**
     * 判断方法是否为可预览的推导查询。
     *
     * 条件：
     *  - 非 @Query 注解方法
     *  - 非 default 方法
     *  - 非 static 方法
     *  - 方法名以 PartTree 前缀开头，且满足以下形态之一：
     *    - 含 `By` 关键字（`findByUsername` / `streamByLastName` 等，T-J3 扩展 query/search/stream 前缀）
     *    - 无 `By` 的全量/计数形态（T-J3）：`findAll()` / `readAll()` / `getAll()` 等 `*All` 形态、
     *      裸 `count()`——守卫：宿主**必须是 Repository 接口**（基类识别 + Repository 后缀启发），
     *      非 Repository 类/接口的同名方法绝不识别
     *
     * @param method 目标方法
     * @return 是否为可预览的推导查询
     */
    fun isDerivedQuery(method: PsiMethod): Boolean {
        if (hasQueryAnnotation(method)) return false
        // 排除 default 方法（含方法体，非接口推导）
        if (method.hasModifierProperty("default")) return false
        // 排除 static 方法
        if (method.hasModifierProperty("static")) return false

        val methodName = method.name
        // 方法名必须包含 By 关键字（PartTree 分隔符）
        if (!methodName.contains("By")) {
            // T-J3：无 By 形态放行（findAll/count 等），但强制宿主为 Repository 接口——
            // 普通类的 findAll()/count() 不识别（isDerivedQuery 也做类校验，仅此形态）。
            if (!isNoBySubjectForm(methodName)) return false
            val containingClass = method.containingClass ?: return false
            return isRepositoryInterface(containingClass)
        }
        // 方法名必须以 PartTree 前缀开头
        return PART_TREE_PREFIXES.any { methodName.startsWith(it) }
    }

    /**
     * T-J3：判断方法名是否为无 `By` 的合法 Subject 形态。
     *
     * 合法形态：
     *  - `<queryKeyword>All`：findAll / readAll / getAll / queryAll / searchAll / streamAll / countAll
     *  - 裸 `count`
     */
    private fun isNoBySubjectForm(methodName: String): Boolean {
        if (methodName == "count") return true
        if (!methodName.endsWith(ALL_SUFFIX)) return false
        val stem = methodName.dropLast(ALL_SUFFIX.length)
        return stem in NO_BY_SUBJECT_KEYWORDS
    }

    /**
     * 判断方法是否为**可预览的 Repository 派生查询**（快速路径，供 gutter 图标判定）。
     *
     * 在 [isDerivedQuery]（纯方法名判定，不检查所在类）基础上叠加 `containing class 是
     * Repository 接口` 校验——避免普通类方法（如业务 DAO 手写方法名 `deleteByDbHelper`）被
     * 误判为 JPA 派生查询而显示错误的预览图标 / 触发 previewDerivedQuery 但返回 null。
     *
     * 语义与 [locateFromMethod] 的「Repository 接口 + 派生方法名」双重校验一致，
     * 但只做正例判定、不解析实体，O(1) 快速路径专用；点击后仍走 [locate] 全链路。
     *
     * @param method 目标方法
     * @return 是否为可预览的 Repository 派生查询
     */
    fun isRepositoryMethod(method: PsiMethod): Boolean {
        if (!isDerivedQuery(method)) return false
        val containingClass = method.containingClass ?: return false
        return isRepositoryInterface(containingClass)
    }

    /**
     * 从 Repository 接口提取实体类。
     *
     * 解析策略：
     *  - 从接口的 superType 泛型参数提取（JpaRepository<Entity, ID> → Entity）
     *  - 优先取第一个类型参数（Spring Data 基类泛型约定：第一参数为实体类）
     *
     * @param repositoryInterface Repository 接口 PsiClass
     * @return 实体类 PsiClass，解析失败返回 null
     */
    fun resolveEntityClass(repositoryInterface: PsiClass): PsiClass? {
        // 遍历所有 superType，提取第一个类型参数（实体类）
        for (superType in repositoryInterface.superTypes) {
            val entityClass = extractEntityFromSuperType(superType) ?: continue
            if (entityClass != null) return entityClass
        }
        return null
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 从指定方法定位推导方法信息。
     *
     * 完整校验链：containing class 是否为 Repository 接口 + 方法是否为推导查询。
     */
    private fun locateFromMethod(method: PsiMethod): DerivedMethodInfo? {
        val containingClass = method.containingClass ?: return null
        if (!isRepositoryInterface(containingClass)) return null
        if (!isDerivedQuery(method)) return null

        val entityClass = resolveEntityClass(containingClass)
            ?: resolveEntityFromReturnType(method)

        return DerivedMethodInfo(
            methodName = method.name,
            repositoryInterface = containingClass,
            entityClass = entityClass,
            method = method,
        )
    }

    /**
     * 判断 PsiClass 是否为 Spring Data Repository 接口。
     *
     * 判定策略（双路径）：
     *  1. 精确匹配：superType 全限定名含 Spring Data Repository 基类
     *  2. 启发式：接口名以 `Repository` 结尾（置信度较低，兜底）
     */
    private fun isRepositoryInterface(clazz: PsiClass): Boolean {
        if (!clazz.isInterface) return false

        // 路径 1：superType 精确匹配 Spring Data 基类
        for (superType in clazz.superTypes) {
            val canonicalText = superType.canonicalText ?: continue
            // 取简单类名/全限定名匹配（泛型参数不影响 canonicalText 的类名部分）
            if (REPOSITORY_BASE_TYPES.any { canonicalText.startsWith(it) }) return true
        }

        // 路径 2：启发式——接口名以 Repository 结尾
        val name = clazz.name ?: return false
        return name.endsWith("Repository")
    }

    /**
     * 从 superType 泛型参数提取实体类。
     *
     * JpaRepository<Entity, ID> → 第一个类型参数 Entity。
     * 返回 null 表示该 superType 不含有效泛型参数（非 Repository 基类）。
     */
    private fun extractEntityFromSuperType(superType: PsiType): PsiClass? {
        val classType = superType as? PsiClassType ?: return null
        val parameters = classType.parameters
        if (parameters.isEmpty()) return null
        // Spring Data 基类第一类型参数为实体类
        val entityType = parameters[0] as? PsiClassType ?: return null
        return entityType.resolve()
    }

    /**
     * 从方法返回类型解析实体类（兜底路径）。
     *
     * 当 Repository 接口的 superType 泛型不可解析时（如 LightPlatform 测试环境），
     * 从方法返回类型的泛型参数推断：
     *  - `Optional<Entity>` → Entity
     *  - `List<Entity>` → Entity
     *  - `Entity` → Entity（直接返回类型）
     *
     * void 返回类型（如 deleteByStatus）：无法从返回类型推断实体，直接返回 null。
     * 调用方应优先使用 [resolveEntityClass]（从 Repository 泛型参数解析）作为主要路径，
     * 本方法仅作兜底——void 时跳过以避免误判。
     */
    private fun resolveEntityFromReturnType(method: PsiMethod): PsiClass? {
        val returnType = method.returnType ?: return null

        // void 返回类型（P2-2：void 兜底）：PsiPrimitiveType.VOID 非 PsiClassType，
        // 无法提取泛型参数，直接返回 null，让 locateFromMethod 链保持 entityClass=null。
        // 调用方 resolveEntityClass(containingClass) 已优先尝试 Repository 泛型解析；
        // 若也失败，entityClass 为 null，但定位不失败——下游 converter 会按名称/文本兜底。
        if (returnType is com.intellij.psi.PsiPrimitiveType && returnType == PsiTypes.voidType()) {
            return null
        }

        // 直接返回类型是实体类（非泛型包装）
        val directClass = (returnType as? PsiClassType)?.resolve()
        if (directClass != null && !isCommonType(directClass)) return directClass

        // 泛型包装类型（Optional<Entity> / List<Entity> / Page<Entity>）
        val classType = returnType as? PsiClassType ?: return null
        val parameters = classType.parameters
        if (parameters.isEmpty()) return null
        val firstParam = parameters[0] as? PsiClassType ?: return null
        return firstParam.resolve()?.takeIf { !isCommonType(it) }
    }

    /**
     * 判断是否为常见非实体类型（String/Integer/Long 等基本包装类）。
     * 防止返回类型为 `List<String>` 等基本类型时被误判为实体。
     */
    private fun isCommonType(clazz: PsiClass): Boolean {
        val name = clazz.name ?: return false
        return name in setOf(
            "String", "Integer", "Long", "Short", "Byte", "Double", "Float",
            "BigDecimal", "BigInteger", "Boolean", "Character",
            "Date", "LocalDate", "LocalDateTime", "LocalTime", "Instant",
        )
    }

    /**
     * 判断方法是否标注 @Query 注解。
     *
     * 优先全限定名匹配；LightPlatform 测试环境注解类不可解析时按短名兜底。
     */
    private fun hasQueryAnnotation(method: PsiMethod): Boolean {
        val annotations = method.modifierList?.annotations ?: return false
        for (annotation in annotations) {
            val qualifiedName = annotation.qualifiedName
            if (qualifiedName != null && qualifiedName == QUERY_ANNOTATION) return true
            // 短名兜底（LightPlatform 测试环境）
            val shortName = annotation.nameReferenceElement?.referenceName
            if (shortName == "Query") return true
        }
        return false
    }
}
