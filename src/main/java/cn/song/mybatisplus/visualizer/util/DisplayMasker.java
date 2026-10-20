package cn.song.mybatisplus.visualizer.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R60（T-LaneB D95/D96）：展示/导出链字面量脱敏——规则<b>逐条对齐</b>采集侧 agent
 * {@code sub-projects/sql-visualizer-agent/.../util/Desensitizer.java:10-30}（V112.1 落档）：
 *
 * <p>①列名/参数名提示：hint 小写化后 contains 命中 {@link #KEYWORDS} 任一项 → 打码；
 * ②值形态兜底（无 hint 也命中）：18 位身份证（前 17 位数字+末位数字/X/x）或
 * 11 位手机号（全数字且首位 1）→ 打码；打码结果固定 {@link #MASK}，空/null 值原样返回。</p>
 *
 * <p>agent 是独立源集（随 jar 注入目标应用 JVM），主仓 classpath 不可引用——两份同规则文本，
 * <b>关键词表/形态判定/打码值改动须双侧同步</b>（D97 口径对齐声明：采集 agent / MCP 输出 /
 * 展示导出三面同一套双条件规则）。</p>
 *
 * <p>本类纯 Java 零平台依赖；开关由调用方读设置传入（{@link #maskDisplay}），MCP 面
 * （sqlv_render_logs）与展示面（列表/预览浮层/导出）各自走各自的设置键（D96 双开关）。</p>
 */
public final class DisplayMasker {

    private DisplayMasker() {
    }

    /** 打码结果（与采集侧 Desensitizer.MASK 一致）。 */
    public static final String MASK = "***";

    /** 敏感关键词表（Desensitizer.java:12-16 逐项同步；改动双侧同步）。 */
    private static final String[] KEYWORDS = {
        "password", "passwd", "pwd", "token", "secret", "credential", "apikey", "api_key",
        "access_key", "private_key", "idcard", "id_card", "mobile", "phone",
        "身份证", "手机号", "密码", "密钥", "令牌",
    };

    /**
     * 双条件打码（Desensitizer.mask 等价移植）：列名提示 contains 命中或值形态命中 → {@link #MASK}；
     * 其余原样。空/null 值原样（不打码）。
     */
    public static String mask(String columnHint, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (columnHint != null) {
            String hint = columnHint.toLowerCase();
            for (String keyword : KEYWORDS) {
                if (hint.contains(keyword)) {
                    return MASK;
                }
            }
        }
        return looksLikeIdCard(value) || looksLikeMobile(value) ? MASK : value;
    }

    /** 18 位身份证形态（前 17 位数字 + 末位数字/X/x）。 */
    public static boolean looksLikeIdCard(String v) {
        if (v.length() != 18) {
            return false;
        }
        for (int i = 0; i <= 16; i++) {
            if (!Character.isDigit(v.charAt(i))) {
                return false;
            }
        }
        char last = v.charAt(17);
        return Character.isDigit(last) || last == 'X' || last == 'x';
    }

    /** 11 位手机号形态（全数字且首位 1）。 */
    public static boolean looksLikeMobile(String v) {
        if (v.length() != 11) {
            return false;
        }
        for (int i = 0; i < v.length(); i++) {
            if (!Character.isDigit(v.charAt(i))) {
                return false;
            }
        }
        return v.charAt(0) == '1';
    }

    // ------------------------------------------------------------------
    // SQL 文本级扫描（展示/导出/MCP 链共用）
    // ------------------------------------------------------------------

    /** SQL 关键字黑名单（列名 hint 推导时跳过——{@code WHERE x = 'v'} 的 hint 是 x 不是 WHERE）。 */
    private static final Set<String> SQL_KEYWORDS = Set.of(
        "select", "from", "where", "and", "or", "not", "in", "is", "like", "set", "values",
        "insert", "update", "delete", "into", "join", "on", "as", "by", "order", "group",
        "having", "limit", "offset", "distinct", "case", "when", "then", "else", "end",
        "between", "exists", "union", "all", "asc", "desc", "null", "true", "false"
    );

    /** 字符串字面量 {@code '...'}（含 {@code ''} 转义）或裸数字形态（11 位手机号/18 位身份证）的联合扫描。 */
    private static final Pattern TOKEN_REGEX =
            Pattern.compile("'(?:[^']|'')*'|\\b\\d{11}\\b|\\b\\d{17}[0-9Xx]\\b");

    /**
     * SQL 文本脱敏：字符串字面量按「前置最近标识符作列名 hint」双条件打码为 {@code '***'}；
     * 裸数字仅按值形态打码为 {@code ***}（保留原引号形态，展示面可读）。非敏感片段逐字保留。
     *
     * <p>值内子串命中的形态<b>不</b>打码（对齐采集侧「参数值整体判形态」语义）——联合正则天然
     * 保证：引号字面量整体命中后其内部数字不再单独扫描。</p>
     *
     * <p>UI-021：{@code INSERT ... VALUES} 形态按<b>列清单与值序列位置对位</b>取列名 hint——VALUES 列表里
     * 字面量之间没有列名，旧的「前置最近标识符」会把<b>前一个字面量值</b>当列名（如 password 列值
     * Secret123X 的 hint 变成上一列的 ui_mask_probe → 漏打；下一列值的 hint 变成 Secret123X →
     * 因含 secret 误打）。列清单解析失败/值数超出列数时逐位回退旧规则。</p>
     */
    public static String maskSqlText(String sql) {
        List<String> insertCols = insertColumnsForValues(sql);
        int[] literalOrdinal = { -1 };
        return transform(sql, ctx -> {
            if (ctx.kind == TokenKind.LITERAL) {
                literalOrdinal[0]++;
                String inner = ctx.text.substring(1, ctx.text.length() - 1).replace("''", "'");
                String hint = insertCols != null && literalOrdinal[0] < insertCols.size()
                        ? insertCols.get(literalOrdinal[0])
                        : ctx.hint;
                return MASK.equals(mask(hint, inner)) ? "'" + MASK + "'" : ctx.text;
            }
            return MASK;
        });
    }

    /** 链入口：{@code enabled} 开 → {@link #maskSqlText}，关 → 原文逐字（列表/预览浮层/导出三链共用）。 */
    public static String maskDisplay(String sql, boolean enabled) {
        return enabled ? maskSqlText(sql) : sql;
    }

    /** R37 参数化产物：敏感字面量替换为 {@code ?} 的 SQL + 脱敏参数表（值恒 {@link #MASK}，原值不出本方法）。 */
    public record ParameterizedSql(String sql, List<String> maskedParams) {
    }

    /**
     * R37 敏感字面量参数化（V51.3 规则）：敏感字面量（引号/裸数字）替换为 {@code ?}，
     * 打码值进 {@link ParameterizedSql#maskedParams()}；非敏感片段逐字保留。零敏感时 SQL 即原文、参数表空。
     */
    public static ParameterizedSql parameterize(String sql) {
        List<String> params = new ArrayList<>();
        List<String> insertCols = insertColumnsForValues(sql);
        int[] literalOrdinal = { -1 };
        String out = transform(sql, ctx -> {
            if (ctx.kind == TokenKind.LITERAL) {
                literalOrdinal[0]++;
                String inner = ctx.text.substring(1, ctx.text.length() - 1).replace("''", "'");
                String hint = insertCols != null && literalOrdinal[0] < insertCols.size()
                        ? insertCols.get(literalOrdinal[0])
                        : ctx.hint;
                if (MASK.equals(mask(hint, inner))) {
                    params.add(MASK);
                    return "?";
                }
                return ctx.text;
            }
            params.add(MASK);
            return "?";
        });
        return new ParameterizedSql(out, params);
    }

    /** 扫描 token 形态。 */
    private enum TokenKind { LITERAL, BARE_NUMBER }

    /** 命中 token + 推导的列名 hint（仅 LITERAL 有；裸数字无 hint 走形态）。 */
    private record TokenContext(String text, TokenKind kind, String hint) {
    }

    /** SQL 字符串字面量的引号字符。 */
    private static final char SQL_QUOTE = '\'';

    /** 联合扫描 + 逐 token 变换（非命中区间逐字保留）。 */
    private static String transform(String sql, java.util.function.Function<TokenContext, String> mapper) {
        StringBuilder sb = new StringBuilder(sql.length());
        int last = 0;
        Matcher m = TOKEN_REGEX.matcher(sql);
        while (m.find()) {
            String token = m.group();
            TokenKind kind = token.charAt(0) == SQL_QUOTE ? TokenKind.LITERAL : TokenKind.BARE_NUMBER;
            sb.append(sql, last, m.start());
            sb.append(mapper.apply(new TokenContext(token, kind,
                    kind == TokenKind.LITERAL ? columnHintBefore(sql, m.start()) : null)));
            last = m.end();
        }
        sb.append(sql, last, sql.length());
        return sb.toString();
    }

    /**
     * 字面量前置上下文的列名 hint：取字面量起点前 {@link #HINT_WINDOW} 字符内的<b>最后一个</b>
     * 标识符（跳过 SQL 关键字）——{@code WHERE phone = '…'} 取 phone、{@code IN ('a','b')} 取 IN 前的
     * 列名、{@code VALUES ('x')} 取最近列名或无。取不到返回 null（只走形态兜底）。
     */
    private static String columnHintBefore(String sql, int literalStart) {
        int from = Math.max(0, literalStart - HINT_WINDOW);
        String window = sql.substring(from, literalStart);
        String result = null;
        Matcher m = ID_REGEX.matcher(window);
        while (m.find()) {
            String id = m.group();
            if (!SQL_KEYWORDS.contains(id.toLowerCase())) {
                result = id;
            }
        }
        return result;
    }

    /**
     * UI-021：{@code INSERT ... VALUES} 的列清单（供按值位置取列名 hint）。
     *
     * <p>VALUES 列表里字面量之间没有列名——旧 hint 推导会把前一个字面量值当列名；本函数解析
     * 「{@code INSERT INTO <表> (列1, 列2, ...) VALUES (}」段，返回列名序列，由调用方按 LITERAL 出现序
     * 对位。解析失败（非 INSERT/无列清单）返回 null，调用方逐位回退旧规则。
     * 列名支持裸标识符与反引号/双引号/方括号包裹；列清单内不含逗号嵌套。</p>
     */
    private static final Pattern INSERT_COLUMNS = Pattern.compile(
            "(?is)\\bINSERT\\s+INTO\\s+[\\w.`\"\\[\\]]+\\s*\\(([^()]*)\\)\\s*VALUES\\s*\\(");

    private static List<String> insertColumnsForValues(String sql) {
        Matcher m = INSERT_COLUMNS.matcher(sql);
        if (!m.find()) {
            return null;
        }
        String[] rawCols = m.group(1).split(",");
        List<String> cols = new ArrayList<>();
        for (String raw : rawCols) {
            String col = raw.trim();
            col = stripWrappers(col);
            cols.add(col);
        }
        boolean allNonEmpty = !cols.isEmpty();
        for (String col : cols) {
            if (col.isEmpty()) {
                allNonEmpty = false;
            }
        }
        return allNonEmpty ? cols : null;
    }

    private static String stripWrappers(String col) {
        String s = col;
        boolean changed = true;
        while (changed && s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '`' && last == '`') || (first == '"' && last == '"')
                    || (first == '[' && last == ']')) {
                s = s.substring(1, s.length() - 1).trim();
            } else {
                changed = false;
            }
        }
        return s;
    }

    /** 标识符（含中文列名/别名形态）。 */
    private static final Pattern ID_REGEX = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*|[一-鿿]+");

    /** 列名 hint 回看窗口（字符；覆盖 {@code WHERE very_long_column_name = } 量级）。 */
    private static final int HINT_WINDOW = 48;
}
