package cn.song.mybatisplus.visualizer.log;

import java.util.ArrayList;
import java.util.List;

/**
 * ShardingSphere 双段日志解析器（R10 T-ShardingSphereCompat，<b>骨架首版</b>）。
 *
 * <p>支持的日志形态（ShardingSphere 5.x sql-show 公开文档形态，<b>真实语料未采样——V36</b>）：
 * <ul>
 * <li>Logic SQL 行：{@code Logic SQL: select ...}（逻辑 SQL，占位符形态）</li>
 * <li>Actual SQL 行：{@code Actual SQL: ds_0 ::: select ... ::: Parameters: [1, abc]}
 *   （路由后真实执行片段；{@code ::: Parameters:} 段无参时缺省）</li>
 * </ul>
 *
 * <p>D1=A 定案：<b>Logic SQL 为主条目</b>（可还原参数执行——参数取首个 Actual 的 {@code Parameters:}
 * 段，同语句各分片绑定值相同），<b>Actual SQL 段折叠为附注</b>（{@link LogSqlEntry#actualSql()}，
 * 逐行原文，面板对照渲染等语料）。
 *
 * <p>三态状态机（循 {@link HibernateLogParser} 等待态配对先例 + {@link LogThreadRouter} 线程分桶）：
 * <ul>
 * <li>IDLE：等待 Logic SQL 行；</li>
 * <li>PENDING：Logic 行开启、Actual 行逐行折叠；下一条 Logic 行 / {@link #closePending()} / {@link #flushSettled()}
 *   （静默 ≥1s，循 JdbcTemplate T-ConsolePendingFlush 先例）闭合产出。</li>
 * <li>噪声行在 PENDING 态<b>不闭合</b>（保守：与 MyBatis 行交错的场景 V11a 未采样，先保配对；
 *   代价是条目滞后一条语句产出，面板可见性由静默冲刷兜底）。</li>
 * </ul>
 *
 * <p>TODO(等语料/V36)：真实双段日志语料到位后补全——①{@code Parameters: [..]} 值含 ", "（字符串内逗号）
 * 的拆分规则（骨架按文档样例「逗号+空格分隔」直拆）；②4.x 输出形态（D2=A 登记不支持）；③与
 * MyBatis {@code Preparing:} 行交错的配对语义与去重兜底（V11a）；④参数类型标注是否在位（当前按无类型
 * 还原，走 {@link LogSqlRestorer} 启发式，V11b 的复用结论=可复用）；⑤Actual 折叠附注的面板对照渲染（T-03）。
 */
public class ShardingSphereLogParser {

    /** 单线程桶：一条双段语句从 Logic 行到闭合之间的全部状态。 */
    private static final class Bucket {
        /** Logic SQL（关键字后原文；null = 空闲态）。 */
        String logicSql = null;

        /** Logic 行原文（日志时间取它）。 */
        String pendingLogLine = null;

        /** Actual 段折叠缓冲（关键字后原文逐行）。 */
        final List<String> actualLines = new ArrayList<>();

        /** 参数（取首个带 {@code Parameters:} 段的 Actual 行）。 */
        final List<LogSqlParam> pendingParams = new ArrayList<>();
    }

    private final LogSqlRestorer restorer;

    /** 行解析最大长度（超长截断，防控制台大输出卡顿；与其余解析器同口径）。 */
    private int maxParseLength;

    /** 静默冲刷阈值（毫秒）；语义与 {@link JdbcTemplateLogParser} 同款。 */
    private final long settleMs;

    /** settle 判定的时钟（测试注入可控件）。 */
    private final kotlin.jvm.functions.Function0<Long> clock;

    /**
     * 线程分桶路由。router 持有实例级 {@code clock}，须在构造体内建（字段初始化器先于构造体执行，
     * 届时 clock 尚未赋值）。
     */
    private final LogThreadRouter<Bucket> router;

    private final List<LogSqlEntry> entries = new ArrayList<>();

    public ShardingSphereLogParser() {
        this(new LogSqlRestorer(), DEFAULT_MAX_PARSE_LENGTH, DEFAULT_SETTLE_MS, System::currentTimeMillis);
    }

