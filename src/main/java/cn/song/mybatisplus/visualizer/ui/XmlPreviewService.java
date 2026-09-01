package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import cn.song.mybatisplus.visualizer.xml.MockObject;
import cn.song.mybatisplus.visualizer.xml.ParameterMapping;
import cn.song.mybatisplus.visualizer.xml.RenderedXmlSql;
import cn.song.mybatisplus.visualizer.xml.XmlEwExpander;
import cn.song.mybatisplus.visualizer.ui.XmlEwResolver;
import cn.song.mybatisplus.visualizer.xml.XmlMapperModel;
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser;
import cn.song.mybatisplus.visualizer.xml.XmlParseException;
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel;
import cn.song.mybatisplus.visualizer.xml.XmlScriptException;
import cn.song.mybatisplus.visualizer.xml.XmlStatement;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XML 预览服务（T-M24-04）：mapper 文件文本 → 语句 → 预览模型 → PreviewResult。
 *
 * 纯函数（fileText 输入，无 PSI 依赖），gutter / Action 负责取文件文本与定位语句 id；
 * 弹窗与面板完全复用 SqlPreviewService.showResult 链路。
 */
public final class XmlPreviewService {

    public static final XmlPreviewService INSTANCE = new XmlPreviewService();

    private static final Pattern WHITESPACE_REGEX = Pattern.compile("\\s+");
    /** T-M25.2：OGNL 静态字段引用 `@类全名@字段名`（类名可含 `$` 内部类）。 */
    private static final Pattern STATIC_REF_REGEX = Pattern.compile("@([A-Za-z_$][\\w.$]*)@(\\w+)");

    private XmlPreviewService() {
    }

    @NotNull
    public PreviewResult preview(@NotNull String fileText, @Nullable String statementId) {
        return preview(fileText, statementId, null, null);
    }

    @NotNull
    public PreviewResult preview(@NotNull String fileText, @Nullable String statementId, @Nullable Project project) {
        return preview(fileText, statementId, project, null);
    }

    @NotNull
    public PreviewResult preview(@NotNull String fileText, @Nullable String statementId,
                                 @Nullable Project project, @Nullable SqlCompletionContext entityContext) {
        XmlPreviewModel model;
        List<String> staticWarnings;
        try {
            XmlMapperModel parsed = new XmlMapperParser().parse(fileText);
            XmlStatement statement = null;
            if (statementId != null) {
                for (XmlStatement s : parsed.statements()) {
                    if (statementId.equals(s.id())) {
                        statement = s;
                        break;
                    }
                }
            }
            if (statement == null) {
                for (XmlStatement s : parsed.statements()) {
                    statement = s;
                    break;
                }
            }
            if (statement == null) {
                return emptyResult("未找到可预览的 SQL 语句（mapper 中无 select/insert/update/delete）。");
            }
            List<String> warnings = new ArrayList<>();
            Map<String, Object> staticValues = resolveStaticFields(fileText, project, warnings);
            Map<String, Object> ewBindings = resolveEwPlaceholders(fileText, parsed.namespace(), statement.id(), project, warnings);
            Map<String, Boolean> paramQuotes = XmlParamTypeResolver.INSTANCE.resolveQuotes(project, parsed.namespace(), statement.id());
            model = XmlPreviewModel.from(statement, parsed.errors(), Set.of(), staticValues, ewBindings, paramQuotes);
            staticWarnings = warnings;
        } catch (XmlParseException e) {
            return emptyResult(e.getMessage() != null ? e.getMessage() : "XML 解析失败。");
        } catch (XmlScriptException e) {
            return emptyResult(e.getMessage() != null ? e.getMessage() : "SQL 脚本解析失败。");
        }

        return toResult(model, staticWarnings, entityContext);
    }

