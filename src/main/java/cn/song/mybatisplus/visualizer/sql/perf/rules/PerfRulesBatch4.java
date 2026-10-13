package cn.song.mybatisplus.visualizer.sql.perf.rules;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.MariaDbDialect;
import cn.song.mybatisplus.visualizer.sql.MySqlDialect;
import cn.song.mybatisplus.visualizer.sql.OceanBaseDialect;
import cn.song.mybatisplus.visualizer.sql.perf.DialectAwarePerfRule;
import cn.song.mybatisplus.visualizer.sql.perf.PerfContext;
import cn.song.mybatisplus.visualizer.sql.perf.PerfRule;
import cn.song.mybatisplus.visualizer.sql.perf.PerfRuleEngine;
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity;
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning;

import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 第四批性能规则（方言感知，T-DialectQuoteWarn）。
 *
 * <p>与前三批的差别：本批规则的正确性依赖当前数据库方言，实现 {@link DialectAwarePerfRule}，
 * 由 {@link PerfRuleEngine#apply} 在拿到方言时改调 {@link DialectAwarePerfRule#check(PerfContext)}。</p>
 *
 * <p>规则清单：
 * <ul>
 *   <li>{@code dialect_quote_mismatch}：标识符用了当前方言不接受的引号字符（如 MySQL 方言下出现
 *       {@code FROM "user" t}）。</li>
 * </ul></p>
 */
public final class PerfRulesBatch4 {

    /** 单例（兼容 Kotlin {@code object} 语义）。 */
    @NotNull
    public static final PerfRulesBatch4 INSTANCE = new PerfRulesBatch4();

    /** 所有第四批规则实例。 */
    @NotNull
    public static final List<PerfRule> all = List.of(
            DialectQuoteMismatchRule.INSTANCE
    );

    private PerfRulesBatch4() {
    }

    /**
     * {@code dialect_quote_mismatch}：SQL 里出现「<b>已知标识符引号字符</b>（双引号 {@code "} / 反引号
     * {@code `} / 方括号 {@code []}）包裹的标识符，而该字符不在当前方言的<b>可接受引号集合</b>
     * （{@link Dialect#getIdentifierQuotes()}）内」→ 告警。
     *
     * <p><b>只报告不改写</b>：本规则不触碰 SQL 文本。理由见计划 §一——插件渲染的是 MyBatis-Plus 真实
     * 发出的 SQL，把它改写成「能跑」的形态会让预览偏离真机、把用户代码里的真 bug 静默抹平。</p>
     *
     * <p>判定路径：
     * <ul>
     *   <li>AST 可用（首选）：用 {@link TablesNamesFinder} 遍历语句树，收集表名与列名——JSqlParser 5.3 对
     *       {@code "user"} / {@code `user`} / {@code [user]} 均<b>保留原始引号</b>（{@code Table.getName()} /
     *       {@code Column.getColumnName()} 实测），且字符串字面量里的引号（{@code 'say "hi" '}）不是标识符节点，
     *       <b>天然不误伤</b>；</li>
     *   <li>AST 为 null（降级）：正则扫描 SQL 文本，<b>先剥离单引号字符串字面量与注释</b>，再找引号包裹的标识符。</li>
     * </ul></p>
     *
     * <p>严重度分两档（T-DialectQuoteWarn §十二）：
     * <ul>
     *   <li><b>无歧义档 → ERROR</b>：PG / Oracle / DM / KingBase / openGauss 遇反引号、非 SQL Server 遇方括号
     *       等——这些引号字符在对应方言下<b>一定</b>不是标识符引号，报语法错无例外；</li>
     *   <li><b>MySQL 家族（MySQL / MariaDB / OceanBase）+ 双引号 → 至多 WARN</b>：双引号是否是标识符取决于
     *       连接的 {@code sql_mode} 是否含 {@code ANSI_QUOTES}。<b>URL 只能证明「合法」，不能证明「非法」</b>
     *       ——生效 {@code sql_mode} = 服务端全局 + 会话覆盖，URL 里的 {@code sessionVariables} 只是会话覆盖，
     *       未声明时服务端全局仍可能开着。故：
     *       <ul>
     *         <li>{@link PerfContext#ansiQuotes()} 为 {@code true}（URL 明示声明）→ <b>不报</b>（已确知合法）；</li>
     *         <li>{@code false}（已知但未声明）或 {@code null}（未知）→ WARN，文案带前提（若实际未开启则报语法错）。</li>
     *       </ul>
     *       缓存键含该三态，见 {@code cn.song.mybatisplus.visualizer.sql.SqlPerfAnalyzer}。</li>
     * </ul></p>
     *
     * <p>无方言上下文（{@code dialect == null}）或方言未声明引号集合（基类兜底空集）→ 返回空列表（宁可不报）。
     * 命中时<b>至多一条</b>告警：优先报无歧义档（MySQL 家族下同时出现 {@code "x"} 与 {@code [y]} 时，
     * {@code [y]} 的 ERROR 不被双引号的 WARN 掩盖）。</p>
     */
    public static final class DialectQuoteMismatchRule implements DialectAwarePerfRule {

        @NotNull
        public static final DialectQuoteMismatchRule INSTANCE = new DialectQuoteMismatchRule();

        private DialectQuoteMismatchRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "dialect_quote_mismatch";
        }

        /** 无方言上下文的降级：不做判定（{@link DialectAwarePerfRule} 契约要求实现，语义即「不报」）。 */
        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            return List.of();
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull PerfContext ctx) {
            Dialect dialect = ctx.dialect();
            if (dialect == null) return List.of();
            Set<Character> accepted = dialect.getIdentifierQuotes();
            // 空集 = 基类兜底「不判」（无方言上下文时宁可不报也不误报）
            if (accepted.isEmpty()) return List.of();

            List<String> identifiers = ctx.ast() != null
                    ? collectIdentifiersFromAst(ctx.ast())
                    : collectIdentifiersFromText(ctx.sql());

            Set<Character> offendingSet = new LinkedHashSet<>();
            for (String identifier : identifiers) {
                Character quote = quoteCharOf(identifier);
                if (quote != null && !accepted.contains(quote)) {
                    offendingSet.add(quote);
                }
            }
            if (offendingSet.isEmpty()) return List.of();
            List<Character> offending = new ArrayList<>(offendingSet);

            // 无歧义档优先：MySQL 家族之外的引号不匹配（或 MySQL 家族下的方括号等）一定报语法错 → ERROR
            Character unambiguous = null;
            for (Character quote : offending) {
                if (!isAmbiguousMysqlDoubleQuote(dialect, quote)) {
                    unambiguous = quote;
                    break;
                }
            }
            if (unambiguous != null) {
                return List.of(
                        new PerfWarning(
                                getRuleId(),
                                PerfSeverity.ERROR,
                                "perf.dialect_quote_mismatch",
                                List.of(quoteDisplay(unambiguous), dialect.getName(), acceptedDisplay(accepted))
                        )
                );
            }

            // 只剩「MySQL 家族 + 双引号」这一档：URL 只能证明合法、不能证明非法（§十二），故至多 WARN。
            // 已确知连接声明了 ANSI_QUOTES → 不报。
            if (Boolean.TRUE.equals(ctx.ansiQuotes())) return List.of();
            return List.of(
                    new PerfWarning(
                            getRuleId(),
                            PerfSeverity.WARN,
                            "perf.dialect_quote_mismatch_ansi_quotes",
                            List.of(quoteDisplay(offending.get(0)), dialect.getName(), acceptedDisplay(accepted))
                    )
            );
        }
    }

    /** MySQL 家族（双引号是否为标识符取决于连接 {@code sql_mode} 是否含 {@code ANSI_QUOTES}）。 */
    private static final Set<Dialect> MYSQL_FAMILY = Set.of(
            MySqlDialect.INSTANCE, MariaDbDialect.INSTANCE, OceanBaseDialect.INSTANCE);

    /**
     * 「MySQL 家族 + 双引号」＝判定<b>有歧义</b>的档：URL 只能证明合法、不能证明非法，故不得用 ERROR。
     * 其余组合（PG 族遇反引号、非 SQL Server 遇方括号等）引号字符一定不是标识符引号 → 无歧义。
     */
    private static boolean isAmbiguousMysqlDoubleQuote(Dialect dialect, char quote) {
        return quote == '"' && MYSQL_FAMILY.contains(dialect);
    }

    // ------------------------------------------------------------------
    // 标识符收集
    // ------------------------------------------------------------------

    /**
     * AST 路径：遍历语句树收集<b>表名 + 列名</b>（含 JOIN / 子查询内的）。
     *
     * <p>复用 JSqlParser 官方 {@link TablesNamesFinder} 的整树遍历（它实现了 SelectVisitor / ExpressionVisitor /
     * SelectItemVisitor 等全部访问面），仅覆写 visit 把表名与列名的<b>原始文本</b>（引号保留）记下来。
     * {@code getTables(ast)} 只是驱动这次遍历的入口，其返回值（去重后的表集合）不直接使用。</p>
     */
    private static List<String> collectIdentifiersFromAst(Statement ast) {
        IdentifierCollector collector = new IdentifierCollector();
        // 驱动整树遍历；收集是 visit 覆写的副作用（返回值此处不用）
        collector.getTables(ast);
        return collector.identifiers;
    }

    private static final class IdentifierCollector extends TablesNamesFinder<Object> {
        final List<String> identifiers = new ArrayList<>();

        @Override
        public <S> Object visit(Table table, S context) {
            String name = table.getName();
            if (name != null) identifiers.add(name);
            String schemaName = table.getSchemaName();
            if (schemaName != null) identifiers.add(schemaName);
            return super.visit(table, context);
        }

        @Override
        public <S> Object visit(Column column, S context) {
            String columnName = column.getColumnName();
            if (columnName != null) identifiers.add(columnName);
            return super.visit(column, context);
        }
    }

    /**
     * 正则降级路径（ast == null 时）：剥离单引号字符串字面量与注释后，找出引号包裹的标识符。
     *
     * <p>单引号字符串字面量里的引号（{@code WHERE name = 'say "hi" '}）<b>不得</b>触发告警——这是降级路径
     * 与 AST 路径的对齐点（AST 路径靠节点类型天然排除）。</p>
     */
    private static List<String> collectIdentifiersFromText(String sql) {
        String stripped = stripLiteralsAndComments(sql);
        List<String> result = new ArrayList<>();
        Matcher m = QUOTED_IDENTIFIER.matcher(stripped);
        while (m.find()) {
            result.add(m.group());
        }
        return result;
    }

    /** 逐字符剥离：单引号字符串（含 {@code ''} 转义）、{@code --} 行注释、{@code /* *}{@code /} 块注释。 */
    private static String stripLiteralsAndComments(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'') {
                // 单引号字符串：连写两个单引号是一个转义的单引号
                i++;
                while (i < sql.length()) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) i++;
                if (i + 1 < sql.length()) i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 引号判定与展示
    // ------------------------------------------------------------------

    /**
     * 取标识符的开引号字符（仅当<b>首尾成对</b>为已知标识符引号时）：
     * {@code "x"} → {@code "}、{@code `x`} → {@code `}、{@code [x]} → {@code [}；其余返回 null。
     *
     * <p>「首尾成对」口径见计划 §六：不做逐字符扫描（反引号可能出现在标识符内部，罕见）。</p>
     */
    @Nullable
    private static Character quoteCharOf(String identifier) {
        if (identifier.length() < 2) return null;
        char first = identifier.charAt(0);
        char last = identifier.charAt(identifier.length() - 1);
        if (first == '"' && last == '"') return '"';
        if (first == '`' && last == '`') return '`';
        if (first == '[' && last == ']') return '[';
        return null;
    }

    private static String quoteDisplay(char ch) {
        return String.valueOf(ch);
    }

    /** 可接受引号集合的展示串（排序后以 {@code /} 连接，如 SQLite 的 {@code " / [ / `}）。 */
    private static String acceptedDisplay(Set<Character> accepted) {
        return accepted.stream().map(String::valueOf).sorted().collect(Collectors.joining("/"));
    }

    /** 引号包裹的标识符：双引号 / 反引号 / 方括号三种形态（已剥离字面量与注释的文本上匹配）。 */
    private static final Pattern QUOTED_IDENTIFIER =
            Pattern.compile("\"[^\"]*\"|`[^`]*`|\\[[^\\]]*]");
}
