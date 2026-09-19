// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSelectGuard.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * T-McpToolset 只读白名单守卫（mpsv_explain / 未来任何执行类工具共用）。
 *
 * fail closed（计划 §三-2）：非 SELECT / 多语句 / 解析失败 / 锁读 / SELECT INTO 一律拒绝。
 * 双重判定（DBX「风险分类器 + 关键字扫描任一命中即算写」的只读子集）：
 *  1. 关键字粗扫（词边界、大小写不敏感）：写/DDL/会话类动词与锁读/落盘形态；
 *  2. JSqlParser 结构判定：单语句 + Select 子类 + 无 FOR UPDATE + 无 INTO。
 *
 * 纯逻辑层：只依赖 JSqlParser 5.3（运行时既有依赖），无 IDE 类型。
 * JSqlParser API 均经本地 5.3 jar javap 核实（Select.getForUpdateTable / PlainSelect.getIntoTables /
 * PlainSelect.getIntoTempTable / CCJSqlParserUtil.parseStatements→Statements.getStatements）。
 */
public final class McpSelectGuard {

    private McpSelectGuard() {
    }

    /** 单例（Kotlin object 惯例形态；静态方法为主）。 */
    public static final McpSelectGuard INSTANCE = new McpSelectGuard();

    /** 输入长度硬上限（防恶意超长输入拖垮解析器）。 */
    public static final int MAX_SQL_LENGTH = 200_000;

    /** 结构化判定结果。{@code allowed}=false 时 {@code errorCode}/{@code reason} 必非空。 */
    public record Verdict(
        /** true = 通过只读白名单，可执行。 */
        boolean allowed,
        /** 拒绝时的 {@link McpToolErrors} 错误码（通过时 null）。 */
        String errorCode,
        /** 拒绝时的 agent 可读原因（通过时 null）。 */
        String reason
    ) {
        /** Kotlin 默认参数等价重载：通过形态（errorCode/reason 缺省 null）。 */
        public Verdict(boolean allowed) {
            this(allowed, null, null);
        }
    }

    /**
     * 写/DDL/会话类动词（词边界粗扫；标识符如 last_update 因下划线不构成词边界不会误伤）。
     * 刻意不含：desc（ORDER BY DESC 合法方向）、replace（REPLACE() 纯读函数）、comment（常见列名）——
     * 对应的写语句形态由 JSqlParser 结构判定兜底拒绝（fail closed 双保险分工）。
     */
    private static final List<String> FORBIDDEN_WORDS = List.of(
        "insert", "update", "delete", "merge", "upsert", "call", "exec", "execute",
        "create", "alter", "drop", "truncate", "rename", "grant", "revoke",
        "commit", "rollback", "savepoint", "release", "lock", "unlock",
        "set", "use", "kill", "shutdown", "describe", "show", "analyze",
        "optimize", "repair", "flush", "purge", "load", "unload", "handler", "do", "listen",
        "notify", "reindex", "vacuum", "cluster", "copy", "checkpoint", "discard",
        "import", "export", "admin"
    );

    /** 锁读 / 落盘形态（词边界粗扫；FOR XML/JSON 是 SQL Server 合法读形态，不在表内）。 */
    private static final List<String> FORBIDDEN_PHRASES = List.of(
        "for update", "for share", "lock in share mode", "into outfile", "into dumpfile",
        "into var", "into @", "for browse"
    );

    /** 词边界正则缓存（\b + 字面量引述 + \b，等价 Kotlin Regex.escape；次序与 FORBIDDEN_WORDS 一致）。 */
    private static final List<Pattern> FORBIDDEN_WORD_PATTERNS = buildWordPatterns();

    private static List<Pattern> buildWordPatterns() {
        List<Pattern> patterns = new ArrayList<>(FORBIDDEN_WORDS.size());
        for (String word : FORBIDDEN_WORDS) {
            patterns.add(Pattern.compile("\\b" + Pattern.quote(word) + "\\b"));
        }
        return patterns;
    }

