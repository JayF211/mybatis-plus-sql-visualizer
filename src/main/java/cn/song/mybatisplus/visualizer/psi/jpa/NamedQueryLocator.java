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

    /** @NamedQuery 注解全限定名。 */
    private static final String NAMED_QUERY = "javax.persistence.NamedQuery";

    /** @NamedQueries 容器注解全限定名。 */
    private static final String NAMED_QUERIES = "javax.persistence.NamedQueries";

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
            String simpleName = resolveAnnotationSimpleName(annotation);
            if (simpleName == null) continue;
            switch (simpleName) {
                case "NamedQueries" -> extractNamedQueriesContainer(annotation, results);
                case "NamedQuery" -> extractNamedQuery(annotation, results);
                default -> {
                    // 其它注解忽略
                }
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
        if (queryName == null || queryName.isBlank()) return null;
        int dot = queryName.indexOf('.');
        String entitySimpleName = (dot >= 0 ? queryName.substring(0, dot) : "").trim();
        if (entitySimpleName.isBlank()) return null;
        PsiClass[] classes;
        try {
            classes = JavaPsiFacade.getInstance(project).findClasses(entitySimpleName, GlobalSearchScope.projectScope(project));
        } catch (Throwable t) {
            // runCatching 等价：解析失败时调用方优雅降级
            return null;
        }
        for (PsiClass cls : classes) {
            NamedQueryInfo hit = null;
            for (NamedQueryInfo info : locate(cls)) {
                if (info.getName().equals(queryName)) {
                    hit = info;
                    break;
                }
            }
            if (hit != null) return hit;
        }
        return null;
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
     * 解析注解的短名（simpleName）。
     *
     * 优先 qualifiedName 截取（完整 IDE）；LightPlatform 测试环境下 qualifiedName 可能为 null，
     * 回退使用 nameReferenceElement.referenceName。
     *
     * @return 短名；无法解析返回 null
     */
    @Nullable
    private static String resolveAnnotationSimpleName(@NotNull PsiAnnotation annotation) {
        if (annotation.getQualifiedName() != null) {
            String qn = annotation.getQualifiedName();
            return qn.substring(qn.lastIndexOf('.') + 1);
        }
        return annotation.getNameReferenceElement() != null
                ? annotation.getNameReferenceElement().getReferenceName()
                : null;
    }
}
