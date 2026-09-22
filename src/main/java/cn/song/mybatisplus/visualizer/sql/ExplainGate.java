// T-SyncK2JMaster184 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/sql/ExplainGate.kt 移植
package cn.song.mybatisplus.visualizer.sql;

import java.util.Locale;
import java.util.Set;

/**
 * EXPLAIN 包装前的<b>首关键字词法边界判定</b>（T-ExplainChainHardening T-ECH-01 / O-48）。
 *
 * <p>根因（第十轮报告 §8 #50，DBX {@code 3a44507a} {@code is_safe_explain_source} +
 * {@code starts_with_explain_keyword_boundary} 对齐）：IDE 路径此前对编辑器里的<b>任意文本</b>加
 * EXPLAIN 前缀——{@code DELETE FROM t}、{@code CREATE TABLE …}、标识符 {@code SELECTED} 一律被包成
 * {@code EXPLAIN DELETE FROM t} 送进数据库。EXPLAIN 虽不执行被包语句，但语法错误与解析副作用仍由库侧
 * 承担，且「点了没报错但结果无意义」比明确拒绝更不诚实。</p>
 *
 * <p>与 {@link Dialect#renderExplainStatementIdempotent} 里既有的 {@code startsWithExplainKeyword} 分工：</p>
 * <ul>
 *  <li>本类的 {@link #startsWithExplainKeyword}：判<b>包装后的语句</b>是否已是 EXPLAIN（幂等，防双前缀）；</li>
 *  <li>本类的 {@link #isExplainableSource}：判<b>被包语句</b>的首关键字是否可 EXPLAIN（本任务新增）。</li>
 * </ul>
 * <p>两者都在这里，是因为它们是同一类「词法边界」判定——放一处便于对照，避免再次出现
 * 「一处有边界判定、另一处取任意文本」的口径分叉。</p>
 *
 * <p><b>不复用</b> {@code execute/SqlTypeClassifier.extractKeyword}（{@code takeWhile { it.isLetter() }}）：
 * 它会把 {@code SELECT_foo} 的前缀 {@code SELECT} 当首关键字 → 放行（方向比 DBX 宽松），正是本任务要拒的形态。</p>
 */
public final class ExplainGate {

    private ExplainGate() {}

    /**
     * 可被 EXPLAIN 的<b>首关键字白名单</b>（小写；与 DBX {@code is_safe_explain_source} 对齐）。
     *
     * <p>只列「只读查询表达式」的起始关键字——{@code SELECT}（含 {@code SELECT*}/{@code SELECT(1)}）、
     * {@code VALUES}（{@code VALUES (1),(2)} 是合法只读查询）、{@code TABLE}（PG 的 {@code TABLE t} 简写）、
     * {@code WITH}（CTE）。DML/DDL/会话类（INSERT/UPDATE/DELETE/CREATE/…）与未知词一律不在册 → 拒绝。</p>
     */
    private static final Set<String> ALLOWED_FIRST_KEYWORDS = Set.of("select", "values", "table", "with");

