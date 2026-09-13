package cn.song.mybatisplus.visualizer.psi.jpa;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Spring Data JPA @Query 注解定位器（F-JPA-02）。
 *
 * 从光标位置定位 Repository 接口中带有 @Query 注解的方法，提取 JPQL 字符串与实体上下文。
 */
public final class JpqlQueryLocator {

    /** 单例实例（Kotlin object 语义兼容，供外部 Xxx.method() 调用路由）。 */
    public static final JpqlQueryLocator INSTANCE = new JpqlQueryLocator();

    private JpqlQueryLocator() {
    }

    /** Spring Data @Query 注解全限定名。 */
    private static final String QUERY_ANNOTATION = "org.springframework.data.jpa.repository.Query";

    /**
     * SD7（M44 止血）：Spring Data JDBC / R2DBC 的 @Query 全限定名。
     *
     * <p>javap 实证（spring-data-jdbc 4.1.1 / spring-data-r2dbc 4.1.1）：两者均<b>无 nativeQuery 属性</b>
     * （jdbc 版属性 = value/name/rowMapper 族，r2dbc 版仅 value），查询体本就是原生 SQL——
     * 此前短名 "Query" 兜底命中后被当 JPQL 喂 JpqlConverter，{@code SELECT * FROM ...} 等非 JPQL 语法
     * 产出错误结果（矩阵 §1.3 最危险假阳性链路）。识别后 isNative 置 true，
     * 使 previewJpql 走<b>原生透传分支</b>（convertNativeSqlParameters 命名参数替换），不做 JPQL 转换。
     */
    private static final Set<String> NATIVE_QUERY_ANNOTATIONS = Set.of(
            "org.springframework.data.jdbc.repository.query.Query",
            "org.springframework.data.r2dbc.repository.Query");

    /** SD7：JDBC/R2DBC @Query 的 FQN 前缀（qualifiedName 仅在注解类可解析时可靠，前缀兜底 import 场景）。 */
    private static final List<String> NATIVE_QUERY_FQN_PREFIXES = List.of(
            "org.springframework.data.jdbc.repository.query.Query",
            "org.springframework.data.r2dbc.repository.Query");

    /**
     * 从光标位置定位 @Query 注解方法。
     *
     * @param file   目标 PSI 文件
     * @param offset 光标偏移量
     * @return JpqlQueryInfo 或 null（如果不是 @Query 注解方法）
     */
    @Nullable
    public static JpqlQueryInfo locate(@NotNull PsiFile file, int offset) {
        PsiElement element = file.findElementAt(offset);
        if (element == null) return null;
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (method == null) return null;
        return locateFromMethod(method);
    }

    /**
     * 判断方法是否标注 @Query 注解（优先全限定名，LightPlatform 按短名兜底）。
     *
     * @param method 目标方法
     * @return 是否标注 @Query 注解
     */
    public static boolean hasQueryAnnotation(@NotNull PsiMethod method) {
        if (method.getModifierList() == null) return false;
        for (PsiAnnotation annotation : method.getModifierList().getAnnotations()) {
            String qualifiedName = annotation.getQualifiedName();
            if (qualifiedName != null && qualifiedName.equals(QUERY_ANNOTATION)) return true;
            String shortName = annotation.getNameReferenceElement() != null
                    ? annotation.getNameReferenceElement().getReferenceName() : null;
            if ("Query".equals(shortName)) return true;
        }
        return false;
    }

    /**
     * 从指定方法定位 @Query 查询信息。
     */
    @Nullable
    private static JpqlQueryInfo locateFromMethod(@NotNull PsiMethod method) {
        if (!hasQueryAnnotation(method)) return null;

        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) return null;
        PsiAnnotation queryAnnotation = findQueryAnnotation(method);
        if (queryAnnotation == null) return null;

        String jpql = extractJpql(queryAnnotation);
        if (jpql == null) return null;
        boolean isNative = isNativeQueryAnnotation(queryAnnotation) || isNativeQuery(queryAnnotation);
        String queryName = extractQueryName(queryAnnotation);
        PsiClass entityClass = DerivedMethodLocator.resolveEntityClass(containingClass);

