package cn.song.mybatisplus.visualizer.ui.highlight;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.sql.SqlClause;
import cn.song.mybatisplus.visualizer.sql.SqlTextAnalysis;
import cn.song.mybatisplus.visualizer.sql.TableAlias;
import cn.song.mybatisplus.visualizer.ui.SourceSqlContext;
import com.intellij.codeInsight.lookup.LookupElementBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 源码 SQL 补全纯逻辑（T-M27-03，零平台注册、零 PSI——纯文本输入）。
 *
 * 背景：XML 语句补全（本里程碑）与注解方法补全（T-M27-05，并行智能体 D 按本契约调用）的 SQL
 * 补全计算收敛为纯函数：输入"语句文本（已 stripXmlTags 等长 / 注解常量求值文本）+ 光标偏移 +
 * {@link SourceSqlContext}"，输出未过滤前缀的补全项；**调用方负责 {@code prefixMatches} 过滤与
 * {@code result.addElement}**（prefixMatcher 来自真实补全会话，本模块不感知）。
 *
 * 防御：任何异常 → 空列表（不抛，补全静默降级）；offset 越界 clamp（{@link SqlTextAnalysis} 已防御，
 * 本模块再 clamp 一次用于 substring）。
 */
public final class SourceSqlCompleter {

    public static final SourceSqlCompleter INSTANCE = new SourceSqlCompleter();

    /**
     * 返回未过滤前缀的补全项（关键字/函数/表名/列名/别名列/{@code #{}} 参数名/投影属性/反引号列名）。
     *
     * @param text    语句文本（已 stripXmlTags 等长 / 注解常量求值文本）
     * @param offset  光标在 text 中的偏移（0..text.length，越界 clamp）
     * @param context 补全上下文（主表 + 联表候选 + 参数名；XmlSqlCompletionContextResolver 产物）
     */
    public static List<LookupElementBuilder> sqlCompletions(String text, int offset, SourceSqlContext context) {
        return sqlCompletions(text, offset, context, List.of(), List.of());
    }

