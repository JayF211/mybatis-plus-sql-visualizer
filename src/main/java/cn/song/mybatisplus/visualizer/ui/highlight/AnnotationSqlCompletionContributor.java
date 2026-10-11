package cn.song.mybatisplus.visualizer.ui.highlight;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.sql.SqlTextAnalysis;
import cn.song.mybatisplus.visualizer.ui.AnnotationSqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.SourceSqlContext;
import cn.song.mybatisplus.visualizer.ui.XmlSqlCompletionContextResolver;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.completion.PrefixMatcher;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java SQL 注解字符串代码补全贡献器（T-M27-05，M27 架构 注解场景）。
 *
 * 背景：M27 将源码 SQL 补全接入 Java Mapper 接口——@Select/@Insert/@Update/@Delete 的 value
 * 字符串（含数组元素 / {@code +} 拼接 / {@code <script>} 动态标签前缀）内部 Ctrl+Space 补全关键字 / 表名 /
 * 列名 / 别名前缀列 / {@code #{}` 参数名，与 M16 预览编辑器补全（{@link SqlCompletionContributor}）并列。
 * 补全逻辑（候选生成）复用共享契约 {@link SourceSqlCompleter}，本类只做**守卫 + 文本提取 + 上下文解析**。
 *
 * 注册（plugin.xml {@code <completion.contributor>}）由主控统一完成；本类实现 {@link DumbAware}——
 * dumb 模式下关键字补全与降级上下文仍可用（上下文解析内部已 runCatching）。
 */
public class AnnotationSqlCompletionContributor extends CompletionContributor implements DumbAware {

    public AnnotationSqlCompletionContributor() {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), new CompletionProvider<CompletionParameters>() {
            @Override
            protected void addCompletions(CompletionParameters parameters, ProcessingContext context, CompletionResultSet result) {
                // 守卫 1：光标在字符串字面量内（position 本身或祖先；strict=false 覆盖 position 即字面量叶子）
                PsiLiteralExpression literal = PsiTreeUtil.getParentOfType(parameters.getPosition(), PsiLiteralExpression.class, false);
                if (literal == null) {
                    return;
                }
                // 守卫 2：字符串值（数值/布尔/字符字面量非 SQL 文本）
                if (!(literal.getValue() instanceof String)) {
                    return;
                }
                // 守卫 3：字面量所属注解是字面量 SQL 注解（Provider 注解 SQL 由 Java 生成，不补全）
                PsiAnnotation annotation = PsiTreeUtil.getParentOfType(literal, PsiAnnotation.class, false);
                if (annotation == null) {
                    return;
                }
                if (!AnnotationSqlPreviewService.INSTANCE.isSqlAnnotation(annotation)) {
                    return;
                }
                String qualifiedName = annotation.getQualifiedName();
                String shortName = qualifiedName != null ? qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1) : null;
                if (shortName == null) {
                    return;
                }
                if (!AnnotationSqlPreviewService.LITERAL_ANNOTATIONS.contains(shortName)) {
                    return;
                }
                // 守卫 4：字面量是该注解的 value 或 value 数组元素（不放大到非 value 属性）
                if (!isAnnotationValueMember(annotation, literal)) {
                    return;
                }

                // 文本提取：常量求值（数组取当前元素 / + 拼接取整段），失败空串 → return
                String raw = extractSqlText(annotation, literal, parameters.getPosition().getProject());
                if (raw.isEmpty()) {
                    return;
                }
                SourceSqlContext sourceContext = resolveSourceContext(parameters, annotation);
                PrefixMatcher prefix = result.getPrefixMatcher();
                // T-M27.5：`<script>` 动态标签补全（与 XML mapper 一致：标签名/属性/OGNL，文本级判断）
                int caretInRaw = Math.max(0, Math.min(parameters.getOffset() - literal.getTextRange().getStartOffset(), raw.length()));
                String beforeCaret = raw.substring(0, caretInRaw);
                if (SourceSqlSuggestionFactory.DYNAMIC_TAG_PATTERN.matcher(beforeCaret).find()) {
                    for (String tag : SourceSqlSuggestionFactory.DYNAMIC_TAGS) {
                        if (prefix.prefixMatches(tag)) {
                            result.addElement(SourceSqlSuggestionFactory.xmlTag(tag));
                        }
                    }
                    return;
                }
                if (SourceSqlSuggestionFactory.ATTRIBUTE_POSITION_PATTERN.matcher(beforeCaret).find()) {
                    Matcher tm = TAG_NAME_AT_CARET.matcher(beforeCaret);
                    String tagName = tm.find() ? tm.group(1) : null;
                    List<String> attrs = SourceSqlSuggestionFactory.DYNAMIC_TAG_ATTRIBUTES.get(tagName);
                    if (attrs != null) {
                        for (String attr : attrs) {
                            if (prefix.prefixMatches(attr)) {
                                result.addElement(SourceSqlSuggestionFactory.xmlAttribute(attr));
                            }
                        }
                    }
                    return;
                }
                if (SourceSqlSuggestionFactory.OGNL_ATTRIBUTE_PATTERN.matcher(beforeCaret).find()) {
                    addOgnlCompletions(sourceContext, beforeCaret, prefix, result);
                    return;
                }
                // <script>/XML 标签等长剥离（偏移量 1:1，offset 无需换算）
                String text = SqlTextAnalysis.stripXmlTags(raw);
                try {
                    List<LookupElementBuilder> items = SourceSqlCompleter.sqlCompletions(text, caretInRaw, sourceContext);
                    for (LookupElementBuilder item : items) {
                        // T-XmlCmpl3 二段：过滤按**任一 lookup string**（附加串 `别名.列名`
                        // 命中已敲的 `o.user_i`，插入用主串裸列名——防别名重复）
                        if (item.getAllLookupStrings().stream().anyMatch(prefix::prefixMatches)) {
                            result.addElement(item);
                        }
                    }
                } catch (Throwable e) {
                    // 静默降级
                }
            }
        });
    }

    /** 属性名位置的标签名提取（`<foreach  ` → foreach）。 */
    private static final Pattern TAG_NAME_AT_CARET = Pattern.compile("<(\\w+)\\s+[\\w-]*$");

    /** OGNL 补全：test/collection 值内——参数名 + 主表实体字段（`param.` 前缀时仅第二段字段）。 */
    private void addOgnlCompletions(SourceSqlContext context, String beforeCaret, PrefixMatcher prefix, CompletionResultSet result) {
        SqlCompletionContext main = context.getMain();
        String mainTableName = main != null ? main.tableName : null;
        List<String> fieldNames = context.getCandidates().stream()
                .filter(it -> it.getTableName().equals(mainTableName))
                .findFirst()
                .map(t -> t.getColumns().stream().map(ColumnInfo::getFieldName).toList())
                .orElse(List.of());
        if (SourceSqlSuggestionFactory.OGNL_PREFIX_PATTERN.matcher(beforeCaret).find()) {
            for (String name : fieldNames) {
                if (prefix.prefixMatches(name)) {
                    result.addElement(SourceSqlSuggestionFactory.param(name));
                }
            }
        } else {
            for (String name : context.getParamNames()) {
                if (prefix.prefixMatches(name)) {
                    result.addElement(SourceSqlSuggestionFactory.param(name));
                }
            }
            for (String name : fieldNames) {
                if (prefix.prefixMatches(name)) {
                    result.addElement(SourceSqlSuggestionFactory.param(name));
                }
            }
        }
    }

    /**
     * 求值对象选择 + 常量求值（T-M27-05）：
     *  - value 即当前字面量（单值 / value = 命名属性）→ 整个 value 求值；
     *  - value 是数组 → 只求值当前元素（数组整体不可折叠为 String）；
     *  - value 是 {@code +} 拼接（PsiPolyadicExpression）→ 整段求值；
     *  - 其余（守卫已放行的异常形态）→ 退回字面量本身求值。
     */
    private String extractSqlText(PsiAnnotation annotation, PsiLiteralExpression literal, Project project) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(null);
        PsiAnnotationMemberValue memberValue;
        if (value != null && value == literal) {
            memberValue = value;
        } else if (value instanceof PsiArrayInitializerMemberValue) {
            memberValue = literal;
        } else if (value instanceof PsiPolyadicExpression) {
            memberValue = value;
        } else {
            memberValue = literal;
        }
        return AnnotationSqlPreviewService.INSTANCE.constantString(memberValue, project, new ArrayList<>());
    }

    /**
     * 守卫 4：字面量是否为注解 value 的一部分（T-M27-05）。
     */
    private boolean isAnnotationValueMember(PsiAnnotation annotation, PsiLiteralExpression literal) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(null);
        if (value != null) {
            return PsiTreeUtil.isAncestor(value, literal, false);
        }
        if (annotation.getParameterList() == null) {
            return false;
        }
        for (PsiNameValuePair pair : annotation.getParameterList().getAttributes()) {
            PsiAnnotationMemberValue pv = pair.getValue();
            String name = pair.getName();
            if ((name == null || name.equals("value")) && pv != null && PsiTreeUtil.isAncestor(pv, literal, false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 注解方法 → 补全上下文（T-M27-05）：method = 注解所在 PsiMethod；
     * 方法缺失（类级注解等异常形态）或解析异常 → 空上下文兜底（关键字补全不受影响，不抛异常）。
     */
    private SourceSqlContext resolveSourceContext(CompletionParameters parameters, PsiAnnotation annotation) {
        try {
            PsiMethod method = PsiTreeUtil.getParentOfType(annotation, PsiMethod.class, false);
            if (method == null) {
                return new SourceSqlContext(null, List.of(), List.of());
            } else {
                return XmlSqlCompletionContextResolver.INSTANCE.resolveAnnotation(parameters.getPosition().getProject(), method);
            }
        } catch (Throwable e) {
            return new SourceSqlContext(null, List.of(), List.of());
        }
    }
}