        return new JpqlQueryInfo(method.getName(), containingClass, entityClass, jpql, isNative, queryName, method);
    }

    /**
     * 查找 @Query 注解实例。
     */
    @Nullable
    private static PsiAnnotation findQueryAnnotation(@NotNull PsiMethod method) {
        if (method.getModifierList() == null) return null;
        for (PsiAnnotation annotation : method.getModifierList().getAnnotations()) {
            String qualifiedName = annotation.getQualifiedName();
            if (qualifiedName != null && qualifiedName.equals(QUERY_ANNOTATION)) return annotation;
            String shortName = annotation.getNameReferenceElement() != null
                    ? annotation.getNameReferenceElement().getReferenceName() : null;
            if ("Query".equals(shortName)) return annotation;
        }
        return null;
    }

    /**
     * 提取 @Query 注解的 value 属性（JPQL 字符串）。
     */
    @Nullable
    private static String extractJpql(@NotNull PsiAnnotation annotation) {
        PsiAnnotationMemberValue memberValue = annotation.findAttributeValue("value");
        if (memberValue == null) return null;
        return extractStringValue(memberValue);
    }

    /**
     * 检测是否为原生 SQL 查询（`nativeQuery = true`）。
     */
    private static boolean isNativeQuery(@NotNull PsiAnnotation annotation) {
        PsiAnnotationMemberValue memberValue = annotation.findAttributeValue("nativeQuery");
        if (memberValue == null) return false;
        if (!(memberValue instanceof PsiLiteralExpression literal)) return false;
        Object value = literal.getValue();
        if (!(value instanceof Boolean b)) return false;
        return b;
    }

    /**
     * SD7（M44）：注解是否为 Spring Data JDBC / R2DBC @Query（原生 SQL 语义）。
     *
     * <p>判定路径：
     * <ol>
     *   <li>qualifiedName 精确 / 前缀匹配（注解类可解析或注解以全限定名书写时可靠）；</li>
     *   <li>兜底：宿主文件的 import 语句含 JDBC/R2DBC Query FQN（{@code @Query} 短名书写 +
     *       注解类不可解析的测试/跨模块场景——qualifiedName 退化为短名文本，只能靠 import 定包）。</li>
     * </ol>
     */
    private static boolean isNativeQueryAnnotation(@NotNull PsiAnnotation annotation) {
        String qualifiedName = annotation.getQualifiedName();
        if (qualifiedName == null) return hasNativeQueryImport(annotation);
        if (NATIVE_QUERY_ANNOTATIONS.contains(qualifiedName)) return true;
        for (String prefix : NATIVE_QUERY_FQN_PREFIXES) {
            if (qualifiedName.startsWith(prefix)) return true;
        }
        // 短名兜底场景（qualifiedName 无包名或与 JPA Query 不匹配时按 import 判定）
        int dot = qualifiedName.lastIndexOf('.');
        String shortName = dot >= 0 ? qualifiedName.substring(dot + 1) : qualifiedName;
        if (!"Query".equals(shortName)) return false;
        return hasNativeQueryImport(annotation);
    }

    /** 宿主文件 import 列表是否引入 JDBC/R2DBC @Query（短名兜底场景的包归属判定）。 */
    private static boolean hasNativeQueryImport(@NotNull PsiAnnotation annotation) {
        if (!(annotation.getContainingFile() instanceof com.intellij.psi.PsiJavaFile javaFile)) return false;
        if (javaFile.getImportList() == null) return false;
        for (com.intellij.psi.PsiImportStatementBase stmt : javaFile.getImportList().getAllImportStatements()) {
            if (stmt instanceof com.intellij.psi.PsiImportStatement imp
                    && imp.getQualifiedName() != null
                    && NATIVE_QUERY_ANNOTATIONS.contains(imp.getQualifiedName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提取 @Query 注解的 name 属性（命名查询引用）。
     */
    @Nullable
    private static String extractQueryName(@NotNull PsiAnnotation annotation) {
        PsiAnnotationMemberValue memberValue = annotation.findAttributeValue("name");
        if (memberValue == null) return null;
        return extractStringValue(memberValue);
    }

    /**
     * 从注解属性值中提取字符串。
     * 支持单字符串字面量，以及字符串拼接表达式（递归收集所有 PsiLiteralExpression 子节点）。
     */
    @Nullable
    private static String extractStringValue(@NotNull PsiAnnotationMemberValue memberValue) {
        // 路径 1：直接字面量
        if (memberValue instanceof PsiLiteralExpression literal) {
            if (literal.getValue() instanceof String s) return s.isBlank() ? null : s;
            return null;
        }
        // 路径 2：字符串拼接表达式
        List<String> parts = collectStringLiterals(memberValue);
        if (!parts.isEmpty()) return String.join("", parts);
        return null;
    }

    /**
     * 递归收集 PSI 树中所有 PsiLiteralExpression 的字符串值。
     */
    private static List<String> collectStringLiterals(@NotNull PsiElement element) {
        if (element instanceof PsiLiteralExpression literal) {
            if (literal.getValue() instanceof String s) return List.of(s);
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (PsiElement child : element.getChildren()) {
            result.addAll(collectStringLiterals(child));
        }
        return result;
    }
}
