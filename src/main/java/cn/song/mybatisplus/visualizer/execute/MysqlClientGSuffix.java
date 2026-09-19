// T-SyncK2JMaster309 自 execute/MysqlClientGSuffix.kt 移植
package cn.song.mybatisplus.visualizer.execute;

import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T-MysqlGStrip：MySQL CLI 习惯的 {@code \G}/{@code \g} 后缀剥离（DBX 吸收清单 v6 #35）。
 *
 * <p>背景：用户从 MySQL 命令行带过来的 {@code SELECT 1\G}（垂直输出后缀）粘进预览面板/控制台执行时，
 * 文本原样透传服务端报语法错（MySQL 1064）。DBX 的做法是<b>提交前</b>剥掉最后一语句行尾的
 * {@code \G}/{@code \g}，同时保留历史记录里的原文（提交文本与历史原文两态分离）。</p>
 *
 * <p>规则（对齐 DBX sqlStatementRanges 的 {@code \G} 处理，v1 取<b>行级判定</b>）：</p>
 * <ul>
 *  <li>只看<b>最后一条语句的末行</b>——即全文最后一个非空白字符所在行，行尾匹配 {@code \G}/{@code \g} 才剥离；</li>
 *  <li>允许可选分号在 {@code \G} 之前或之后（{@code SELECT 1\G}、{@code SELECT 1;\G}、{@code SELECT 1\G;}），
 *      允许尾随空白；</li>
 *  <li>行内容以 {@code --} / {@code #} 注释开头 → 不碰；</li>
 *  <li>字符串字面量内的 {@code \G}（如 {@code SELECT '\G'}）——行尾锚定天然不匹配，不碰；</li>
 *  <li>只移除 {@code \G} 与它紧邻的空白（其前/后的分号保留），其余字节（含尾随换行）原样保留。</li>
 * </ul>
 *
 * <p><b>宁漏剥勿错剥</b>：不做全文字符串状态机（漏剥 = 维持现状报服务端错，错剥 = 改变用户 SQL）。</p>
 *
 * <p>本对象为纯函数（零平台依赖），可进默认 test 任务的快桶（testFast）。</p>
 */
public final class MysqlClientGSuffix {

    public static final MysqlClientGSuffix INSTANCE = new MysqlClientGSuffix();
    private MysqlClientGSuffix() {}

    /**
     * 剥离结果两态（提交文本与历史原文分离）：
     * <ul>
     *  <li>{@code original}：剥前原文——历史链记录它（用户回看时仍是自己粘进来的 SQL）；</li>
     *  <li>{@code submitText}：提交用文本（未剥离时与 {@code original} 同一实例语义）；</li>
     *  <li>{@code stripped}：本次是否真的剥掉了后缀（false 时调用方零动作）。</li>
     * </ul>
     *
     * @param original   剥前原文
     * @param submitText 提交用文本
     * @param stripped   是否真的剥掉了后缀
     */
    public record Result(String original, String submitText, boolean stripped) {}

    /**
     * 末行 {@code \G} 后缀形态：{@code <内容>;?[空白]\[gG][空白];?}（行尾锚定）。
     * 组 1 = {@code \G} 前内容、组 2 = {@code \G} 前的可选分号（保留）、组 3 = {@code \G} 后的可选分号（保留）。
     * {@code \G} 两侧的空白被吞掉（{@code SELECT 1\G ;} → {@code SELECT 1;}），其余字节不动。
     */
    private static final Pattern G_SUFFIX = Pattern.compile("^(.*?)(;?)[ \\t]*\\\\[gG][ \\t]*(;?)$");

    /** MySQL 系方言标识（JDBC URL 子协议段小写形态；含 MySQL 协议兼容的国产/衍生库）。 */
    private static final Set<String> MYSQL_FAMILY = Set.of(
            "mysql", "mariadb", "tidb", "oceanbase", "goldendb", "gbase", "polardb"
    );

    /**
     * 无条件剥离（方言不可得场景：exec 文件首次创建时数据源尚未选定）。
     * 文本不含末行 {@code \G} 后缀时原样返回（{@link Result#stripped} = false）。
     */
    @org.jetbrains.annotations.NotNull
    public static Result strip(@org.jetbrains.annotations.NotNull String sqlText) {
        if (sqlText.isEmpty()) return new Result(sqlText, sqlText, false);
        // 末行 = 最后一个非空白字符所在行；尾随空白（含换行）不参与匹配但原样保留
        String trimmed = trimTrailing(sqlText);
        if (trimmed.isEmpty()) return new Result(sqlText, sqlText, false);
        int lineStart = trimmed.lastIndexOf('\n') + 1;
        String lastLine = trimmed.substring(lineStart);
        if (isCommentLine(lastLine)) return new Result(sqlText, sqlText, false);
        Matcher m = G_SUFFIX.matcher(lastLine);
        if (!m.matches()) return new Result(sqlText, sqlText, false);
        String head = m.group(1);
        String kept = head + m.group(2) + m.group(3);
        String before = sqlText.substring(0, lineStart);
        String after = sqlText.substring(trimmed.length());
        // 末行只有 `\G` 命令（可选分号/空白）时整行删除——`SELECT 1;\n\G` → `SELECT 1;`
        String submitText = head.isBlank() ? trimTrailing(before) : before + kept + after;
        return new Result(sqlText, submitText, true);
    }

    /** 等价 Kotlin trimEnd()：剥尾随空白（Character.isWhitespace 口径与 Kotlin 一致按 ' ', '\t', '\n', '\r' 等常用空白）。 */
    private static String trimTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    /**
     * 按方言剥离：非 MySQL 系方言原样返回（{@link Result#stripped} = false）。
     * <b>拿不到方言（null/空白）时按 MySQL 处理（剥离）</b>——{@code \G} 不是任何方言的合法语法，
     * 剥离对非 MySQL 同样无害（开发计划 §三 T-MG-02 定案）。
     */
    @org.jetbrains.annotations.NotNull
    public static Result stripForDialect(@org.jetbrains.annotations.NotNull String sqlText, @Nullable String dialect) {
        return isMySqlFamily(dialect) ? strip(sqlText) : new Result(sqlText, sqlText, false);
    }

    /** 方言标识是否 MySQL 系；null/空白 = 判定不可得 → true（默认剥离，见 {@link #stripForDialect}）。 */
    public static boolean isMySqlFamily(@Nullable String dialect) {
        String d = dialect != null ? dialect.trim().toLowerCase() : "";
        if (d.isEmpty()) return true;
        return MYSQL_FAMILY.contains(d);
    }

    /**
     * 从 JDBC URL 提取方言标识（{@code jdbc:mysql://host:3306/db} → {@code mysql}；{@code jdbc:postgresql://…} →
     * {@code postgresql}）。取 {@code ://} 之前 scheme 段的末节，纯字符串截取不做连接参数解析。
     *
     * <p>无法判定时返回 null（无 {@code ://} 的形态如 Oracle thin {@code @host:1521:SID}、空串）——
     * 调用方据此走 {@link #stripForDialect} 的「不可得 → 默认剥离」分支。</p>
     */
    @Nullable
    public static String dialectFromUrl(@Nullable String url) {
        String raw = url != null ? url.trim() : "";
        int schemeEnd = raw.indexOf("://");
        if (schemeEnd <= 0) return null;
        String scheme = raw.substring(0, schemeEnd);
        for (int i = 0; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == ':' || c == '+' || c == '-' || c == '.')) {
                return null; // 不是 URL scheme 形态，不猜
            }
        }
        int lastColon = scheme.lastIndexOf(':');
        String tail = lastColon >= 0 ? scheme.substring(lastColon + 1) : scheme;
        String lowered = tail.toLowerCase();
        return lowered.isEmpty() ? null : lowered;
    }

    /** 行内容（跳过前导空白）是否以 {@code --} / {@code #} 行注释开头。 */
    private static boolean isCommentLine(String line) {
        String t = line.stripLeading();
        return t.startsWith("--") || t.startsWith("#");
    }
}
