package cn.song.mybatisplus.visualizer.log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hibernate/JPA 控制台日志解析器。
 *
 * 支持的日志形态：
 * - SQL 行（Hibernate: 前缀）：`Hibernate: select ...`（同行或独占一行等待下一行）
 * - SQL 行（注释前缀）：块注释 load com.example.User 后跟 select ...（同行或跨行注释块）
 * - SQL 行（纯 SQL，recognizeBareSql=true 时）：行首即 SELECT/INSERT/UPDATE/DELETE
 * - 参数行：`binding parameter [1] as [BIGINT] - [1]`
 *
 * 三态状态机：IDLE、COLLECTING、emit。
 * - IDLE：等待 SQL 行（Hibernate: / 注释块 / 纯 SQL），检测到后切换到 COLLECTING。
 * - COLLECTING：累积多行 SQL（注释块跨行 + SQL 续行），遇到新语句前缀或参数行时闭合。
 * - emit：closePending() 从 multilineBuffer 取完整 SQL 构建 LogSqlEntry。
 *
 * 与 {@link MyBatisLogParser} 并列运行，互不干扰。共享 {@link LogSqlEntry} 数据模型。
 * 产出条目的 {@link LogSqlEntry#source()} 固定为 {@link SqlSource#HIBERNATE}。
 */
public class HibernateLogParser {

    public static final String DEFAULT_SQL_PREFIX = "Hibernate:";
    public static final String DEFAULT_PARAM_PREFIX = "binding parameter";
    public static final int DEFAULT_MAX_PARSE_LENGTH = 20000;

    /**
     * 纯 SQL 行模式：行首即 SQL 关键字。
     */
    private static final Pattern BARE_SQL_PATTERN = Pattern.compile(
            "^(select|insert|update|delete|merge|with)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * 参数绑定行模式：`binding parameter [N] as [TYPE] - [VALUE]`
     * 组 1 = 位置索引（未使用，保留用于调试），组 2 = JDBC 类型，组 3 = 值。
     */
    private static final Pattern BINDING_PARAM_PATTERN = Pattern.compile(
            "binding parameter\\s+\\[(\\d+)\\]\\s+as\\s+\\[(\\w+)\\]\\s+-\\s+\\[(.*)\\]",
            Pattern.CASE_INSENSITIVE);

    /**
     * MyBatis/MyBatis-Plus 日志行模式：
     * - MP 格式：`==>  Preparing:` / `==> Parameters:`
     * - 日志框架前缀：`2026-08-22 ... org.hibernate.SQL :`（任意日志行含类名）
     */
    private static final Pattern MYBATIS_LINE_PATTERN = Pattern.compile(
            "==>\\s+(Preparing|Parameters):",
            Pattern.CASE_INSENSITIVE);

    /**
     * 日志框架前缀模式：时间戳开头 + 日志级别（DEBUG/TRACE/INFO/WARN/ERROR）。
     */
    private static final Pattern LOG_FRAMEWORK_PATTERN = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+(DEBUG|TRACE|INFO|WARN|ERROR)");

    private final LogSqlRestorer restorer;
    private String sqlPrefix;
    private String paramPrefix;
    private boolean mixedPrefix;
    private int maxParseLength;
    private boolean recognizeBareSql;

    private final List<LogSqlEntry> entries = new ArrayList<>();
    private String pendingSql = null;
    private final List<LogSqlParam> pendingParams = new ArrayList<>();

    /**
     * T-HibDualCapture：零参数闭合条目隔离槽。
     *
     * {@code show-sql: true} 与 {@code org.hibernate.SQL: debug} 双路打印时，同一条 SQL 在控制台出现两份
     * （logger 副本多行块 + {@code Hibernate: } stdout 副本），参数绑定行只出现在两份之后——
     * logger 副本被 {@code Hibernate: } 行提前闭合时产出零参数影子条目，参数全部挂到 stdout 副本上，
     * 面板层去重拦不住（restoredSql 指纹不同）。零参数条目先进本槽：同一 rawSql 的后继条目
     * 到达即取代之（参数版上岗、影子丢弃）；不同 SQL 到达时影子照常入列保持产出顺序。
     *
     * 槽内条目刻意不随 {@link #takeEntries()} 冲刷（桥接逐行取件，冲刷会让影子在 stdout 副本到达前
     * 就可见、合并失效）；出口只有 {@link #closePending()}（LogToSqlPanel 收尾先 closePending 再
     * takeEntries，与既有「末条目滞后一条」契约一致）。
     */
    private LogSqlEntry quarantined = null;

    // ----- multiline 累积状态 -----
    private boolean collecting = false;
    private boolean inComment = false;
    private final StringBuilder multilineBuffer = new StringBuilder();

    public HibernateLogParser() {
        this(new LogSqlRestorer());
    }

    public HibernateLogParser(LogSqlRestorer restorer) {
        this(restorer, DEFAULT_SQL_PREFIX, DEFAULT_PARAM_PREFIX, true, DEFAULT_MAX_PARSE_LENGTH, true);
    }

    public HibernateLogParser(LogSqlRestorer restorer, String sqlPrefix, String paramPrefix,
                              boolean mixedPrefix, int maxParseLength, boolean recognizeBareSql) {
        this.restorer = restorer;
        this.sqlPrefix = sqlPrefix;
        this.paramPrefix = paramPrefix;
        this.mixedPrefix = mixedPrefix;
        this.maxParseLength = maxParseLength;
        this.recognizeBareSql = recognizeBareSql;
    }

    public LogSqlRestorer getRestorer() {
        return restorer;
    }

    public String getSqlPrefix() {
        return sqlPrefix;
    }

    public void setSqlPrefix(String sqlPrefix) {
        this.sqlPrefix = sqlPrefix;
    }

    public String getParamPrefix() {
        return paramPrefix;
    }

    public void setParamPrefix(String paramPrefix) {
        this.paramPrefix = paramPrefix;
    }

    public boolean isMixedPrefix() {
        return mixedPrefix;
    }

    public void setMixedPrefix(boolean mixedPrefix) {
        this.mixedPrefix = mixedPrefix;
    }

    public int getMaxParseLength() {
        return maxParseLength;
    }

    public void setMaxParseLength(int maxParseLength) {
        this.maxParseLength = maxParseLength;
    }

    public boolean isRecognizeBareSql() {
        return recognizeBareSql;
    }

    public void setRecognizeBareSql(boolean recognizeBareSql) {
        this.recognizeBareSql = recognizeBareSql;
    }

    /** 喂入一行控制台输出（任意线程，内部无共享可变状态——解析器实例按 console 隔离）。 */
    public void feed(String line) {
        if (collecting) {
            feedCollecting(line);
        } else {
            feedIdle(line);
        }
    }

    // ---------- IDLE 状态（等待 SQL 行） ----------

    private void feedIdle(String line) {
        // 1. Hibernate: 前缀（含同行 SQL）
        if (handleHibernatePrefix(line)) {
            return;
        }

        // 2. Hibernate: 独占一行（remainder 为空）→ 进入等待态
        if (isPrefixOnly(line, sqlPrefix)) {
            closePending();
            collecting = true;
            pendingParams.clear();
            return;
        }

        // 3. 块注释开头
        if (line.stripLeading().startsWith("/*")) {
            CommentState cs = hasUnclosedComment(line);
            closePending();
            collecting = true;
            pendingParams.clear();
            if (cs.unclosed()) {
                inComment = true;
            } else {
                if (!cs.afterComment().isEmpty() && looksLikeSql(cs.afterComment())) {
                    multilineBuffer.append(cs.afterComment());
                } else {
                    collecting = false;
                }
            }
            return;
        }

        // 4. 纯 SQL 行（recognizeBareSql=true 时）
        if (recognizeBareSql) {
            String trimmed = line.trim();
            if (!trimmed.toLowerCase().startsWith(paramPrefix.toLowerCase()) &&
                    BARE_SQL_PATTERN.matcher(trimmed).find()) {
                closePending();
                collecting = true;
                multilineBuffer.append(trimmed);
                pendingParams.clear();
                return;
            }
        }

        // 5. 参数绑定行
        LogSqlParam param = extractBindingParam(line);
        if (param != null) {
            if (pendingSql == null) {
                return; // 无 SQL 上下文，忽略
            }
            pendingParams.add(param);
            return;
        }

        // 6. 噪声行（忽略）
    }

    // ---------- COLLECTING 状态（累积多行 SQL） ----------

    private void feedCollecting(String line) {
        if (inComment) {
            // 等待注释块结束符
            int endIdx = line.lastIndexOf("*/");
            if (endIdx >= 0) {
                inComment = false;
                String afterComment = line.substring(endIdx + 2).trim();
                if (!afterComment.isEmpty() && looksLikeSql(afterComment)) {
                    if (multilineBuffer.length() + 1 + afterComment.length() <= maxParseLength) {
                        multilineBuffer.append(" ").append(afterComment);
                    }
                }
            }
            return;
        }

        // 1. 新 Hibernate: 前缀（含同行 SQL）→ 闭合当前条目，开启新收集
        if (handleHibernatePrefix(line)) {
            return;
        }

        // 1b. Hibernate: 独占一行 → 闭合当前条目，开启新收集
        if (isPrefixOnly(line, sqlPrefix)) {
            closePending();
            collecting = true;
            inComment = false;
            multilineBuffer.setLength(0);
            pendingParams.clear();
            return;
        }

        // 2. 新块注释开头 → 闭合当前条目，开启新收集
        if (line.stripLeading().startsWith("/*")) {
            CommentState cs = hasUnclosedComment(line);
            closePending();
            collecting = true;
            pendingParams.clear();
            if (cs.unclosed()) {
                inComment = true;
            } else {
                if (!cs.afterComment().isEmpty() && looksLikeSql(cs.afterComment())) {
                    multilineBuffer.append(cs.afterComment());
                } else {
                    collecting = false;
                }
            }
            return;
        }

        // 3. 参数绑定行 → 追加参数（保持 COLLECTING 以支持多参数）
        LogSqlParam param = extractBindingParam(line);
        if (param != null) {
            pendingParams.add(param);
            return;
        }

        // 4. 已有参数后遇到非 SQL 行 → 闭合当前条目，按 IDLE 处理该行
        if (!pendingParams.isEmpty()) {
            closePending();
            feedIdle(line);
            return;
        }

        // 5. MyBatis 行或其他框架前缀 → 忽略（不属于 Hibernate SQL 续行，保持 COLLECTING 等待参数）
        if (isMybatisLogLine(line) || isLogFrameworkLine(line)) {
            return;
        }

        // 6. 续行 → 追加到 buffer
        if (multilineBuffer.length() < maxParseLength) {
            multilineBuffer.append(" ").append(line.trim());
        }
    }

    /** 闭合当前未完成条目（新语句到来 / 面板刷新时调用）。 */
    public LogSqlEntry closePending() {
        String sql;
        if (collecting) {
            String s = take(multilineBuffer.toString().trim(), maxParseLength);
            if (s.isEmpty()) {
                s = null;
            }
            collecting = false;
            inComment = false;
            multilineBuffer.setLength(0);
            sql = s;
        } else {
            sql = pendingSql;
        }
        if (sql == null) {
            return null;
        }
        pendingSql = null;
        LogSqlEntry entry = new LogSqlEntry(
                sql,
                restorer.restore(sql, pendingParams),
                new ArrayList<>(pendingParams),
                SqlSource.HIBERNATE);
        pendingParams.clear();

        // T-HibDualCapture：隔离槽合并——同 SQL 后继条目取代零参影子；不同语句影子先入列保序
        LogSqlEntry shadow = quarantined;
        quarantined = null;
        if (shadow != null) {
            if (shadow.rawSql().equals(entry.rawSql())) {
                entries.add(entry);
                return entry;
            }
            entries.add(shadow);
        }
        if (entry.params().isEmpty()) {
            quarantined = entry; // 可能是被 stdout 副本覆盖的零参影子，先隔离待并
            return null;
        }
        entries.add(entry);
        return entry;
    }

    /** 取出全部已闭合条目并清空缓冲区（面板刷新用）。 */
    public List<LogSqlEntry> takeEntries() {
        // T-HibDualCapture：隔离槽仅在无语句收集中时冲刷——收集中冲刷会让零参影子在
        // stdout 副本到达前就可见、合并失效；收集中说明当前语句尚未闭合（末条目本就滞后一条）
        if (!collecting && quarantined != null) {
            entries.add(quarantined);
            quarantined = null;
        }
        List<LogSqlEntry> result = new ArrayList<>(entries);
        entries.clear();
        return result;
    }

    // ---------- 私有方法 ----------

    /**
     * 处理 Hibernate: 前缀（含同行 SQL 或同行块注释）。
     */
    private boolean handleHibernatePrefix(String line) {
        String afterKeyword = extractAfterKeyword(line, sqlPrefix);
        if (afterKeyword == null) {
            return false;
        }

        if (afterKeyword.stripLeading().startsWith("/*")) {
            CommentState cs = hasUnclosedComment(afterKeyword);
            closePending();
            collecting = true;
            pendingParams.clear();
            if (cs.unclosed()) {
                inComment = true;
            } else {
                if (!cs.afterComment().isEmpty() && looksLikeSql(cs.afterComment())) {
                    multilineBuffer.append(cs.afterComment());
                } else {
                    collecting = false;
                }
            }
        } else {
            closePending();
            collecting = true;
            multilineBuffer.append(afterKeyword);
            pendingParams.clear();
        }
        return true;
    }

    /**
     * 尝试从行中提取参数绑定信息。
     * 格式：`binding parameter [N] as [TYPE] - [VALUE]`
     */
    private LogSqlParam extractBindingParam(String line) {
        Matcher match = mixedPrefix
                ? BINDING_PARAM_PATTERN.matcher(line)
                : BINDING_PARAM_PATTERN.matcher(line.trim());
        boolean found = mixedPrefix ? match.find() : match.matches();
        if (!found) {
            return null;
        }

        String type = match.group(2);
        if (type != null && type.isEmpty()) {
            type = null;
        }
        String rawValue = match.group(3);

        // Hibernate 输出 [null] 表示 null 字面量
        if (rawValue.equalsIgnoreCase("null")) {
            return new LogSqlParam(null, type);
        }

        // [] 表示空字符串（非 null）
        return new LogSqlParam(rawValue, type);
    }

    /** 提取关键字后的文本（用于显式前缀匹配）。 */
    private String extractAfterKeyword(String line, String keyword) {
        if (keyword.isEmpty()) {
            return null;
        }
        int idx = mixedPrefix ? line.indexOf(keyword) : (line.startsWith(keyword) ? 0 : -1);
        if (idx < 0) {
            return null;
        }
        String text = line.substring(idx + keyword.length()).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 行仅含前缀关键字（remainder 为空/纯空白）——分行形态信号。
     */
    private boolean isPrefixOnly(String line, String keyword) {
        if (keyword.isEmpty()) {
            return false;
        }
        int idx = mixedPrefix ? line.indexOf(keyword) : (line.startsWith(keyword) ? 0 : -1);
        if (idx < 0) {
            return false;
        }
        return line.substring(idx + keyword.length()).trim().isEmpty();
    }

    /**
     * 检测行中块注释的状态。
     */
    private CommentState hasUnclosedComment(String line) {
        int start = line.indexOf("/*");
        if (start < 0) {
            return new CommentState(false, "");
        }
        int end = line.lastIndexOf("*/");
        if (end < start + 2) {
            return new CommentState(true, "");
        }
        String afterComment = line.substring(end + 2).trim();
        return new CommentState(false, afterComment);
    }

    /** 文本是否以 SQL 关键字开头。 */
    private boolean looksLikeSql(String text) {
        String upper = text.stripLeading().toUpperCase();
        return upper.startsWith("SELECT") || upper.startsWith("INSERT") ||
                upper.startsWith("UPDATE") || upper.startsWith("DELETE") ||
                upper.startsWith("MERGE") || upper.startsWith("WITH");
    }

    /**
     * 是否为 MyBatis/MyBatis-Plus 日志行（MP 前缀 / 原生 Preparing: / Parameters:）。
     */
    private boolean isMybatisLogLine(String line) {
        if (MYBATIS_LINE_PATTERN.matcher(line).find()) {
            return true;
        }
        return line.contains("Preparing:") || line.contains("Parameters:");
    }

    /**
     * 是否为日志框架前缀行（时间戳 + 日志级别 + 线程 + 类名），如 Spring Boot 默认格式。
     */
    private boolean isLogFrameworkLine(String line) {
        return LOG_FRAMEWORK_PATTERN.matcher(line).find();
    }

    /** 块注释状态（unclosed=未闭合, afterComment=注释后文本）。 */
    private record CommentState(boolean unclosed, String afterComment) {
    }

    private static String take(String s, int max) {
        return s.substring(0, Math.min(max, s.length()));
    }
}