    /**
     * 校验 sql 是单条纯查询（SELECT / UNION 等 {@link Select} 形态，含 CTE）。
     * 判定序固定：空 → 超长 → 关键字粗扫 → 解析 → 单语句 → Select 结构 → 锁读/INTO。
     */
    public static Verdict checkSingleSelect(String sql) {
        if (sql == null || sql.isBlank()) {
            return deny(McpToolErrors.INVALID_ARGUMENT, "selectSql is required (a single SELECT statement).");
        }
        String trimmed = sql.trim();
        if (trimmed.length() > MAX_SQL_LENGTH) {
            return deny(
                McpToolErrors.INVALID_ARGUMENT,
                "selectSql exceeds the maximum length of " + MAX_SQL_LENGTH + " characters."
            );
        }
        // 双保险判定序：结构判定优先（错误码语义准确：写语句 READ_ONLY、形态违规 SQL_BLOCKED），
        // 关键字粗扫兜底（拦结构判定覆盖不到的可疑词面）；粗扫在剥离字面量后的文本上进行
        List<Statement> statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(trimmed).getStatements();
        } catch (Throwable e) {
            // 解析失败按写处理（fail closed），不暴露解析器内部细节给 agent；
            // 捕 Throwable：解析器对恶意构造输入可能抛 StackOverflowError 等 Error
            return deny(
                McpToolErrors.SQL_BLOCKED,
                "selectSql could not be parsed as a single standard SELECT statement; " +
                    "refusing to execute (fail-closed policy)."
            );
        }
        if (statements.size() != 1) {
            return deny(
                McpToolErrors.SQL_BLOCKED,
                "multiple statements are not allowed (got " + statements.size() + "); submit exactly one SELECT."
            );
        }
        Statement statement = statements.get(0);
        if (!(statement instanceof Select select)) {
            return deny(
                McpToolErrors.READ_ONLY,
                "only SELECT statements are allowed; got "
                    + (statement != null ? statement.getClass().getSimpleName() : "unknown") + "."
            );
        }
        // 锁读双字段判定（JSqlParser 5.3 javap 实证）：FOR UPDATE t 落 forUpdateTable，
        // 裸 FOR UPDATE/FOR SHARE 等落 forMode（UPDATE/SHARE/NO_KEY_UPDATE/KEY_SHARE）
        if (select.getForUpdateTable() != null || select.getForMode() != null) {
            return deny(McpToolErrors.SQL_BLOCKED, "locking reads (FOR UPDATE/SHARE) are not allowed.");
        }
        if (hasIntoClause(select)) {
            return deny(
                McpToolErrors.SQL_BLOCKED,
                "SELECT INTO (file/temp-table side effects) is not allowed."
            );
        }
        String masked = stripLiterals(trimmed).toLowerCase(Locale.ROOT);
        Verdict wordHit = scanWords(masked);
        if (wordHit != null) {
            return wordHit;
        }
        Verdict phraseHit = scanPhrases(masked);
        if (phraseHit != null) {
            return phraseHit;
        }
        return new Verdict(true);
    }

    /**
     * 剥离字符串字面量（'…'、"…"）与反引号/方括号标识符内容——粗扫只在结构层文本上进行，
     * 避免 `LIKE '%update%'`、`WHERE col = 'drop'` 等合法查询被关键字粗扫误杀；
     * 字面量内夹带的可疑内容（如 `'; DROP TABLE'`）由 JSqlParser 结构判定兜底（整条必须
     * 解析为单条 Select，多语句/写语句照拒）。剥不清（引号不配对）则按原文粗扫（fail closed）。
     */
    private static String stripLiterals(String sql) {
        StringBuilder masked = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            Character quote = switch (c) {
                case '\'', '"', '`' -> c;
                case '[' -> ']';
                default -> null;
            };
            if (quote == null) {
                // 行注释/块注释一并剥离（注释文本同样可含关键字）
                if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                    while (i < sql.length() && sql.charAt(i) != '\n') {
                        i++;
                    }
                    continue;
                }
                if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                    int end = sql.indexOf("*/", i + 2);
                    i = end < 0 ? sql.length() : end + 2;
                    continue;
                }
                masked.append(c);
                i++;
                continue;
            }
            masked.append(quote.charValue());
            i++;
            while (i < sql.length()) {
                if (quote != '[' && sql.charAt(i) == quote && i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2; // 转义的引号（'' / "" / ``）
                    continue;
                }
                if (sql.charAt(i) == quote) {
                    i++;
                    break;
                }
                i++;
            }
            masked.append(quote.charValue());
        }
        return masked.toString();
    }

    private static Verdict scanWords(String lower) {
        for (int i = 0; i < FORBIDDEN_WORDS.size(); i++) {
            if (FORBIDDEN_WORD_PATTERNS.get(i).matcher(lower).find()) {
                String word = FORBIDDEN_WORDS.get(i);
                return deny(
                    McpToolErrors.READ_ONLY,
                    "statement contains the non-read-only keyword '" + word + "'; only SELECT is allowed."
                );
            }
        }
        return null;
    }

    private static Verdict scanPhrases(String lower) {
        for (String phrase : FORBIDDEN_PHRASES) {
            if (lower.contains(phrase)) {
                return deny(
                    McpToolErrors.SQL_BLOCKED,
                    "statement contains the restricted clause '" + phrase + "'; only plain reads are allowed."
                );
            }
        }
        return null;
    }

    /** 顶层及 UNION 各分支的 INTO 判定（CTE 包装在 Select 自身，into 仍落在 PlainSelect）。 */
    private static boolean hasIntoClause(Select select) {
        if (select instanceof PlainSelect plain) {
            return !isNullOrEmpty(plain.getIntoTables()) || plain.getIntoTempTable() != null;
        }
        if (select instanceof SetOperationList setOperation) {
            for (Select branch : setOperation.getSelects()) {
                if (branch instanceof PlainSelect plainBranch) {
                    if (!isNullOrEmpty(plainBranch.getIntoTables()) || plainBranch.getIntoTempTable() != null) {
                        return true;
                    }
                }
            }
            return false;
        }
        return false;
    }

    /** Kotlin isNullOrEmpty 等价（null 或空均算空）。 */
    private static boolean isNullOrEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    private static Verdict deny(String code, String reason) {
        return new Verdict(false, code, reason);
    }
}
