package cn.song.mybatisplus.visualizer.log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * MyBatis 控制台日志解析器（T-M31-01）：逐行喂入控制台输出，配对 `Preparing:` / `Parameters:` 行。
 *
 * 支持的日志形态（T-M31.14 起前缀可配置）：
 * - MP 前缀：`==>  Preparing: SELECT ...` / `==> Parameters: 1(Long), abc(String)`
 * - 原生：`Preparing: SELECT ...` / `Parameters: 1, abc(String)`
 * - 带日志框架前缀（时间戳/线程/类名）——mixedPrefix=true 时按关键字定位（默认，宽容匹配）；
 *   false 时仅行首精确前缀匹配（用户自定义前缀后开启）
 * - 无参语句：仅 `Preparing:` 行（无 Parameters），还原时不做替换
 *
 * 噪声行（`<== Columns:` / `<== Total:` / 业务日志等）自然忽略；
 * 一条语句可跟多条 Parameters 行（极端形态），参数顺序追加。
 * maxParseLength 超长截断（防控制台大输出卡顿）。
 */
public class MyBatisLogParser {

    private static final String DEFAULT_SQL_PREFIX = "Preparing:";
    private static final String DEFAULT_PARAM_PREFIX = "Parameters:";
    private static final int DEFAULT_MAX_PARSE_LENGTH = 20000;

    /**
     * Hibernate 参数绑定行模式：`binding parameter [N] as [TYPE] - [VALUE]`
     */
    private static final Pattern HIBERNATE_BINDING_PATTERN = Pattern.compile(
            "binding parameter\\s+\\[(\\d+)\\]\\s+as\\s+\\[(\\w+)\\]\\s+-\\s+\\[(.*)\\]",
            Pattern.CASE_INSENSITIVE);

    private final LogSqlRestorer restorer;
    private String sqlPrefix;
    private String paramPrefix;
    private boolean mixedPrefix;
    private int maxParseLength;

    private final List<LogSqlEntry> entries = new ArrayList<>();
    private String pendingSql = null;
    private final List<LogSqlParam> pendingParams = new ArrayList<>();
    /**
     * 前缀独占一行兼容（T-M31.xx）：`Preparing:` 行 remainder 为空时置 true，
     * 等待下一行作为 SQL。下一行非空非关键字行视为 SQL 文本。
     */
    private boolean prefixMatched = false;
    /**
     * Parameters 也独占一行（极端形态）：`Parameters:` 行 remainder 为空时置 true，
     * 等待下一行作为参数文本。
     */
    private boolean paramPrefixMatched = false;

    public MyBatisLogParser() {
        this(new LogSqlRestorer());
    }

    public MyBatisLogParser(LogSqlRestorer restorer) {
        this(restorer, DEFAULT_SQL_PREFIX, DEFAULT_PARAM_PREFIX, true, DEFAULT_MAX_PARSE_LENGTH);
    }

