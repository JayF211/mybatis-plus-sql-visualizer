package cn.song.mybatisplus.visualizer.psi.jpa;

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.search.GlobalSearchScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * JPA @NamedQuery / @NamedQueries 注解定位器（F-JPA-02 / P2-1）。
 *
 * 从实体类上定位 @NamedQuery / @NamedQueries 注解，提取命名查询的 name 和 JPQL 字符串。
 *
 * 识别模式：
 *  - @NamedQuery(name="xxx", query="JPQL")：单一定位，提取 name 与 query 属性。
 *  - @NamedQueries({@NamedQuery(...), @NamedQuery(...)})：容器注解，遍历 value 数组提取。
 */
public final class NamedQueryLocator {

    /** 单例实例（Kotlin object 语义兼容，供外部 Xxx.method() 调用路由）。 */
    public static final NamedQueryLocator INSTANCE = new NamedQueryLocator();

    private NamedQueryLocator() {
    }

    /** @NamedQuery 注解全限定名（javax 老命名空间）。 */
    private static final String NAMED_QUERY_JAVAX = "javax.persistence.NamedQuery";

    /** @NamedQuery 注解全限定名（jakarta 新命名空间，T-J12 双支持）。 */
    private static final String NAMED_QUERY_JAKARTA = "jakarta.persistence.NamedQuery";

    /** @NamedQueries 容器注解全限定名（javax 老命名空间）。 */
    private static final String NAMED_QUERIES_JAVAX = "javax.persistence.NamedQueries";

    /** @NamedQueries 容器注解全限定名（jakarta 新命名空间，T-J12 双支持）。 */
    private static final String NAMED_QUERIES_JAKARTA = "jakarta.persistence.NamedQueries";

    /** @NamedQuery 全限定名集合（javax + jakarta）。 */
    private static final Set<String> NAMED_QUERY_FQNS = Set.of(NAMED_QUERY_JAVAX, NAMED_QUERY_JAKARTA);

    /** @NamedQueries 全限定名集合（javax + jakarta）。 */
    private static final Set<String> NAMED_QUERIES_FQNS = Set.of(NAMED_QUERIES_JAVAX, NAMED_QUERIES_JAKARTA);

    /** 容器注解短名（LightPlatform 测试环境 / 粘贴片段下 FQN 不可解析时兜底）。 */
    private static final String NAMED_QUERIES_SHORT_NAME = "NamedQueries";

    /** 单注解短名。 */
    private static final String NAMED_QUERY_SHORT_NAME = "NamedQuery";

    /** 识别出的命名查询注解类别。 */
    private enum AnnotationKind { NAMED_QUERY, NAMED_QUERIES }

    /**
     * 从实体类提取全部 @NamedQuery / @NamedQueries 命名查询。
     *
     * 同时扫描 @NamedQueries 容器与独立 @NamedQuery 注解（JPA 规范允许两种写法并存）。
     *
     * @param entityClass 实体类 PsiClass
     * @return 命名查询信息列表；无注解时返回空列表
     */
    @NotNull
    public static List<NamedQueryInfo> locate(@NotNull PsiClass entityClass) {
        List<NamedQueryInfo> results = new ArrayList<>();
        if (entityClass.getModifierList() == null) return results;
        for (PsiAnnotation annotation : entityClass.getModifierList().getAnnotations()) {
            AnnotationKind kind = resolveAnnotationKind(annotation);
            if (kind == AnnotationKind.NAMED_QUERIES) {
                extractNamedQueriesContainer(annotation, results);
            } else if (kind == AnnotationKind.NAMED_QUERY) {
                extractNamedQuery(annotation, results);
            }
        }
        return results;
    }

    /**
     * 按命名查询名查找（T-PreviewShortcut：`session.createNamedQuery("name")` 跨文件按名回查）。
     *
     * JPA 规范命名查询名默认即 `&lt;实体简单名&gt;.&lt;方法名&gt;`（如 `"UserEntity.findByUsernameNamed"`），
     * 取 `.` 前缀解析实体类，再由 locate 按名字精确匹配。解析不到时返回 null（调用方优雅降级）。
     *
     * @param project   项目
     * @param queryName 命名查询名
     * @return 命中的命名查询（annotation 字段已回填，可反查实体类）；未命中返回 null
     */
    @Nullable
    public static NamedQueryInfo findByQueryName(@NotNull Project project, @Nullable String queryName) {
        return findByQueryName(project, queryName, null);
    }

