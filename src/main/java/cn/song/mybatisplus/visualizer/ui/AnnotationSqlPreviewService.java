package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.xml.AnnotationSqlParser;
import cn.song.mybatisplus.visualizer.xml.XmlParseException;
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel;
import cn.song.mybatisplus.visualizer.xml.XmlScriptException;
import cn.song.mybatisplus.visualizer.xml.XmlStatementKind;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 注解 SQL 预览服务（T-M25-02/03）：Java Mapper 方法上的 MyBatis SQL 注解 → {@link PreviewResult}。
 *
 * 构建规则对齐 mybatis-3.5.19（MapperAnnotationBuilder）：
 * - 字面量注解（@Select/@Insert/@Update/@Delete）：value 元素（数组/单值）经平台常量求值器
 *   （computeConstantExpression：字面量/文本块/`+` 拼接/静态 final 引用）逐个求值 →
 *   {@code String.join(" ", values).trim()} → {@link AnnotationSqlParser}（script 检测与渲染）；
 * - Provider 注解（@SelectProvider 等，L1 常量折叠）：type → PsiClass、method → 同名方法或
 *   {@code provideSql} → return 表达式常量求值 → 同一渲染；含参数依赖/循环 → 降级提示
 *   「动态 SQL 由 Java 生成，无法静态预览」；
 * - @Param 参数名收集（@Param 值 > 实际参数名；多参数或含 @Param 时补 {@code param1..N}，
 *   对齐 ParamNameResolver 的 ParamMap 键）注入 mock；
 * - @Lang 非默认语言驱动 → 提示不支持预览。
 */
public final class AnnotationSqlPreviewService {

    /** 字面量 SQL 注解（qualifiedName 末段）。 */
    public static final Set<String> LITERAL_ANNOTATIONS = Set.of("Select", "Insert", "Update", "Delete");

    /** Provider 注解（SQL 由 Java 方法生成）。 */
    public static final Set<String> PROVIDER_ANNOTATIONS =
            Set.of("SelectProvider", "InsertProvider", "UpdateProvider", "DeleteProvider");

    /** 全部 SQL 注解（gutter/action 判定用）。 */
    public static final Set<String> SQL_ANNOTATIONS = new HashSet<>();

    static {
        SQL_ANNOTATIONS.addAll(LITERAL_ANNOTATIONS);
        SQL_ANNOTATIONS.addAll(PROVIDER_ANNOTATIONS);
    }

    private static final String FALLBACK_METHOD_NAME = "provideSql";

    public static final AnnotationSqlPreviewService INSTANCE = new AnnotationSqlPreviewService();

    private AnnotationSqlPreviewService() {
    }

    /** 注解是否为 MyBatis SQL 注解（按 qualifiedName 末段匹配，O(1) 语法判定）。 */
    public boolean isSqlAnnotation(@NotNull PsiAnnotation annotation) {
        String qn = annotation.getQualifiedName();
        if (qn == null) return false;
        String shortName = qn.substring(qn.lastIndexOf('.') + 1);
        return SQL_ANNOTATIONS.contains(shortName);
    }

    /** M26-A：文件内全部 SQL 注解候选（文件级多候选——光标不在注解/Wrapper 上时回落）。 */
    public List<PsiAnnotation> annotationCandidates(@NotNull PsiFile file) {
        return PsiTreeUtil.collectElementsOfType(file, PsiAnnotation.class).stream()
                .filter(this::isSqlAnnotation)
                .collect(Collectors.toList());
    }

    /**
     * T-M29.6：选择区内 SQL 注解候选（注解起始 offset 在选区范围内，按 offset 排序）——
     * ConvertToSqlAction 选择区候选回落复用（与 Java 选择区候选语义一致：注解开头在选择区内才计入）；
     * 纯 PSI 遍历零 resolve。
     */
    public List<PsiAnnotation> annotationCandidatesInRange(@NotNull PsiFile file, int start, int end) {
        return annotationCandidates(file).stream()
                .filter(it -> {
                    int off = it.getTextRange().getStartOffset();
                    return off >= start && off < end;
                })
                .sorted((a, b) -> Integer.compare(
                        a.getTextRange().getStartOffset(), b.getTextRange().getStartOffset()))
                .collect(Collectors.toList());
    }

    /** M26-A：注解 SQL 摘要（候选列表行文本——value 首个字符串元素，截断）。 */
    public String sqlSnippet(@NotNull PsiAnnotation annotation, int maxLength) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(null);
        PsiAnnotationMemberValue first = value instanceof PsiArrayInitializerMemberValue
                ? ((PsiArrayInitializerMemberValue) value).getInitializers()[0]
                : value;
        String text = "";
        if (first instanceof PsiLiteralExpression) {
            Object v = ((PsiLiteralExpression) first).getValue();
            if (v != null) text = v.toString().trim();
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "…" : text;
    }

