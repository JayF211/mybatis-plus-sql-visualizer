package cn.song.mybatisplus.visualizer.psi.jpa;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Spring Data JPA Repository 推导查询方法定位器（F-JPA-01）。
 *
 * 从光标位置定位 Repository 接口中的推导查询方法（Derived Query Method），
 * 判断是否为可预览的推导方法，并提取方法名与实体类。
 */
public final class DerivedMethodLocator {

    /** 单例实例（Kotlin object 语义兼容，供外部 Xxx.method() 调用路由）。 */
    public static final DerivedMethodLocator INSTANCE = new DerivedMethodLocator();

    private DerivedMethodLocator() {
    }

    /** Spring Data Repository 基类全限定名（superType 匹配用）。 */
    private static final Set<String> REPOSITORY_BASE_TYPES = Set.of(
            "org.springframework.data.repository.Repository",
            "org.springframework.data.repository.CrudRepository",
            "org.springframework.data.repository.PagingAndSortingRepository",
            "org.springframework.data.jpa.repository.JpaRepository",
            "org.springframework.data.jpa.repository.QueryExecutorAdapter"
    );

    /** Spring Data @Query 注解全限定名（排除用）。 */
    private static final String QUERY_ANNOTATION = "org.springframework.data.jpa.repository.Query";

    /** PartTree 推导查询方法名前缀（Spring Data JPA 官方支持）。 */
    private static final Set<String> PART_TREE_PREFIXES = Set.of(
            "find", "findAll", "read", "get", "count", "exists", "delete", "remove"
    );

    /**
     * 从光标位置定位推导方法。
     *
     * @param file   目标 PSI 文件
     * @param offset 光标偏移量
     * @return DerivedMethodInfo 或 null（如果不是推导方法）
     */
    @Nullable
    public static DerivedMethodInfo locate(@NotNull PsiFile file, int offset) {
        PsiElement element = file.findElementAt(offset);
        if (element == null) return null;
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (method == null) return null;
        return locateFromMethod(method);
    }

    /**
     * 判断方法是否为可预览的推导查询。
     *
     * 条件：非 @Query 注解 / 非 default / 非 static / 含 By / PartTree 前缀开头。
     *
     * @param method 目标方法
     * @return 是否为可预览的推导查询
     */
    public static boolean isDerivedQuery(@NotNull PsiMethod method) {
        if (hasQueryAnnotation(method)) return false;
        // 排除 default 方法（含方法体，非接口推导）
        if (method.hasModifierProperty("default")) return false;
        // 排除 static 方法
        if (method.hasModifierProperty("static")) return false;

        String methodName = method.getName();
        // 方法名必须包含 By 关键字（PartTree 分隔符）
        if (!methodName.contains("By")) return false;
        // 方法名必须以 PartTree 前缀开头
        return PART_TREE_PREFIXES.stream().anyMatch(methodName::startsWith);
    }

