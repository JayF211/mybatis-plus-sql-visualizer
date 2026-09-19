package cn.song.mybatisplus.visualizer.execute;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * SQL 语句类型分类器（M36）：区分查询类语句（免确认）与非查询类语句（需二次确认）。
 *
 * 分类规则（按首条有效语句首关键字判定）：
 *  - 查询类（免确认）：SELECT / SHOW / EXPLAIN / DESCRIBE / WITH（CTE）
 *  - DML（需确认）：INSERT / UPDATE / DELETE / MERGE
 *  - DDL（需确认）：CREATE / ALTER / DROP / TRUNCATE
 *  - 多条语句混合 → 按最高风险级别处理（需确认）
 *  - 注释（-- 单行 / * / 块注释）自动跳过
 */
public final class SqlTypeClassifier {

    /** SQL 语句类型枚举（风险升序）。 */
    public enum SqlType { QUERY, DML, DDL, UNKNOWN }

    /** 查询类关键字（免确认）。 */
    private static final Set<String> QUERY_KEYWORDS = Set.of("SELECT", "SHOW", "EXPLAIN", "DESCRIBE", "DESC", "WITH");

    /** DML 关键字（需确认）。 */
    private static final Set<String> DML_KEYWORDS = Set.of("INSERT", "UPDATE", "DELETE", "MERGE");

    /** DDL 关键字（需确认）。 */
    private static final Set<String> DDL_KEYWORDS = Set.of("CREATE", "ALTER", "DROP", "TRUNCATE");

    /** 二级证明词法扫描：标识符形态词（字边界匹配，列名 deleted_at 不会被拆成 DELETE）。 */
    private static final java.util.regex.Pattern WORD_REGEX = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** 二级证明词法扫描：INTO 字（\b 双侧边界，POINT 内含 INTO 不误配）。 */
    private static final java.util.regex.Pattern INTO_WORD_REGEX = java.util.regex.Pattern.compile("\\bINTO\\b");

    public static final SqlTypeClassifier INSTANCE = new SqlTypeClassifier();

    private SqlTypeClassifier() {
    }

    /**
     * 判断 SQL 是否为查询类语句（免确认直接执行）。
     *
     * @param sql SQL 文本（可能含多条语句、注释）
     * @return true=查询类（SELECT/SHOW/EXPLAIN/DESCRIBE/WITH）；false=非查询或无法判定
     */
    public static boolean isQuery(@NotNull String sql) {
        List<String> statements = splitStatements(sql);
        if (statements.isEmpty()) return false;
        // 多条语句：全部为查询类才免确认；任一条非查询 → 需确认
        return statements.stream().allMatch(it -> classify(it) == SqlType.QUERY);
    }

    /**
     * 提取 SQL 首条有效语句的首关键字（大写）。
     *
     * @param sql SQL 文本
     * @return 首关键字大写（如 "SELECT"、"INSERT"），无有效语句时返回 null
     */
    @Nullable
    public static String getFirstKeyword(@NotNull String sql) {
        List<String> statements = splitStatements(sql);
        if (statements.isEmpty()) return null;
        return extractKeyword(statements.get(0));
    }

    /**
     * 对单条 SQL 语句分类。
     *
     * <p>T-TxnReadOnlyProof（N-21）：首关键字命中免确认名单后追加只读二级证明——三个真实
     * 豁免盲区（可写 CTE / EXPLAIN ANALYZE 真执行写 / SELECT INTO 建表与写文件）任一
     * 命中即改判 UNKNOWN（需确认，fail-closed）；证明不了的形态一律维持确认弹窗。</p>
     *
     * @param statement 单条 SQL 语句（已去除注释）
     * @return 分类结果
     */
    @NotNull
    public static SqlType classify(@NotNull String statement) {
        String keyword = extractKeyword(statement);
        if (keyword == null) return SqlType.UNKNOWN;
        if (QUERY_KEYWORDS.contains(keyword) && !proveReadOnly(statement, keyword)) {
            return SqlType.UNKNOWN;
        }
        if (QUERY_KEYWORDS.contains(keyword)) return SqlType.QUERY;
        if (DML_KEYWORDS.contains(keyword)) return SqlType.DML;
        if (DDL_KEYWORDS.contains(keyword)) return SqlType.DDL;
        return SqlType.UNKNOWN;
    }