    /**
     * Kotlin {@code Char.isWhitespace} 等价：{@code Character.isWhitespace(c) || Character.isSpaceChar(c)}。
     */
    private static boolean isWs(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** 首关键字字符集：字母/数字/下划线/{@code $}（SQL 标识符字符）——取连续段即「第一个词」。 */
    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /**
     * 取语句的<b>首个词</b>（小写）；取不到（空/纯注释/纯符号）返回 null。
     *
     * <p>取词前跳过：前导空白、前导 {@code (}（括号化的查询表达式 {@code (SELECT …) UNION (SELECT …)}——
     * 括号内不可能是可执行的 DML/DDL 形态）、以及前导注释（{@code -- …} / {@code /* … *}{@code /} /
     * {@code # …}）。跳过注释是必要的：{@code -- 查用户\nSELECT …} 这类预览文本在 mapper 里很常见，
     * 按「首个非空白字符不是词」判会误拒；而 MCP 侧的结构白名单（JSqlParser）本来就接受带前导注释的
     * SELECT，不跳过会让两链对同一文本给出相反结论。</p>
     */
    public static String firstKeyword(String sql) {
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (isWs(c)) {
                i++;
            } else if (c == '(') {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i = skipToLineEnd(sql, i);
            } else if (c == '#') {
                i = skipToLineEnd(sql, i);
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
            } else {
                if (!isWordChar(c)) return null;
                int j = i;
                while (j < n && isWordChar(sql.charAt(j))) j++;
                return sql.substring(i, j).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static int skipToLineEnd(String sql, int from) {
        int i = from;
        while (i < sql.length() && sql.charAt(i) != '\n') i++;
        return i;
    }

    /** 跳过块注释；未闭合（{@code /*} 后无 {@code *}{@code /}）→ 视作注释延续到结尾（与 SQL 引擎的容忍一致）。 */
    private static int skipBlockComment(String sql, int from) {
        int end = sql.indexOf("*/", from + 2);
        return end < 0 ? sql.length() : end + 2;
    }

    /**
     * 被包语句是否可 EXPLAIN（首关键字落在 {@link #ALLOWED_FIRST_KEYWORDS} 且词边界完整）。
     *
     * <p>词边界由「取连续词字符段」天然保证：{@code SELECTED}/{@code SELECT_foo} 的整段是
     * {@code selected}/{@code select_foo}（不在白名单）→ 拒；{@code SELECT*}/{@code SELECT(1)}/
     * {@code VALUES(1)}/{@code TABLE users} 的首段是 {@code select}/{@code values}/{@code table} → 放行。</p>
     */
    public static boolean isExplainableSource(String sql) {
        String keyword = firstKeyword(sql != null ? sql : "");
        return keyword != null && ALLOWED_FIRST_KEYWORDS.contains(keyword);
    }

    /**
     * 语句是否已以 EXPLAIN 关键字开头——紧随字符须非字母/数字/下划线（词边界），
     * 避免 {@code EXPLAINING_TABLE} 之类标识符误判（T-ExplainDbl 幂等包装判定，语义与迁移前逐字一致）。
     */
    public static boolean startsWithExplainKeyword(String sql) {
        if (!sql.regionMatches(true, 0, "EXPLAIN", 0, 7)) {
            return false;
        }
        return sql.length() == 7
                || (!Character.isLetterOrDigit(sql.charAt(7)) && sql.charAt(7) != '_');
    }

    /**
     * EXPLAIN 提交判定结果（T-ECH-01）：两类拒绝理由<b>必须可区分</b>——「语句不可 EXPLAIN」与
     * 「方言不接入 EXPLAIN」的提示文案不同，合并成一个 null 会让用户拿到错的解释。
     *
     * <p>移植注记：master 侧为 {@code sealed interface SubmitPlan}（三个 data class 嵌套在接口体内，
     * 全名 {@code SubmitPlan.Submit / SubmitPlan.NotExplainable / SubmitPlan.DialectUnsupported}）；
     * Java 17 用 sealed interface + record 直译（record 访问器无 get 前缀，同 data class 语义）。</p>
     */
    public sealed interface SubmitPlan {

        /** 可提交的 EXPLAIN 语句（已包装文本原样提交，未包装文本按方言模板包装）。 */
        public record Submit(String statement) implements SubmitPlan {
        }

        /** 被包语句首关键字不在白名单（{@link ExplainGate#firstKeyword} 为实际取到的词，null = 取不到）。 */
        public record NotExplainable(String firstKeyword) implements SubmitPlan {
        }

        /** 本方言不接入 EXPLAIN。 */
        public record DialectUnsupported(String dialectName) implements SubmitPlan {
        }
    }

    /**
     * EXPLAIN 提交判定——<b>IDE 链的唯一决策点</b>（{@code SqlPreviewPanel.explainSql}）。
     *
     * <p>判定序：① 已以 EXPLAIN 开头（幂等场景，是本插件上一次的包装产物）→ 原样提交；
     * ② 被包语句首关键字不在白名单 → {@link SubmitPlan.NotExplainable}；③ 方言不接入 →
     * {@link SubmitPlan.DialectUnsupported}。</p>
     *
     * <p>幂等分支<b>跳过</b>白名单是刻意的：包装产物首词是 {@code EXPLAIN}（不在白名单），若一并判会让
     * 「再点一次执行计划」被误拒（T-ExplainDbl 回归）。</p>
     */
    public static SubmitPlan planExplainSubmit(Dialect dialect, String sql) {
        String trimmed = sql.trim();
        if (!startsWithExplainKeyword(trimmed) && !isExplainableSource(trimmed)) {
            return new SubmitPlan.NotExplainable(firstKeyword(trimmed));
        }
        String statement = dialect.renderExplainStatementIdempotent(trimmed);
        if (statement == null) {
            return new SubmitPlan.DialectUnsupported(dialect.getName());
        }
        return new SubmitPlan.Submit(statement);
    }
}