    /**
     * 判断方法是否为**可预览的 Repository 派生查询**（快速路径，供 gutter 图标判定）。
     *
     * 在 isDerivedQuery（纯方法名判定，不检查所在类）基础上叠加 `containing class 是
     * Repository 接口` 校验——避免普通类方法（如业务 DAO 手写方法名 `deleteByDbHelper`）被
     * 误判为 JPA 派生查询而显示错误的预览图标 / 触发 previewDerivedQuery 但返回 null。
     *
     * 语义与 locateFromMethod 的「Repository 接口 + 派生方法名」双重校验一致，
     * 但只做正例判定、不解析实体，O(1) 快速路径专用；点击后仍走 locate 全链路。
     *
     * @param method 目标方法
     * @return 是否为可预览的 Repository 派生查询
     */
    public static boolean isRepositoryMethod(@NotNull PsiMethod method) {
        if (!isDerivedQuery(method)) return false;
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) return false;
        return isRepositoryInterface(containingClass);
    }

    /**
     * 从 Repository 接口提取实体类（从 superType 泛型参数提取）。
     *
     * @param repositoryInterface Repository 接口 PsiClass
     * @return 实体类 PsiClass，解析失败返回 null
     */
    @Nullable
    public static PsiClass resolveEntityClass(@NotNull PsiClass repositoryInterface) {
        for (PsiType superType : repositoryInterface.getSuperTypes()) {
            PsiClass entityClass = extractEntityFromSuperType(superType);
            if (entityClass != null) return entityClass;
        }
        return null;
    }

    /**
     * 从指定方法定位推导方法信息。
     */
    @Nullable
    private static DerivedMethodInfo locateFromMethod(@NotNull PsiMethod method) {
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) return null;
        if (!isRepositoryInterface(containingClass)) return null;
        if (!isDerivedQuery(method)) return null;

        PsiClass entityClass = resolveEntityClass(containingClass);
        if (entityClass == null) entityClass = resolveEntityFromReturnType(method);

        return new DerivedMethodInfo(method.getName(), containingClass, entityClass, method);
    }

    /**
     * 判断 PsiClass 是否为 Spring Data Repository 接口。
     */
    private static boolean isRepositoryInterface(@NotNull PsiClass clazz) {
        if (!clazz.isInterface()) return false;

        // 路径 1：superType 精确匹配 Spring Data 基类
        for (PsiType superType : clazz.getSuperTypes()) {
            String canonicalText = superType.getCanonicalText();
            if (canonicalText == null) continue;
            if (REPOSITORY_BASE_TYPES.stream().anyMatch(canonicalText::startsWith)) return true;
        }

        // 路径 2：启发式——接口名以 Repository 结尾
        String name = clazz.getName();
        if (name == null) return false;
        return name.endsWith("Repository");
    }

    /**
     * 从 superType 泛型参数提取实体类（JpaRepository&lt;Entity, ID&gt; → 第一个类型参数 Entity）。
     */
    @Nullable
    private static PsiClass extractEntityFromSuperType(@NotNull PsiType superType) {
        if (!(superType instanceof PsiClassType classType)) return null;
        PsiType[] parameters = classType.getParameters();
        if (parameters.length == 0) return null;
        if (!(parameters[0] instanceof PsiClassType entityType)) return null;
        return entityType.resolve();
    }

    /**
     * 从方法返回类型解析实体类（兜底路径）：
     *  - Optional&lt;Entity&gt; → Entity
     *  - List&lt;Entity&gt; → Entity
     *  - Entity → Entity（直接返回类型）
     * void 返回类型无法推断，直接返回 null。
     */
    @Nullable
    private static PsiClass resolveEntityFromReturnType(@NotNull PsiMethod method) {
        PsiType returnType = method.getReturnType();
        if (returnType == null) return null;

        // void 返回类型：直接返回 null
        if (returnType instanceof PsiPrimitiveType && returnType.equals(PsiTypes.voidType())) {
            return null;
        }

        // 直接返回类型是实体类（非泛型包装）
        PsiClass directClass = (returnType instanceof PsiClassType ct) ? ct.resolve() : null;
        if (directClass != null && !isCommonType(directClass)) return directClass;

        // 泛型包装类型（Optional<Entity> / List<Entity> / Page<Entity>）
        if (!(returnType instanceof PsiClassType classType)) return null;
        PsiType[] parameters = classType.getParameters();
        if (parameters.length == 0) return null;
        if (!(parameters[0] instanceof PsiClassType firstParam)) return null;
        PsiClass resolved = firstParam.resolve();
        return resolved != null && !isCommonType(resolved) ? resolved : null;
    }

    /**
     * 判断是否为常见非实体类型（String/Integer/Long 等基本包装类）。
     */
    private static boolean isCommonType(@NotNull PsiClass clazz) {
        String name = clazz.getName();
        if (name == null) return false;
        return Set.of("String", "Integer", "Long", "Short", "Byte", "Double", "Float",
                "BigDecimal", "BigInteger", "Boolean", "Character",
                "Date", "LocalDate", "LocalDateTime", "LocalTime", "Instant").contains(name);
    }

    /**
     * 判断方法是否标注 @Query 注解（优先全限定名，LightPlatform 按短名兜底）。
     */
    private static boolean hasQueryAnnotation(@NotNull PsiMethod method) {
        if (method.getModifierList() == null) return false;
        for (com.intellij.psi.PsiAnnotation annotation : method.getModifierList().getAnnotations()) {
            String qualifiedName = annotation.getQualifiedName();
            if (qualifiedName != null && qualifiedName.equals(QUERY_ANNOTATION)) return true;
            String shortName = annotation.getNameReferenceElement() != null
                    ? annotation.getNameReferenceElement().getReferenceName() : null;
            if ("Query".equals(shortName)) return true;
        }
        return false;
    }
}