    public MyBatisLogParser(LogSqlRestorer restorer, String sqlPrefix, String paramPrefix,
                            boolean mixedPrefix, int maxParseLength) {
        this.restorer = restorer;
        this.sqlPrefix = sqlPrefix;
        this.paramPrefix = paramPrefix;
        this.mixedPrefix = mixedPrefix;
        this.maxParseLength = maxParseLength;
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

    /** 喂入一行控制台输出（任意线程，内部无共享可变状态——解析器实例按 console 隔离）。 */
    public void feed(String line) {
        // 分支 1：Preparing 行（有 SQL 同行）
        String sql = extractAfterKeyword(line, sqlPrefix);
        if (sql != null) {
            closePending(); // 上一条未闭合先闭合（无 Parameters 或异常日志）
            pendingSql = sql.substring(0, Math.min(maxParseLength, sql.length()));
            pendingParams.clear();
            prefixMatched = false;
            return;
        }

        // 分支 2：Preparing 独占一行（remainder 为空）→ 进入等待 SQL 态
        if (!prefixMatched && isPrefixOnly(line, sqlPrefix)) {
            prefixMatched = true;
            pendingParams.clear();
            return;
        }

        // 分支 3：Parameters 独占一行（极端形态）→ 等待下一行参数文本
        if (!paramPrefixMatched && isPrefixOnly(line, paramPrefix)) {
            if (prefixMatched) {
                // 等待 SQL 中看到 Parameters（中间无 SQL 行）→ 放弃等待
                prefixMatched = false;
                return;
            }
            paramPrefixMatched = true;
            return;
        }
        if (paramPrefixMatched) {
            paramPrefixMatched = false;
            if (line.isBlank()) {
                return; // 跳过空行
            }
            if (pendingSql == null) {
                return; // 无 SQL 上下文，忽略
            }
            pendingParams.addAll(MyBatisLogParserKt.parseParams(line.trim()));
            closePending();
            return;
        }

        // 分支 4：Parameters 行（有参数同行）——正常形态
        String paramsText = extractAfterKeyword(line, paramPrefix);
        if (paramsText != null) {
            prefixMatched = false; // 清除等待态（罕见：Preparing 独占一行后紧跟正常 Parameters）
            if (pendingSql == null) {
                return; // 无上下文（未捕获到 Preparing），忽略
            }
            pendingParams.addAll(MyBatisLogParserKt.parseParams(paramsText));
            // 立即闭合——MyBatis 单条语句 Parameters 行后参数完整，立即生成条目避免去重窗口时序问题；
            // 多 Parameters 行（极端）合并逻辑：若下一行仍是 Parameters，pendingParams 已被清空后重开。
            closePending();
            return;
        }

        // 分支 5：等待 SQL 态 → 此行即 SQL
        if (prefixMatched) {
            if (line.isBlank()) {
                return; // 跳过空行
            }
            // 多模块并发时，Hibernate 行可能出现在 Preparing: 独占一行后的等待态 → 跳过并继续等待
            if (looksLikeHibernateLine(line)) {
                return; // 不重置 prefixMatched，继续等 MyBatis SQL
            }
            String trimmed = line.trim();
            pendingSql = trimmed.substring(0, Math.min(maxParseLength, trimmed.length()));
            prefixMatched = false;
            return;
        }

        // 分支 6：噪声行（现有逻辑不变）
    }

    /** 闭合当前未完成条目（新语句到来 / 面板刷新时调用）。 */
    public LogSqlEntry closePending() {
        String sql = pendingSql;
        if (sql == null) {
            return null;
        }
        pendingSql = null;
        LogSqlEntry entry = new LogSqlEntry(
                sql,
                restorer.restore(sql, pendingParams),
                new ArrayList<>(pendingParams),
                SqlSource.MYBATIS);
        entries.add(entry);
        pendingParams.clear();
        return entry;
    }

    /** 取出全部已闭合条目并清空缓冲区（面板刷新用）。 */
    public List<LogSqlEntry> takeEntries() {
        List<LogSqlEntry> result = new ArrayList<>(entries);
        entries.clear();
        return result;
    }

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
     * 与 {@link #extractAfterKeyword(String, String)} 使用相同的 mixedPrefix 判断路径，保证两种模式下行为一致。
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
     * 是否为 Hibernate 日志行（多模块并发时可能混入 MyBatis 控制台）。
     * 在 prefixMatched 等待态中跳过此类行，避免将 Hibernate 行误认为 MyBatis SQL。
     */
    private boolean looksLikeHibernateLine(String line) {
        String trimmed = line.trim();
        // Hibernate: 前缀 / 块注释开头 / 参数绑定行
        return trimmed.toLowerCase().startsWith("hibernate:") ||
                trimmed.startsWith("/*") ||
                HIBERNATE_BINDING_PATTERN.matcher(trimmed).find();
    }
}