    /**
     * T-M26-B：MP 占位展开——扫描 `${变量.customSqlSegment/sqlSelect/sqlSet}`，
     * 经 XmlEwResolver 关联 Java Mapper 方法 wrapper 实参展开为 SQL 片段，
     * 包装为 MockObject 注入 mock bindings。
     */
    private Map<String, Object> resolveEwPlaceholders(String fileText, String namespace,
                                                     String statementId, Project project, List<String> warnings) {
        List<cn.song.mybatisplus.visualizer.xml.EwPlaceholder> placeholders = XmlEwExpander.placeholders(fileText);
        if (placeholders.isEmpty() || project == null) return java.util.Collections.emptyMap();
        Map<String, Map<String, String>> resolved =
                XmlEwResolver.INSTANCE.resolve(namespace, statementId, placeholders, project, warnings);
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> e : resolved.entrySet()) {
            result.put(e.getKey(), new MockObject(new HashMap<>(e.getValue())));
        }
        return result;
    }

    /**
     * 静态字段引用解析（T-M25.2）：扫描文本中的 `@类全名@字段名`（OGNL 静态访问），
     * 经 PSI 定位用户项目中的常量类与字段初始值。
     */
    public Map<String, Object> resolveStaticFields(String fileText, Project project, List<String> warnings) {
        if (project == null) return java.util.Collections.emptyMap();
        Set<String> refs = new java.util.LinkedHashSet<>();
        Matcher m = STATIC_REF_REGEX.matcher(fileText);
        while (m.find()) {
            refs.add(m.group(1) + "@" + m.group(2));
        }
        if (refs.isEmpty()) return java.util.Collections.emptyMap();
        Map<String, Object> result = new HashMap<>();
        for (String ref : refs) {
            String[] parts = ref.split("@", 2);
            String className = parts[0];
            String fieldName = parts[1];
            String value = resolveStaticFieldValue(project, className, fieldName);
            if (value == null) {
                warnings.add("静态常量 " + ref + " 无法解析（类/字段不存在或非常量），预览按空值处理，可在预览中填写覆盖值");
            } else {
                result.put(ref, value);
            }
        }
        return result;
    }

    /** 单字段求值：findClass → findFieldByName → 字段初始值常量求值。 */
    public String resolveStaticFieldValue(Project project, String className, String fieldName) {
        PsiClass psiClass = JavaPsiFacade.getInstance(project)
                .findClass(className, GlobalSearchScope.allScope(project));
        if (psiClass == null) return null;
        PsiField field = psiClass.findFieldByName(fieldName, false);
        if (field == null) return null;
        PsiExpression initializer = field.getInitializer();
        if (initializer == null) return null;
        Object constVal = JavaPsiFacade.getInstance(project).getConstantEvaluationHelper()
                .computeConstantExpression(initializer);
        return constVal != null ? constVal.toString() : null;
    }

    /** 预览模型 → PreviewResult（T-M25-02 注解预览共用）：初始渲染 + 参数对照 + 错误合并。 */
    public PreviewResult toResult(XmlPreviewModel model) {
        return toResult(model, java.util.Collections.emptyList(), null);
    }

    public PreviewResult toResult(XmlPreviewModel model, List<String> extraWarnings) {
        return toResult(model, extraWarnings, null);
    }

    public PreviewResult toResult(XmlPreviewModel model, List<String> extraWarnings, SqlCompletionContext entityContext) {
        RenderedXmlSql rendered = model.renderSql();
        List<String> errors = new ArrayList<>(model.getErrors());
        errors.addAll(extraWarnings);
        errors.addAll(rendered.errors());

        String sql = rendered.sql();
        List<String> unresolvedParams = new ArrayList<>();
        for (ParameterMapping mp : rendered.mappings()) {
            unresolvedParams.add(mp.property());
        }
        String paramTable = ParameterReplacer.INSTANCE.buildParamTable(unresolvedParams);

        String joined = String.join("；", errors);
        String warning = joined.isEmpty() ? null : joined;

        return new PreviewResult(
                sql,
                WHITESPACE_REGEX.matcher(sql).replaceAll(" ").trim(),
                unresolvedParams,
                paramTable,
                null,
                entityContext != null ? entityContext.tableName : null,
                warning,
                entityContext != null ? entityContext.columns : List.of(),
                entityContext != null ? entityContext.columnComments : Map.of(),
                entityContext != null ? entityContext.tableComment : null,
                null,
                List.of(),
                List.of(),
                null,
                model,
                Map.of(),
                null,
                null,
                null,
                null
        );
    }

    private PreviewResult emptyResult(String message) {
        return new PreviewResult(
                "",
                "",
                List.of(),
                null,
                null,
                null,
                message,
                List.of(),
                Map.of(),
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                null
        );
    }
}