    /**
     * QUERY 名单语句的只读二级证明（T-TxnReadOnlyProof，词法宽门控：宁错弹不放过）。
     *
     * <p>三个盲区（对齐 DBX「只读豁免必须逐语句证明且 fail-closed」）：</p>
     * <ol>
     *  <li>可写 CTE：{@code WITH del AS (DELETE …) SELECT * FROM del}——WITH 后正文出现
     *      INSERT/UPDATE/DELETE/MERGE 字（含尾随主语句形态），不豁免；</li>
     *  <li>EXPLAIN ANALYZE：PG 语义 ANALYZE 会<b>真实执行</b>被修饰语句——正文同时出现
     *      ANALYZE|ANALYSE 字与 DML 字，不豁免（{@code EXPLAIN SELECT} 无 ANALYZE 不受影响）；</li>
     *  <li>SELECT INTO：{@code INTO OUTFILE|DUMPFILE}（MySQL 写服务端文件）或 {@code INTO <identifier>}
     *      建表（PG SELECT … INTO new_table），不豁免；{@code INTO @var} 会话变量维持豁免。</li>
     * </ol>
     *
     * <p>词法门控对字符串字面量与引号标识符先屏蔽再按字边界匹配（列名 deleted_at 不误判）；
     * JSqlParser AST 精确化为可选增强，v1 词法先行（与计划 §三 T-TR-01 一致）。</p>
     *
     * @return true=证明只读（维持 QUERY 豁免）；false=存在写证据或证明不了（转需确认）
     */
    private static boolean proveReadOnly(@NotNull String statement, @NotNull String keyword) {
        String masked = maskQuoted(statement).toUpperCase();
        List<String> words = new java.util.ArrayList<>();
        java.util.regex.Matcher wm = WORD_REGEX.matcher(masked);
        while (wm.find()) words.add(wm.group());
        switch (keyword) {
            // WITH：CTE 体内或尾随主语句出现写关键字即不豁免（drop(1) 跳过 WITH 自身）
            case "WITH": {
                for (int i = 1; i < words.size(); i++) {
                    if (DML_KEYWORDS.contains(words.get(i))) return false;
                }
                return true;
            }
            // EXPLAIN：ANALYZE|ANALYSE 与 DML 字并存即不豁免（EXPLAIN (ANALYZE, BUFFERS) DELETE 变体同拦）
            case "EXPLAIN": {
                boolean hasAnalyze = false;
                boolean hasDml = false;
                for (String w : words) {
                    if (w.equals("ANALYZE") || w.equals("ANALYSE")) hasAnalyze = true;
                    if (DML_KEYWORDS.contains(w)) hasDml = true;
                }
                return !(hasAnalyze && hasDml);
            }
            // SELECT：任一 INTO 后随 OUTFILE/DUMPFILE/标识符即不豁免（@var 会话变量除外）
            case "SELECT":
                return !intoWrites(masked);
            default:
                return true;
        }
    }

    /**
     * SELECT 语句的 INTO 写语义判定：任一 {@code INTO} 字后随 OUTFILE/DUMPFILE（MySQL 写文件）
     * 或普通标识符（PG 建表）即有写证据；{@code INTO @var}/{@code INTO @@global.x} 会话变量形态无写；
     * INTO 位于语句尾部（畸形）按证明不了处理（fail-closed）。
     */
    private static boolean intoWrites(@NotNull String maskedUpper) {
        java.util.regex.Matcher matcher = INTO_WORD_REGEX.matcher(maskedUpper);
        // 首个 INTO（find 即 Kotlin INTO_WORD_REGEX.find）
        java.util.regex.MatchResult match = matcher.find() ? matcher.toMatchResult() : null;
        while (match != null) {
            // rest = 本次 INTO 匹配之后的剩余文本（range.last + 1 即 match end）
            String rest = maskedUpper.substring(match.end());
            String afterInto = rest.stripLeading();
            if (afterInto.isEmpty()) {
                // INTO 后无内容：畸形形态，证明不了 → 有写证据（fail-closed）
                return true;
            } else if (afterInto.charAt(0) == '@') {
                // 会话变量（@v / @@global.v）：维持豁免，继续看后续 INTO
            } else {
                return true;
            }
            matcher = INTO_WORD_REGEX.matcher(rest);
            match = matcher.find() ? matcher.toMatchResult() : null;
        }
        return false;
    }

    /**
     * 屏蔽字符串字面量（'…'，含 '' 转义）与 `…`/"…" 引号标识符——字面量内的关键字
     * （如 'delete from t' 常量值）不参与二级证明词法扫描；替换为空格保持偏移稳定。
     */
    private static String maskQuoted(@NotNull String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'') {
                sb.append(' ');
                i++;
                while (i < sql.length()) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                            // '' 转义：字面量内引号
                            sb.append("  ");
                            i += 2;
                        } else {
                            sb.append(' ');
                            i++;
                            break;
                        }
                    } else {
                        sb.append(' ');
                        i++;
                    }
                }
            } else if (c == '`' || c == '"') {
                char quote = c;
                sb.append(' ');
                i++;
                while (i < sql.length() && sql.charAt(i) != quote) {
                    sb.append(' ');
                    i++;
                }
                if (i < sql.length()) {
                    sb.append(' ');
                    i++;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * 按分号分割 SQL 为多条语句（忽略空语句），并逐条去注释。
     *
     * <p>T-SemicolonGuard（O-13）：实现收敛委托至 util/SqlStatementSplitter（与
     * LogToSqlPanel/SqlToJavaConverter 单实现合一）；顺带修复两个历史缺陷——字符串内
     * 分号误切（{@code 'a;b'}）、字符串内 {@code --} 被误剥注释（差异清单见开发计划 T-SG-01）。</p>
     *
     * @param sql SQL 文本
     * @return 去注释后的非空语句列表（段已 trim）
     */
    public static List<String> splitStatements(@NotNull String sql) {
        return cn.song.mybatisplus.visualizer.util.SqlStatementSplitter.splitStripped(sql);
    }

    /**
     * 去除 SQL 语句中的注释（-- 单行注释 + /* *\/ 块注释）。
     *
     * @param sql 原始 SQL 片段
     * @return 去注释后的文本
     */
    public static String stripComments(@NotNull String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            // -- 单行注释：跳过至行尾
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                // 跳至行尾或文本结束
                while (i < sql.length() && sql.charAt(i) != '\n') i++;
            }
            // /* */ 块注释
            else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i < sql.length()
                        && !(sql.charAt(i) == '*' && i + 1 < sql.length() && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2; // 跳过 */
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * 提取语句的首关键字（大写）。跳过前导空白与左括号。
     *
     * @param statement 单条 SQL 语句（已去注释）
     * @return 首关键字大写，无有效关键字时返回 null
     */
    @Nullable
    private static String extractKeyword(@NotNull String statement) {
        String trimmed = statement.trim();
        if (trimmed.isEmpty()) return null;
        // 提取连续字母作为关键字
        StringBuilder letters = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isLetter(ch)) {
                letters.append(ch);
            } else {
                break;
            }
        }
        String result = letters.toString();
        return result.isEmpty() ? null : result.toUpperCase();
    }
}