    public ShardingSphereLogParser(LogSqlRestorer restorer) {
        this(restorer, DEFAULT_MAX_PARSE_LENGTH, DEFAULT_SETTLE_MS, System::currentTimeMillis);
    }

    /** 仅指定最大解析长度（EP Filter / 桥按 ParserConfig 传入）。 */
    public ShardingSphereLogParser(int maxParseLength) {
        this(new LogSqlRestorer(), maxParseLength, DEFAULT_SETTLE_MS, System::currentTimeMillis);
    }

    public ShardingSphereLogParser(LogSqlRestorer restorer, int maxParseLength) {
        this(restorer, maxParseLength, DEFAULT_SETTLE_MS, System::currentTimeMillis);
    }

    /** master 主构造全参形态（settleMs/clock 注入入口，测试用）。 */
    public ShardingSphereLogParser(LogSqlRestorer restorer, int maxParseLength,
                                   long settleMs, kotlin.jvm.functions.Function0<Long> clock) {
        this.restorer = restorer;
        this.maxParseLength = maxParseLength;
        this.settleMs = settleMs;
        this.clock = clock;
        // Logic/Actual 行各带日志框架前缀（线程 token 在位），裸行只有正文续行形态——骨架按
        // 「收集态桶优先」共享路由即可，不开 MRU（Hibernate 专属开关，无其双行形态）。
        // 时钟与 clock 同源（flushSettled 的静默判定一致性；测试注入可控件）。
        this.router = new LogThreadRouter<>(
                Bucket::new,
                b -> b.logicSql != null,
                b -> b.logicSql == null,
                LogThreadRouter.DEFAULT_MAX_BUCKETS,
                false,
                clock);
    }

    public int getMaxParseLength() {
        return maxParseLength;
    }

    public void setMaxParseLength(int maxParseLength) {
        this.maxParseLength = maxParseLength;
    }

    /** 喂入一行控制台输出（任意线程；按线程 token 路由到独立桶）。 */
    public void feed(String line) {
        feedInto(router.bucketFor(line), line);
    }

    private void feedInto(Bucket bucket, String line) {
        // 1. Actual SQL 行 → 折叠附注（PENDING 态才有上下文；IDLE 态丢弃）
        String actualSegment = extractAfterKeyword(line, ACTUAL_SQL_PREFIX);
        if (actualSegment != null) {
            String logic = bucket.logicSql;
            if (logic == null) {
                return; // 未配对 Actual 丢弃（等待态配对先例同口径）
            }
            if (!logic.isEmpty() && bucket.actualLines.size() < MAX_ACTUAL_LINES
                    && String.join("\n", bucket.actualLines).length() < maxParseLength) {
                bucket.actualLines.add(take(actualSegment, maxParseLength));
                if (bucket.pendingParams.isEmpty()) {
                    bucket.pendingParams.addAll(extractParams(actualSegment));
                }
            }
            return;
        }
        // 2. Logic SQL 行 → 闭合上一条、开启新收集
        String logicSegment = extractAfterKeyword(line, LOGIC_SQL_PREFIX);
        if (logicSegment != null) {
            closeBucketPending(bucket);
            // TODO(等语料)：remainder 为空的分行形态（Logic SQL: 独占一行）5.x 文档无此形态，
            // 骨架按空串拒绝开启（不产条目），语料实证后再补等待态。
            if (logicSegment.isBlank()) {
                return;
            }
            bucket.logicSql = take(logicSegment, maxParseLength);
            bucket.pendingLogLine = line;
            return;
        }
        // 3. 噪声行：PENDING 态不闭合（保守保配对，见类头 TODO/V11a）
    }

