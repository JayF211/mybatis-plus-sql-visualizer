package cn.song.mybatisplus.visualizer.ui.highlight;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.sql.SqlTextAnalysis;
import cn.song.mybatisplus.visualizer.ui.SourceSqlContext;
import cn.song.mybatisplus.visualizer.ui.XmlSqlCompletionContextResolver;
import cn.song.mybatisplus.visualizer.ui.gutter.XmlSqlGutterLineMarkerProvider;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.completion.PrefixMatcher;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ProcessingContext;

import java.util.Arrays;
import java.util.List;

/**
 * Mapper XML 源码 SQL 补全贡献器（T-M27-03 + T-M27-04）。
 *
 * 背景：为 {@code <select|insert|update|delete>} 语句提供四类补全，全部基于**纯文本**分析
 * （{@link SqlTextAnalysis#stripXmlTags} 等长剥离 + {@link SourceSqlCompleter}），零 PSI 语义分析：
 *  a. 动态标签补全（T-M27-04）
 *  b. 属性补全
 *  c. OGNL 值内补全（T-M27-04）
 *  d. SQL 补全
 *
 * 守卫（性能红线，O(1) 语法判定，零 resolve）：position 祖先链上的**最近语句标签**
 * （select/insert/update/delete）且根标签名 == "mapper"，否则直接 return。
 *
 * 注册见 plugin.xml {@code <completion.contributor language="XML" .../>}（主控统一注册，本类不声明扩展）。
 */
public class XmlSqlCompletionContributor extends CompletionContributor {