    /** 预览注解 SQL（解析失败/降级场景返回带 warning 的空结果，不抛异常）。 */
    @NotNull
    public PreviewResult preview(@NotNull PsiAnnotation annotation, @Nullable Project project) {
        String qn = annotation.getQualifiedName();
        String shortName = qn != null ? qn.substring(qn.lastIndexOf('.') + 1) : null;
        if (shortName == null) return nullResult(null);
        return PROVIDER_ANNOTATIONS.contains(shortName)
                ? previewProvider(annotation, shortName, project)
                : previewLiteral(annotation, shortName, project);
    }

    // ------------------------------------------------------------------
    // 字面量注解
    // ------------------------------------------------------------------

    private PreviewResult previewLiteral(@NotNull PsiAnnotation annotation, String shortName, @Nullable Project project) {
        List<String> warnings = new ArrayList<>();
        checkLanguageDriver(annotation, warnings);

        PsiAnnotationMemberValue rawValue = annotation.findAttributeValue(null);
        List<String> values = new ArrayList<>();
        if (rawValue instanceof PsiArrayInitializerMemberValue) {
            for (PsiAnnotationMemberValue init : ((PsiArrayInitializerMemberValue) rawValue).getInitializers()) {
                values.add(constantString(init, project, warnings));
            }
        } else {
            values.add(constantString(rawValue, project, warnings));
        }
        if (values.isEmpty()) {
            return nullResult(MpSqlVisualizerBundle.message("annotation.value.notFound"));
        }
        String sql = String.join(" ", values).trim();
        PsiMethod method = PsiTreeUtil.getParentOfType(annotation, PsiMethod.class);
        return buildResult(sql, shortName, paramNames(method), warnings, project);
    }

    // ------------------------------------------------------------------
    // Provider 注解（L1 常量折叠）
    // ------------------------------------------------------------------

    private PreviewResult previewProvider(@NotNull PsiAnnotation annotation, String shortName, @Nullable Project project) {
        List<String> warnings = new ArrayList<>();
        checkLanguageDriver(annotation, warnings);

        PsiClass providerClass = resolveProviderClass(annotation, project);
        PsiMethod mapperMethod = PsiTreeUtil.getParentOfType(annotation, PsiMethod.class);
        if (providerClass == null) {
            return nullResult(MpSqlVisualizerBundle.message("annotation.provider.classNotFound"));
        }
        String methodName = constantString(annotation.findDeclaredAttributeValue("method"), project, warnings);
        if (methodName == null || methodName.isEmpty()) {
            methodName = mapperMethod != null ? mapperMethod.getName() : FALLBACK_METHOD_NAME;
        }
        PsiMethod[] providerMethods = providerClass.findMethodsByName(methodName, false);
        PsiMethod providerMethod = providerMethods.length > 0 ? providerMethods[0] : null;
        if (providerMethod == null) {
            return nullResult(MpSqlVisualizerBundle.message(
                    "annotation.provider.methodNotFound", methodName, providerClass.getQualifiedName()));
        }
        // return 表达式常量折叠（L1）；任一成功即用，失败/无 return → 降级提示
        String sql = null;
        if (providerMethod.getBody() != null) {
            for (PsiStatement stmt : providerMethod.getBody().getStatements()) {
                if (stmt instanceof PsiReturnStatement) {
                    PsiExpression returnValue = ((PsiReturnStatement) stmt).getReturnValue();
                    if (returnValue != null) {
                        String c = constantExpression(returnValue, project);
                        if (c != null) {
                            sql = c;
                            break;
                        }
                    }
                }
            }
        }
        if (sql == null) {
            return nullResult(MpSqlVisualizerBundle.message("annotation.provider.notSupported"));
        }
        return buildResult(sql, shortName, paramNames(mapperMethod), warnings, project);
    }