    @Nullable
    public static NamedQueryInfo findByQueryName(@NotNull Project project, @Nullable String queryName,
                                                 @Nullable com.intellij.psi.PsiElement context) {
        List<NamedQueryInfo> candidates = findCandidatesByQueryName(project, queryName, context);
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    public static List<NamedQueryInfo> findCandidatesByQueryName(Project project, String queryName) {
        return findCandidatesByQueryName(project, queryName, null);
    }

    /** 保留歧义信息，预览不得把 null 误认成 Java 定义不存在而绕到 HBM。 */
    public static List<NamedQueryInfo> findCandidatesByQueryName(Project project, String queryName,
                                                                 com.intellij.psi.PsiElement context) {
        if (queryName == null || queryName.isBlank() || com.intellij.openapi.project.DumbService.isDumb(project)) return List.of();
        int dot = queryName.lastIndexOf('.');
        String ownerName = (dot >= 0 ? queryName.substring(0, dot) : "").trim();
        if (ownerName.isBlank()) return List.of();
        try {
            GlobalSearchScope scope = cn.song.mybatisplus.visualizer.psi.EntityClassResolver.searchScope(project, context);
            // 宿主不一定是根实体，也不一定有 @Entity；仅按宿主名查找。
            PsiClass[] classes = ownerName.contains(".") ? JavaPsiFacade.getInstance(project).findClasses(ownerName, scope)
                    : com.intellij.psi.search.PsiShortNamesCache.getInstance(project).getClassesByName(ownerName, scope);
            List<NamedQueryInfo> matches = new ArrayList<>();
            for (PsiClass cls : new java.util.LinkedHashSet<>(java.util.Arrays.asList(classes))) {
                for (NamedQueryInfo info : locate(cls)) if (queryName.equals(info.getName())) matches.add(info);
            }
            return matches;
        } catch (com.intellij.openapi.project.IndexNotReadyException ignored) {
            return List.of();
        }
    }

    /**
     * 从 @NamedQueries 容器注解中提取子 @NamedQuery 列表。
     */
    private static void extractNamedQueriesContainer(
            @NotNull PsiAnnotation annotation,
            @NotNull List<NamedQueryInfo> results) {
        PsiAnnotationMemberValue valueAttr = annotation.findAttributeValue("value");
        if (valueAttr == null) return;
        // value 属性是数组初始化器：{ @NamedQuery(...), @NamedQuery(...) }
        if (valueAttr instanceof PsiArrayInitializerMemberValue arrayValue) {
            for (PsiAnnotationMemberValue element : arrayValue.getInitializers()) {
                if (element instanceof PsiAnnotation a) extractNamedQuery(a, results);
            }
        } else if (valueAttr instanceof PsiAnnotation a) {
            // 单元素退化形态：@NamedQueries(@NamedQuery(...))（JPA 允许单值省略花括号）
            extractNamedQuery(a, results);
        }
    }

    /**
     * 从单个 @NamedQuery 注解提取 name / query 属性并追加到结果列表。
     */
    private static void extractNamedQuery(
            @NotNull PsiAnnotation annotation,
            @NotNull List<NamedQueryInfo> results) {
        String name = extractStringAttribute(annotation, "name");
        if (name == null) return;
        String query = extractStringAttribute(annotation, "query");
        if (query == null) return;
        if (name.isBlank() || query.isBlank()) return;
        results.add(new NamedQueryInfo(name, query, annotation));
    }

    /**
     * 从注解实例提取指定字符串属性的值。
     */
    @Nullable
    private static String extractStringAttribute(@NotNull PsiAnnotation annotation, @NotNull String attrName) {
        PsiAnnotationMemberValue memberValue = annotation.findAttributeValue(attrName);
        if (memberValue == null) return null;
        return extractStringValue(memberValue);
    }

    /**
     * 从注解属性值中提取字符串文本。
     *
     * 优先 PsiLiteralExpression 取值；不可用时回退去引号文本。
     */
    @Nullable
    private static String extractStringValue(@NotNull PsiAnnotationMemberValue memberValue) {
        // 路径 1：PsiLiteralExpression 直接取值（LightPlatform 常见形态）
        if (memberValue instanceof PsiLiteralExpression literal) {
            String value = literal.getValue() instanceof String s ? s : null;
            return value != null && !value.isBlank() ? value : null;
        }
        // 路径 2：纯文本回退（去掉字面量引号）
        String text = memberValue.getText();
        if (text == null) return null;
        text = text.trim();
        String unquoted = text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")
                ? text.substring(1, text.length() - 1)
                : text;
        return !unquoted.isBlank() ? unquoted : null;
    }

    /**
     * 解析注解类别（T-J12：javax / jakarta 双 FQN + 短名兜底，沿用 JpaEntityResolver 惯例）。
     *
     * <p>匹配顺序：
     * <ol>
     *   <li>qualifiedName 精确匹配 javax / jakarta 全限定名；</li>
     *   <li>兜底：短名匹配（LightPlatform 测试环境 / 粘贴片段下注解类不可解析，qualifiedName 为 null
     *       或为同文件裸短名，用 nameReferenceElement.referenceName 兜底——与旧短名行为保持一致）。</li>
     * </ol>
     *
     * @param annotation 注解实例
     * @return 注解类别；非命名查询注解返回 null
     */
    @Nullable
    private static AnnotationKind resolveAnnotationKind(@NotNull PsiAnnotation annotation) {
        String qualifiedName = annotation.getQualifiedName();
        if (qualifiedName != null) {
            // 优先全限定名精确匹配
            if (NAMED_QUERY_FQNS.contains(qualifiedName)) return AnnotationKind.NAMED_QUERY;
            if (NAMED_QUERIES_FQNS.contains(qualifiedName)) return AnnotationKind.NAMED_QUERIES;
        }
        // FQN 不匹配（不可解析 / 短名形态）→ 按短名兜底
        String shortName = annotation.getNameReferenceElement() != null
                ? annotation.getNameReferenceElement().getReferenceName() : null;
        if (NAMED_QUERY_SHORT_NAME.equals(shortName)) return AnnotationKind.NAMED_QUERY;
        if (NAMED_QUERIES_SHORT_NAME.equals(shortName)) return AnnotationKind.NAMED_QUERIES;
        return null;
    }
}