    /**
     * 返回未过滤前缀的补全项（关键字/函数/表名/列名/别名列/{@code #{}} 参数名/投影属性/反引号列名）。
     *
     * @param text    语句文本（已 stripXmlTags 等长 / 注解常量求值文本）
     * @param offset  光标在 text 中的偏移（0..text.length，越界 clamp）
     * @param context 补全上下文（主表 + 联表候选 + 参数名；XmlSqlCompletionContextResolver 产物）
     * @param projectionProperties   T-XmlGap W5-7：SELECT 投影位的额外候选（resultType 类属性，
     *                               由贡献器按 PSI 解析后传入；空=不产）。纯文本层不感知 PSI。
     * @param quotedIdentifierCandidates T-XmlGap W5-8：反引号内的额外候选（本文件 resultMap 的
     *                               column+property，由贡献器传入；空=不产）。
     */
    public static List<LookupElementBuilder> sqlCompletions(
            String text, int offset, SourceSqlContext context,
            List<String> projectionProperties, List<String> quotedIdentifierCandidates) {
        try {
            int safeOffset = Math.max(0, Math.min(offset, text.length()));
            String before = text.substring(0, safeOffset);
            SqlClause clause = SqlTextAnalysis.detectClause(text, safeOffset);
            Map<String, TableInfo> aliasMap = bindAliases(SqlTextAnalysis.parseTableAliases(text), context);

            List<LookupElementBuilder> result = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();

            // 关键字：无前缀（光标前无进行中的词）时过滤已在文本中出现的关键字（避免重复建议）
            Matcher prefixM = PREFIX_AT_CARET.matcher(before);
            String typedPrefix = prefixM.find() ? prefixM.group() : "";
            String upperText = text.toUpperCase();
            SqlLexer.KEYWORDS.stream()
                    .filter(keyword -> !typedPrefix.isEmpty() || !upperText.contains(keyword))
                    .forEach(keyword -> {
                        if (seen.add(keyword)) result.add(SourceSqlSuggestionFactory.keyword(keyword));
                    });

            // T-XmlGap W5-9：SQL 函数表（独立于关键字；与 KEYWORDS 混排的九个函数名由 seen 去重）
            MyBatisXmlCompletionData.SQL_FUNCTIONS.stream()
                    .filter(fn -> !typedPrefix.isEmpty() || !upperText.contains(fn))
                    .forEach(fn -> {
                        if (seen.add(fn)) result.add(SourceSqlSuggestionFactory.keyword(fn));
                    });

            // T-XmlGap W5-9：单字母关键字捷径（已输入恰好 1 个字符时按表补候选；已出现者去重）
            for (String keyword : MyBatisXmlCompletionData.singleLetterShortcuts(typedPrefix)) {
                if (seen.add(keyword)) result.add(SourceSqlSuggestionFactory.keyword(keyword));
            }

            // `别名.` 前缀：命中绑定别名 → 该实体列；未命中 → 跳过列补全（不产出无前缀列）
            Matcher aliasM = ALIAS_PREFIX_AT_CARET.matcher(before);
            String aliasPrefix = aliasM.find() ? aliasM.group().substring(0, aliasM.group().length() - 1) : null;
            if (aliasPrefix != null) {
                TableInfo table = aliasMap.get(aliasPrefix);
                if (table != null) {
                    for (ColumnInfo col : table.getColumns()) {
                        result.add(SourceSqlSuggestionFactory.prefixedColumn(aliasPrefix, col.getColumnName(), col.getComment()));
                    }
                }
            } else if (clause != SqlClause.TABLE) {
                // 主表列（带列注释）
                SqlCompletionContext main = context.getMain();
                if (main != null) {
                    for (String col : main.columns.stream().distinct().toList()) {
                        result.add(SourceSqlSuggestionFactory.column(col, main.columnComments.get(col)));
                    }
                }
                // 全部绑定别名的列（`别名.` 前缀；主表别名也在 candidates 中一并覆盖）
                for (Map.Entry<String, TableInfo> e : aliasMap.entrySet()) {
                    TableInfo table = e.getValue();
                    for (ColumnInfo col : table.getColumns()) {
                        result.add(SourceSqlSuggestionFactory.prefixedColumn(e.getKey(), col.getColumnName(), col.getComment()));
                    }
                }
            }

            // T-XmlGap W5-7：SELECT 投影位（SELECT 与 FROM 之间）额外产出 resultType 类属性
            if (clause == SqlClause.SELECT_COLUMN && !projectionProperties.isEmpty()) {
                for (String prop : projectionProperties) {
                    if (seen.add(prop)) result.add(SourceSqlSuggestionFactory.param(prop));
                }
            }

            // 表名：FROM/JOIN 后——主表（表注释）+ candidates 其余表（去重，全给）
            if (clause == SqlClause.TABLE) {
                Set<String> tableSeen = new LinkedHashSet<>();
                SqlCompletionContext main = context.getMain();
                if (main != null) {
                    String mainName = main.tableName;
                    if (mainName != null && tableSeen.add(mainName)) {
                        result.add(SourceSqlSuggestionFactory.table(mainName, main.tableComment));
                    }
                }
                for (TableInfo table : context.getCandidates()) {
                    String tName = table.getTableName();
                    if (tableSeen.add(tName)) {
                        result.add(SourceSqlSuggestionFactory.table(tName, table.getComment()));
                    }
                }
            }

            // T-XmlGap W5-8：反引号内（`xxx）——产出额外候选（插入串带反引号，成对）
            if (QUOTED_IDENTIFIER_AT_CARET.matcher(before).find()) {
                for (String name : quotedIdentifierCandidates) {
                    result.add(SourceSqlSuggestionFactory.quotedIdentifier(name));
                }
            }

            // `#{}` 参数：左侧最后一个 `#{` 在最后一个 `}` 之后 → 光标处于未闭合参数占位内
            int lastHashOpen = before.lastIndexOf("#{");
            int lastClose = before.lastIndexOf("}");
            if (lastHashOpen > lastClose) {
                for (String p : context.getParamNames()) {
                    result.add(SourceSqlSuggestionFactory.param(p));
                }
            }

            return result;
        } catch (Throwable e) {
            return new ArrayList<>();
        }
    }

    /**
     * 表名→别名绑定（T-M27-03）：词法扫描结果 ↔ candidates 按 tableName **精确匹配**；
     * 未命中候选的表名（外部表/JOIN 不可知表）仅记录别名、不产出补全项。
     * 同一别名多表 → 后者覆盖（associate 语义，罕见场景取最后出现）。
     */
    private static Map<String, TableInfo> bindAliases(List<TableAlias> aliases, SourceSqlContext context) {
        Map<String, TableInfo> result = new LinkedHashMap<>();
        for (TableAlias entry : aliases) {
            String alias = entry.getAlias();
            if (alias == null) {
                continue;
            }
            TableInfo table = null;
            for (TableInfo t : context.getCandidates()) {
                if (t.getTableName().equals(entry.getTable())) {
                    table = t;
                    break;
                }
            }
            if (table == null) {
                continue;
            }
            result.put(alias, table);
        }
        return result;
    }

    /** 光标前进行中的词（补全前缀）；无进行中的词 → 空串（"无前缀"语义，触发关键字去重过滤）。 */
    private static final Pattern PREFIX_AT_CARET = Pattern.compile("[\\w$]*$");

    /** 光标左侧最近 {@code 别名.} 片段（必须紧贴光标，如 {@code SELECT u.<caret>}）；dropLast(1) 得别名。 */
    private static final Pattern ALIAS_PREFIX_AT_CARET = Pattern.compile("[\\w$]+\\.$");

    /** T-XmlGap W5-8：光标处于未闭合反引号内（{@code `xxx}；行内恰有一个前导反引号）。 */
    private static final Pattern QUOTED_IDENTIFIER_AT_CARET = Pattern.compile("`[\\w$]*$");

    private SourceSqlCompleter() {
    }
}