    /** Provider type 解析：value/type 属性（PsiClassObjectAccessExpression）→ PsiClass。 */
    @Nullable
    private PsiClass resolveProviderClass(@NotNull PsiAnnotation annotation, @Nullable Project project) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue("type");
        if (value == null) value = annotation.findAttributeValue("value");
        if (!(value instanceof PsiClassObjectAccessExpression)) return null;
        PsiType type = ((PsiClassObjectAccessExpression) value).getOperand().getType();
        if (!(type instanceof com.intellij.psi.PsiClassType)) return null;
        PsiClass resolved = ((com.intellij.psi.PsiClassType) type).resolve();
        return resolved instanceof PsiClass ? resolved : null;
    }

    // ------------------------------------------------------------------
    // 共享
    // ------------------------------------------------------------------

    /** 元素常量求值：字面量/文本块/`+` 拼接/静态 final 引用；失败返回 null（含参数依赖等）。 */
    @Nullable
    private String constantExpression(@Nullable PsiAnnotationMemberValue memberValue, @Nullable Project project) {
        if (memberValue == null) return null;
        if (project == null) return null;
        Object value = JavaPsiFacade.getInstance(project).getConstantEvaluationHelper()
                .computeConstantExpression(memberValue);
        return value != null ? value.toString() : null;
    }

    /** 数组元素/单值常量求值：失败记录 warning（元素缺失/非常量）。 */
    @NotNull
    public String constantString(@Nullable PsiAnnotationMemberValue memberValue,
                                 @Nullable Project project, @NotNull List<String> warnings) {
        String result = constantExpression(memberValue, project);
        if (result == null && memberValue != null) {
            warnings.add(MpSqlVisualizerBundle.message("annotation.constant.failed"));
        }
        return result != null ? result : "";
    }

    /** @Lang 检测：方法存在 @Lang 且 value 非默认 XML 语言驱动 → 提示。 */
    private void checkLanguageDriver(@NotNull PsiAnnotation annotation, @NotNull List<String> warnings) {
        PsiMethod method = PsiTreeUtil.getParentOfType(annotation, PsiMethod.class);
        if (method == null) return;
        PsiAnnotation lang = null;
        for (PsiAnnotation a : method.getAnnotations()) {
            String qn = a.getQualifiedName();
            String shortName = qn != null ? qn.substring(qn.lastIndexOf('.') + 1) : null;
            if ("Lang".equals(shortName)) {
                lang = a;
                break;
            }
        }
        if (lang == null) return;
        PsiAnnotationMemberValue dv = lang.findAttributeValue(null);
        String driverClass = null;
        if (dv instanceof PsiClassObjectAccessExpression) {
            driverClass = ((PsiClassObjectAccessExpression) dv).getOperand().getType().getCanonicalText();
        }
        if (driverClass != null && !"org.apache.ibatis.scripting.xmltags.XMLLanguageDriver".equals(driverClass)) {
            warnings.add(MpSqlVisualizerBundle.message("annotation.lang.notSupported", driverClass));
        }
    }

    /**
     * 方法参数 mock 名（对齐 ParamNameResolver）：@Param 值优先，否则实际参数名；
     * 多参数或含 @Param 时补 {@code param1..N}（GENERIC_NAME_CACHE）。
     */
    @NotNull
    public List<String> paramNames(@Nullable PsiMethod method) {
        if (method == null) return Collections.emptyList();
        PsiParameter[] parameters = method.getParameterList().getParameters();
        if (parameters.length == 0) return Collections.emptyList();
        boolean hasParamAnnotation = false;
        List<String> names = new ArrayList<>();
        for (PsiParameter param : parameters) {
            PsiAnnotation paramAnno = null;
            for (PsiAnnotation a : param.getAnnotations()) {
                String qn = a.getQualifiedName();
                String shortName = qn != null ? qn.substring(qn.lastIndexOf('.') + 1) : null;
                if ("Param".equals(shortName)) {
                    paramAnno = a;
                    break;
                }
            }
            if (paramAnno != null) {
                hasParamAnnotation = true;
                PsiAnnotationMemberValue pv = paramAnno.findAttributeValue(null);
                String v = null;
                if (pv instanceof PsiLiteralExpression) {
                    Object val = ((PsiLiteralExpression) pv).getValue();
                    v = val != null ? val.toString() : null;
                }
                names.add(v != null ? v : param.getName());
            } else {
                names.add(param.getName());
            }
        }
        if (parameters.length > 1 || hasParamAnnotation) {
            for (int i = 1; i <= parameters.length; i++) names.add("param" + i);
        }
        return names;
    }

    /** 模型 → 结果（解析异常兜底 warning；T-M25.2 静态常量引用经 project 解析注入）。 */
    private PreviewResult buildResult(String sql, String shortName,
                                      List<String> paramNames, List<String> warnings, @Nullable Project project) {
        XmlStatementKind kind;
        switch (shortName.replaceAll("Provider$", "")) {
            case "Select":
                kind = XmlStatementKind.SELECT;
                break;
            case "Insert":
                kind = XmlStatementKind.INSERT;
                break;
            case "Update":
                kind = XmlStatementKind.UPDATE;
                break;
            default:
                kind = XmlStatementKind.DELETE;
                break;
        }
        try {
            Map<String, Object> staticValues = XmlPreviewService.INSTANCE.resolveStaticFields(sql, project, warnings);
            XmlPreviewModel model = new AnnotationSqlParser().parse(sql, kind, paramNames, staticValues);
            return XmlPreviewService.INSTANCE.toResult(model, warnings);
        } catch (XmlParseException e) {
            return nullResult(e.getMessage() != null ? e.getMessage() : "SQL 解析失败。");
        } catch (XmlScriptException e) {
            return nullResult(e.getMessage() != null ? e.getMessage() : "SQL 脚本解析失败。");
        }
    }

    @NotNull
    private PreviewResult nullResult(@Nullable String message) {
        return new PreviewResult(
                "",
                "",
                Collections.emptyList(),
                null,
                null,
                null,
                message,
                Collections.emptyList(),
                Collections.emptyMap(),
                null,
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                null,
                Collections.emptyMap(),
                null,
                null,
                null,
                null
        );
    }
}