    /** 闭合全部桶的未完成条目（面板刷新/MCP 工具收尾用）。返回首个产出的条目。 */
    public LogSqlEntry closePending() {
        LogSqlEntry first = null;
        for (Bucket bucket : router.allBuckets()) {
            LogSqlEntry produced = closeBucketPending(bucket);
            if (first == null) {
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

    /** 静默冲刷（时钟取当前时刻的默认实参形态）。 */
    public int flushSettled() {
        return flushSettled(clock.invoke());
    }

    /**
     * 静默冲刷——闭合全部已静默 ≥ {@code settleMs} 的桶的待产出条目，产出进 {@link #entries}
     * （由调用方随下一次 {@link #takeEntries()} 取走），返回本次产出条数。
     * 语义与调用点同 {@link JdbcTemplateLogParser#flushSettled(long)}（逐行入口 + 桥轮询兜底两处）——
     * ShardingSphere 条目只在「下一条 Logic 行」闭合，低频访问下最后一条没有后继行，靠这一拍兜底。
     */
    public int flushSettled(long nowMs) {
        int produced = 0;
        for (Bucket bucket : router.settledBuckets(settleMs, nowMs)) {
            if (closeBucketPending(bucket) != null) {
                produced++;
            }
        }
        return produced;
    }

    // ---------- 私有方法 ----------

    private LogSqlEntry closeBucketPending(Bucket bucket) {
        String logic = bucket.logicSql;
        if (logic == null) {
            return null;
        }
        bucket.logicSql = null;
        String logLine = bucket.pendingLogLine;
        bucket.pendingLogLine = null;
        String actual = String.join("\n", bucket.actualLines);
        List<LogSqlParam> params = new ArrayList<>(bucket.pendingParams);
        bucket.actualLines.clear();
        bucket.pendingParams.clear();
        if (logic.isBlank()) {
            return null;
        }
        String restored = restorer.restore(logic, params);
        LogSqlEntry entry = new LogSqlEntry(
                logic,
                restored,
                params,
                System.currentTimeMillis(),
                logLine != null ? LogTimestampParser.parse(logLine) : null,
                LogSqlEntry.computeSummary(restored),
                SqlSource.SHARDINGSPHERE,
                null, null, 0, List.of(), List.of(), null, 0L,
                null, null, null, null, null,
                actual.isEmpty() ? null : actual);
        entries.add(entry);
        return entry;
    }

    /** 提取关键字后的文本（行中任意位置定位——容忍日志框架前缀；与其余解析器 mixed 语义一致）。 */
    private static String extractAfterKeyword(String line, String keyword) {
        int idx = line.indexOf(keyword);
        if (idx < 0) {
            return null;
        }
        return line.substring(idx + keyword.length()).trim();
    }

    /**
     * 从 Actual 段提取 {@code Parameters: [...]} 绑定值（无类型标注；还原走 {@link LogSqlRestorer} 启发式）。
     * TODO(等语料)：值文本含 ", " 的拆分、空参形态 {@code []}、null 字面量形态——骨架按公开文档样例直拆。
     */
    private static List<LogSqlParam> extractParams(String actualSegment) {
        int idx = actualSegment.lastIndexOf(PARAMETERS_KEYWORD);
        if (idx < 0) {
            return List.of();
        }
        String bracket = actualSegment.substring(idx + PARAMETERS_KEYWORD.length()).trim();
        if (!bracket.startsWith("[") || !bracket.endsWith("]")) {
            return List.of();
        }
        String content = bracket.substring(1, bracket.length() - 1);
        if (content.isBlank()) {
            return List.of();
        }
        List<LogSqlParam> params = new ArrayList<>();
        for (String part : content.split(", ")) {
            params.add(new LogSqlParam(part, null));
        }
        return params;
    }

    /** Kotlin {@code String.take(n)} 等价：超长截断。 */
    private static String take(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    public static final String LOGIC_SQL_PREFIX = "Logic SQL:";
    public static final String ACTUAL_SQL_PREFIX = "Actual SQL:";
    public static final String PARAMETERS_KEYWORD = "Parameters:";

    /** Actual 行折叠上限（防异常日志膨胀；路由/条目另有 maxParseLength 与桶上限兜底）。 */
    public static final int MAX_ACTUAL_LINES = 64;

    public static final int DEFAULT_MAX_PARSE_LENGTH = 20000;

    /** 静默冲刷阈值（对齐 {@link JdbcTemplateLogParser#DEFAULT_SETTLE_MS}）。 */
    public static final long DEFAULT_SETTLE_MS = 1000L;
}