    public XmlSqlCompletionContributor() {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), new CompletionProvider<CompletionParameters>() {
            @Override
            protected void addCompletions(CompletionParameters parameters, ProcessingContext context, CompletionResultSet result) {
                // 守卫：祖先链最近语句标签 + 根 <mapper>（O(1) 语法判定）
                XmlTag statementTag = findStatementTag(parameters.getPosition());
                if (statementTag == null) {
                    return;
                }
                if (!rootTag(statementTag).getName().equals("mapper")) {
                    return;
                }

                PrefixMatcher prefix = result.getPrefixMatcher();
                String fileText = parameters.getPosition().getContainingFile() != null ? parameters.getPosition().getContainingFile().getText() : "";
                int statementStart = statementTag.getTextRange().getStartOffset();
                String statementText = fileText.substring(statementStart, statementTag.getTextRange().getEndOffset());
                int caretInStatement = Math.max(0, Math.min(parameters.getOffset() - statementStart, statementText.length()));
                String beforeCaret = statementText.substring(0, caretInStatement);

                // a. 动态标签：光标前非空白字符是 `<`
                if (SourceSqlSuggestionFactory.DYNAMIC_TAG_PATTERN.matcher(beforeCaret).find()) {
                    for (String tag : SourceSqlSuggestionFactory.DYNAMIC_TAGS) {
                        if (prefix.prefixMatches(tag)) {
                            result.addElement(SourceSqlSuggestionFactory.xmlTag(tag));
                        }
                    }
                    return;
                }

                // c. OGNL：position 在 test/collection 属性值内
                XmlAttributeValue attributeValue = PsiTreeUtil.getParentOfType(parameters.getPosition(), XmlAttributeValue.class, false);
                if (attributeValue != null) {
                    String attributeName = attributeValue.getParent() instanceof XmlAttribute ? ((XmlAttribute) attributeValue.getParent()).getName() : null;
                    if (attributeName != null && (attributeName.equals("test") || attributeName.equals("collection"))) {
                        addOgnlCompletions(statementTag, beforeCaret, result);
                    }
                    return;
                }

                // b. 属性名位置
                XmlTag nearestTag = PsiTreeUtil.getParentOfType(parameters.getPosition(), XmlTag.class);
                boolean attributeNamePosition = nearestTag != null && (
                        parameters.getPosition().getParent() instanceof XmlAttribute ||
                                SourceSqlSuggestionFactory.ATTRIBUTE_POSITION_PATTERN.matcher(beforeCaret).find()
                );
                if (attributeNamePosition) {
                    List<String> attrs = SourceSqlSuggestionFactory.DYNAMIC_TAG_ATTRIBUTES.get(nearestTag.getName());
                    for (String attribute : (attrs != null ? attrs : List.<String>of())) {
                        if (prefix.prefixMatches(attribute)) {
                            result.addElement(SourceSqlSuggestionFactory.xmlAttribute(attribute));
                        }
                    }
                    return;
                }

                // d. SQL 补全：语句文本等长剥离标签 → 纯文本分析 → prefixMatches 过滤
                String stripped = SqlTextAnalysis.stripXmlTags(statementText);
                SourceSqlContext sqlContext = resolveContext(statementTag);
                for (LookupElementBuilder item : SourceSqlCompleter.sqlCompletions(stripped, caretInStatement, sqlContext)) {
                    if (prefix.prefixMatches(item.getLookupString())) {
                        result.addElement(item);
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // 分支实现
    // ------------------------------------------------------------------

    /**
     * OGNL 值内补全（T-M27-04）：方法参数名 + 主实体 Java 字段名（fieldName）。
     * 光标前有 {@code param.} 前缀 → 只补第二段字段名；否则参数名 + 字段名都补。
     */
    private void addOgnlCompletions(XmlTag statementTag, String beforeCaret, CompletionResultSet result) {
        SourceSqlContext context = resolveContext(statementTag);
        PrefixMatcher prefix = result.getPrefixMatcher();
        List<String> fieldNames = mainEntityFieldNames(context);
        boolean secondSegment = SourceSqlSuggestionFactory.OGNL_PREFIX_PATTERN.matcher(beforeCaret).find();
        if (secondSegment) {
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

    /** 主实体 Java 字段名（OGNL 用 fieldName）：candidates 中 tableName == main.tableName 的 TableInfo 的列字段名。 */
    private List<String> mainEntityFieldNames(SourceSqlContext context) {
        SqlCompletionContext main = context.getMain();
        String mainTableName = main != null ? main.tableName : null;
        if (mainTableName == null) {
            return List.of();
        }
        for (TableInfo t : context.getCandidates()) {
            if (t.getTableName().equals(mainTableName)) {
                return t.getColumns().stream().map(ColumnInfo::getFieldName).toList();
            }
        }
        return List.of();
    }

    /** 补全上下文解析（实体索引/注解解析仅在补全触发时执行一次；异常 → 空上下文，只补关键字）。 */
    private SourceSqlContext resolveContext(XmlTag statementTag) {
        try {
            return XmlSqlCompletionContextResolver.INSTANCE.resolveXml(statementTag.getProject(), statementTag);
        } catch (Throwable e) {
            return EMPTY_SOURCE_SQL_CONTEXT;
        }
    }

    // ------------------------------------------------------------------
    // 守卫
    // ------------------------------------------------------------------

    /**
     * 祖先链上**最近语句标签**（select/insert/update/delete）。
     */
    private XmlTag findStatementTag(PsiElement position) {
        PsiElement element = position;
        while (element != null) {
            if (element instanceof XmlTag && XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS.contains(((XmlTag) element).getName())) {
                return (XmlTag) element;
            }
            element = element.getParent();
        }
        return null;
    }

    /** 沿 parentTag 链取根标签（`<mapper>` 判定用）。 */
    private XmlTag rootTag(XmlTag statementTag) {
        XmlTag tag = statementTag;
        while (tag.getParentTag() != null) {
            tag = tag.getParentTag();
        }
        return tag;
    }

    // ------------------------------------------------------------------
    // 静态数据
    // ------------------------------------------------------------------

    /** 动态标签补全清单（T-M27-04：MyBatis 动态 SQL 全部标签）。 */
    private static final List<String> DYNAMIC_TAGS = List.copyOf(Arrays.asList(
            "if", "foreach", "where", "set", "trim", "choose", "when", "otherwise", "bind", "sql", "include"
    ));

    /** resolve 失败降级上下文（只补关键字/动态标签，不阻塞补全）。 */
    private static final SourceSqlContext EMPTY_SOURCE_SQL_CONTEXT = new SourceSqlContext(null, List.of(), List.of());
}
