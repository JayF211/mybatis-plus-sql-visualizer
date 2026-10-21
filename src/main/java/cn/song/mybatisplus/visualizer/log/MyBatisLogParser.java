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
 *
 * T-LogParseConc（并发配对修正，用户实测驱动）：
 * - <b>按线程分桶</b>（{@link LogThreadRouter}）：并发应用里多线程语句按到达序混排，单槽状态机下另一线程的
 *   {@code Preparing:} 行会把本线程尚未收到参数的语句提前闭合、本线程随后的 {@code Parameters:} 行因无上下文被丢弃
 *   （参数全丢、{@code ?} 未还原）。故全部状态（pendingSql/参数/等待态/续行态）按线程 token 隔离。
 * - <b>空 {@code Parameters:} 行不再吞下一行</b>：下一行若带日志行特征（looksLikeLogLine），说明该语句本身
 *   无参数（MyBatis 对无参语句打印空前缀行）——闭合且<b>不吞该行</b>（按正常流程重新喂入）。旧行为会把
 *   整行日志当作参数值塞进 {@code ?}（面板出现 {@code LIMIT 2026-09-16 14:35:00.413 DEBUG …} 这类文字）。
 * - <b>多行 SQL 续行</b>（T-LPC-07）：{@code Preparing:} 之后的裸文本行（不含日志特征、非空）并入当前 SQL——
 *   mapper 里格式化的 SQL 不再只留首行，增强通道两侧 rawSql 同形（日志时间相关器得以命中）。
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

    /**
     * T-LPC-07：`名称: 内容` 形态的行首标识符前缀（如 `Hibernate: select …`）——续行判定拒收，
     * 避免异构语句行/框架头行被并入 MyBatis 条目的 SQL 正文。
     */
    private static final Pattern HEADER_LIKE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_.]{2,}\\s*:");

    private final LogSqlRestorer restorer;
    private String sqlPrefix;
    private String paramPrefix;
    private boolean mixedPrefix;
    private int maxParseLength;

    private final List<LogSqlEntry> entries = new ArrayList<>();

    /** 单线程桶：一条语句从 `Preparing:` 到闭合之间的全部状态（T-LogParseConc 按线程隔离）。 */
    private static final class Bucket {
        String pendingSql = null;
        String pendingLogLine = null;
        final List<LogSqlParam> pendingParams = new ArrayList<>();

        /** 前缀独占一行兼容（T-M31.xx）：`Preparing:` 行 remainder 为空 → 等下一行作 SQL。 */
        boolean prefixMatched = false;

        /** `Parameters:` 行 remainder 为空 → 等下一行作参数文本（T-LogParseConc 加了日志行判别）。 */
        boolean paramPrefixMatched = false;

        /** 多行 SQL 续行态（T-LPC-07）：下一行是裸文本则并入 pendingSql。 */
        boolean continuing = false;
    }

    private final LogThreadRouter<Bucket> router = new LogThreadRouter<>(
            Bucket::new,
            // 无线程 token 的裸行（续行 / 「前缀独占一行」的下一行）要落到**正在等待它**的桶——
            // 否则带前缀的 `Preparing:`（token=main）与其下一行裸 SQL 会分属两个桶，等待态永远完不成
            b -> b.continuing || b.prefixMatched || b.paramPrefixMatched,
            b -> b.pendingSql == null && !b.prefixMatched && !b.paramPrefixMatched);

    public MyBatisLogParser() {
        this(new LogSqlRestorer());
    }

    public MyBatisLogParser(LogSqlRestorer restorer) {
        this(restorer, DEFAULT_SQL_PREFIX, DEFAULT_PARAM_PREFIX, true, DEFAULT_MAX_PARSE_LENGTH);
    }

    /** 全配置便捷构造（restorer 取默认）——master 具名默认参数形态（EP Filter/桥用）。 */
    public MyBatisLogParser(String sqlPrefix, String paramPrefix, boolean mixedPrefix, int maxParseLength) {
        this(new LogSqlRestorer(), sqlPrefix, paramPrefix, mixedPrefix, maxParseLength);
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

    /** 喂入一行控制台输出（单解析器调用方兼容薄壳；热路径走 feed(line, token) 重载，任意线程；按线程 token 路由到独立桶——解析器实例按 console 隔离）。 */
    public void feed(String line) {
        feed(line, LogThreadRouter.threadTokenOf(line));
    }

    /**
     * F58（T-PerfSweep18 压轴）：喂入一行＋<b>已提取</b>的线程 token（热路径每行一次下传，
     * 语义与 {@code feed(line)} 自提逐字等价；token=null 走 fail-open 回退）。
     */
    public void feed(String line, String threadToken) {
        feedInto(router.bucketForToken(threadToken), line);
    }

    private void feedInto(Bucket bucket, String line) {
        // 分支 1：Preparing 行（有 SQL 同行）
        String sql = extractAfterKeyword(line, sqlPrefix);
        if (sql != null) {
            closePending(bucket); // 上一条未闭合先闭合（无 Parameters 或异常日志）
            bucket.pendingSql = sql.substring(0, Math.min(maxParseLength, sql.length()));
            bucket.pendingLogLine = line;
            bucket.pendingParams.clear();
            bucket.prefixMatched = false;
            bucket.paramPrefixMatched = false; // T-LogParseConc：等待态随语句切换复位（旧实现残留→吞掉后续整行）
            bucket.continuing = true;
            return;
        }

        // 分支 2：Preparing 独占一行（remainder 为空）→ 进入等待 SQL 态
        if (!bucket.prefixMatched && isPrefixOnly(line, sqlPrefix)) {
            bucket.prefixMatched = true;
            bucket.pendingParams.clear();
            return;
        }

        // 分支 3：Parameters 独占一行（极端形态）→ 等待下一行参数文本
        if (!bucket.paramPrefixMatched && isPrefixOnly(line, paramPrefix)) {
            if (bucket.prefixMatched) {
                // 等待 SQL 中看到 Parameters（中间无 SQL 行）→ 放弃等待
                bucket.prefixMatched = false;
                return;
            }
            bucket.paramPrefixMatched = true;
            return;
        }
        if (bucket.paramPrefixMatched) {
            bucket.paramPrefixMatched = false;
            if (line.isBlank()) {
                return; // 跳过空行
            }
            if (bucket.pendingSql == null) {
                return; // 无 SQL 上下文，忽略
            }
            // T-LPC-03：等到的行若本身是日志行 → 该语句**无参数**（MyBatis 对无参语句打印空前缀行）。
            // 闭合且不吞该行（按正常流程重新喂入——含它自己的线程桶路由；本等待态已复位，不会再进本分支）。
            if (LogThreadRouter.looksLikeLogLine(line, sqlPrefix, paramPrefix)) {
                LogParseDiag.swallowedLineBlocked.incrementAndGet();
                closePending(bucket);
                feed(line);
                return;
            }
            bucket.pendingParams.addAll(MyBatisLogParserKt.parseParams(line.trim()));
            closePending(bucket);
            return;
        }

        // 分支 4：Parameters 行（有参数同行）——正常形态
        String paramsText = extractAfterKeyword(line, paramPrefix);
        if (paramsText != null) {
            bucket.prefixMatched = false; // 清除等待态（罕见：Preparing 独占一行后紧跟正常 Parameters）
            if (bucket.pendingSql == null) {
                LogParseDiag.paramWithoutContext.incrementAndGet(); // T-LPC-05：静默丢弃留痕
                return;
            }
            bucket.pendingParams.addAll(MyBatisLogParserKt.parseParams(paramsText));
            // 立即闭合——MyBatis 单条语句 Parameters 行后参数完整，立即生成条目避免去重窗口时序问题；
            // 多 Parameters 行（极端）合并逻辑：若下一行仍是 Parameters，pendingParams 已被清空后重开。
            closePending(bucket);
            return;
        }

        // 分支 5：等待 SQL 态 → 此行即 SQL
        if (bucket.prefixMatched) {
            if (line.isBlank()) {
                return; // 跳过空行
            }
            // 多模块并发时，Hibernate 行可能出现在 Preparing: 独占一行后的等待态 → 跳过并继续等待
            if (looksLikeHibernateLine(line)) {
                return; // 不重置 prefixMatched，继续等 MyBatis SQL
            }
            String trimmed = line.trim();
            bucket.pendingSql = trimmed.substring(0, Math.min(maxParseLength, trimmed.length()));
            bucket.pendingLogLine = line;
            bucket.prefixMatched = false;
            bucket.continuing = true;
            return;
        }

        // 分支 6（T-LPC-07）：SQL 续行——仅当本行未被任何正常分支消费、且不像日志行/异构语句行时并入当前 SQL。
        // 排在等待态之后是必须的：「等下一行参数文本」态下的裸参数行（`42(Long)`）不能被当续行吞掉。
        if (bucket.continuing) {
            if (line.isBlank() || LogThreadRouter.looksLikeLogLine(line, sqlPrefix, paramPrefix)
                    || looksLikeForeignStatement(line)) {
                bucket.continuing = false; // 日志行/异构语句行/空行终止续行（本行交回噪声分支）
            } else {
                appendContinuation(bucket, line);
            }
            return;
        }

        // 分支 7：噪声行（与旧行为一致）
    }

    /**
     * 是否像<b>异构语句行</b>（其他 ORM/框架的 SQL 行，如 `Hibernate: select …`）——续行判定须拒收：
     * 这类行本身代表另一条语句，并入会污染 MyBatis 条目的 SQL 正文（`DualParserIntegrationTest` 覆盖）。
     */
    private boolean looksLikeForeignStatement(String line) {
        String trimmed = line.trim();
        return looksLikeHibernateLine(line) || HEADER_LIKE_PATTERN.matcher(trimmed).find();
    }

    /** 续行并入（受 maxParseLength 约束，超限即终止续行不吞后续）。 */
    private void appendContinuation(Bucket bucket, String line) {
        String current = bucket.pendingSql;
        if (current == null) {
            bucket.continuing = false;
            return;
        }
        String merged = current + "\n" + stripTrailingWhitespace(line);
        if (merged.length() >= maxParseLength) {
            bucket.continuing = false;
            return;
        }
        bucket.pendingSql = merged;
    }

    private static String stripTrailingWhitespace(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /** 闭合指定桶的未完成条目（新语句到来 / 面板刷新时调用）。 */
    private LogSqlEntry closePending(Bucket bucket) {
        String sql = bucket.pendingSql;
        if (sql == null) {
            return null;
        }
        bucket.pendingSql = null;
        bucket.continuing = false;
        Long logTs = bucket.pendingLogLine != null ? LogTimestampParser.parse(bucket.pendingLogLine) : null;
        bucket.pendingLogLine = null;
        List<LogSqlParam> params = new ArrayList<>(bucket.pendingParams);
        String restored = restorer.restore(sql, params);
        LogSqlEntry entry = new LogSqlEntry(
                sql,
                restored,
                params,
                System.currentTimeMillis(),
                logTs,
                LogSqlEntry.summarizeSql(restored),
                SqlSource.MYBATIS,
                null, null, 0, List.of(), List.of(), null, 0L,
                null, null, null, null, null);
        entries.add(entry);
        bucket.pendingParams.clear();
        return entry;
    }

    /** 闭合全部桶的未完成条目（面板刷新 / 粘贴末尾时调用）。 */
    public LogSqlEntry closePending() {
        LogSqlEntry first = null;
        for (Bucket bucket : router.allBuckets()) {
            LogSqlEntry produced = closePending(bucket);
            if (first == null && produced != null) {
                first = produced;
            }
        }
        return first;
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
