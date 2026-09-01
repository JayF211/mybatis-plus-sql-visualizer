package cn.song.mybatisplus.visualizer.ui.highlight;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.sql.SqlClause;
import cn.song.mybatisplus.visualizer.sql.SqlTextAnalysisKt;
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
 * 防御：任何异常 → 空列表（不抛，补全静默降级）；offset 越界 clamp（{@link SqlTextAnalysisKt} 已防御，
 * 本模块再 clamp 一次用于 substring）。
 */
public final class SourceSqlCompleter {

    public static final SourceSqlCompleter INSTANCE = new SourceSqlCompleter();

    /**
     * 返回未过滤前缀的补全项（关键字/表名/列名/别名列/{@code #{}` 参数名}）。
     *
     * @param text    语句文本（已 stripXmlTags 等长 / 注解常量求值文本）
     * @param offset  光标在 text 中的偏移（0..text.length，越界 clamp）
     * @param context 补全上下文（主表 + 联表候选 + 参数名；XmlSqlCompletionContextResolver 产物）
     */
    public static List<LookupElementBuilder> sqlCompletions(String text, int offset, SourceSqlContext context) {
        try {
            int safeOffset = Math.max(0, Math.min(offset, text.length()));
            String before = text.substring(0, safeOffset);
            SqlClause clause = SqlTextAnalysisKt.detectClause(text, safeOffset);
            Map<String, TableInfo> aliasMap = bindAliases(SqlTextAnalysisKt.parseTableAliases(text), context);

            List<LookupElementBuilder> result = new ArrayList<>();

            // 关键字：无前缀（光标前无进行中的词）时过滤已在文本中出现的关键字（避免重复建议）
            Matcher prefixM = PREFIX_AT_CARET.matcher(before);
            String typedPrefix = prefixM.find() ? prefixM.group() : "";
            String upperText = text.toUpperCase();
            SqlLexer.KEYWORDS.stream()
                    .filter(keyword -> !typedPrefix.isEmpty() || !upperText.contains(keyword))
                    .forEach(keyword -> result.add(SourceSqlSuggestionFactory.keyword(keyword)));

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
                TableInfo main = context.getMain();
                if (main != null) {
                    for (String col : main.getColumns().stream().distinct().toList()) {
                        result.add(SourceSqlSuggestionFactory.column(col, main.getColumnComments().get(col)));
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

            // 表名：FROM/JOIN 后——主表（表注释）+ candidates 其余表（去重，全给）
            if (clause == SqlClause.TABLE) {
                Set<String> seen = new LinkedHashSet<>();
                TableInfo main = context.getMain();
                if (main != null) {
                    String mainName = main.getTableName();
                    if (mainName != null && seen.add(mainName)) {
                        result.add(SourceSqlSuggestionFactory.table(mainName, main.getTableComment()));
                    }
                }
                for (TableInfo table : context.getCandidates()) {
                    String tName = table.getTableName();
                    if (seen.add(tName)) {
                        result.add(SourceSqlSuggestionFactory.table(tName, table.getComment()));
                    }
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

    /** 光标左侧最近 `别名.` 片段（必须紧贴光标，如 `SELECT u.<caret>`）。 */
    private static final Pattern ALIAS_PREFIX_AT_CARET = Pattern.compile("[\\w$]+\\.$");

    private SourceSqlCompleter() {
    }
}
