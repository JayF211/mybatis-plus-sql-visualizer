package cn.song.mybatisplus.visualizer.log;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.jvm.functions.Function0;

/**
 * 控制台日志「线程分桶」路由与日志行形态判定（T-LogParseConc）。
 *
 * 背景：单槽状态机假设「SQL 行 → 参数行 → 下一条」严格串行，而真实控制台里多个请求线程的日志
 * 按到达序混排——另一线程的语句行会把本线程尚未收到参数的语句提前闭合，本线程随后到达的参数行
 * 因无上下文被静默丢弃（参数丢失、{@code ?} 未还原）。{@link JdbcTemplateLogParser} 在 T-JdbcThreadBucket
 * （2026-09-15）已按线程 token 分桶修复，本文件把该机制下沉为三解析器共享实现（MyBatis/Hibernate
 * 此前从未跟进，T-LogParseConc 实证为「并发下条目严重不正确」主因）。
 *
 * 路由策略（fail-open，与既有 JdbcTemplate 实现同语义）：
 * - 行内提取到线程 token → 该线程的独立桶（不存在则新建，超上限先淘汰空闲桶）；
 * - 提取不到 token（多行 SQL 的裸续行等）→ 归属当前处于「收集态」的桶（通常唯一），
 *   否则回退共享默认桶——与旧单槽行为一致，不因提取失败而丢行；
 * - {@code routeNoTokenToRecent}=true 时（T-HibConc，Hibernate 专用）追加一条<b>次选</b>：没有收集态桶时
 *   归属<b>最近一次出现线程 token 的桶</b>（MRU）。理由：Hibernate 的多行 SQL 正文与 {@code Hibernate: }
 *   stdout 标志行都不带日志前缀，却紧跟在同线程的带 token 行之后；回退共享桶会让「正文落回退桶、
 *   绑定行落线程桶」分家，双副本合并与参数归属双双失效。MyBatis/JdbcTemplate 不需要该策略（其
 *   裸行必在等待态桶内），故默认关闭以保持已验证链路零变化。
 *
 * 桶状态由各解析器自定（{@code B} 泛型），本类只管「哪一行归哪个桶」与容量。
 *
 * <p>T-ConsolePendingFlush 追加：本类同时记录<b>每个桶的最近活动时间</b>并提供 {@link #settledBuckets}——
 * 分桶把「条目闭合」的前提从「下一条语句到来」改成「<b>同线程</b>的下一行到来」，而同线程下一行可能永不到来
 * （JdbcTemplate 参数行是 TRACE 级默认不开 + Tomcat 按请求新建线程），静默判定是唯一可行的补救信号。
 */
public final class LogThreadRouter<B> {

    private final Supplier<B> factory;

    /** 该桶是否处于「多行 SQL 收集中」——无线程 token 的续行按此归属。 */
    private final Predicate<B> isCollecting;

    /** 该桶是否空闲（无待闭合状态）——超上限淘汰用，淘汰不作为即丢状态，故只淘汰空闲桶。 */
    private final Predicate<B> isIdle;

    private final int maxBuckets;

    /** 无 token 且无收集态桶时，是否归属最近使用的桶（T-HibConc；默认关闭=回退共享桶）。 */
    private final boolean routeNoTokenToRecent;

    /**
     * settle 判定的时钟（毫秒）；测试注入可控件以获得确定性（T-ConsolePendingFlush）。
     * Kotlin 函数类型字段，按移植约定落 {@link Function0}（非 JDK SAM）。
     */
    private final Function0<Long> clock;

    /** 线程 token → 独立桶（保持插入序，便于 closePending 按序冲刷）。 */
    private final LinkedHashMap<String, B> buckets = new LinkedHashMap<>();

    /** token 提取失败时的回退桶（与旧单槽状态机同语义）。 */
    private final B fallback;

    /** 最近一次出现线程 token 的桶（{@code routeNoTokenToRecent} 的次选归属）。 */
    private B recent;

    /**
     * T-ConsolePendingFlush：桶 → 最近一次收到行的时钟毫秒（{@link #settledBuckets} 的判据）。
     * 用 IdentityHashMap（键是桶实例本身，无 equals 语义）；桶被淘汰/清空时同步清理，不滞留已淘汰桶。
     */
    private final IdentityHashMap<B, Long> lastActivity = new IdentityHashMap<>();

    /**
     * 常用形态（master 默认参数）：maxBuckets={@link #DEFAULT_MAX_BUCKETS}、routeNoTokenToRecent=false。
     */
    public LogThreadRouter(Supplier<B> factory, Predicate<B> isCollecting, Predicate<B> isIdle) {
        this(factory, isCollecting, isIdle, DEFAULT_MAX_BUCKETS, false);
    }

    /**
     * T-HibConc 形态：仅开 MRU 次选路由（第 4 参为 {@code routeNoTokenToRecent}，maxBuckets 取默认值）。
     */
    public LogThreadRouter(Supplier<B> factory, Predicate<B> isCollecting, Predicate<B> isIdle,
            boolean routeNoTokenToRecent) {
        this(factory, isCollecting, isIdle, DEFAULT_MAX_BUCKETS, routeNoTokenToRecent);
    }

    /** 全参形态（master 主构造器）。 */
    public LogThreadRouter(Supplier<B> factory, Predicate<B> isCollecting, Predicate<B> isIdle,
            int maxBuckets, boolean routeNoTokenToRecent) {
        this(factory, isCollecting, isIdle, maxBuckets, routeNoTokenToRecent, System::currentTimeMillis);
    }

    /**
     * 含时钟注入形态（T-ConsolePendingFlush：master 主构造 {@code clock} 参数的显式入口；
     * 测试传可控 {@link Function0} 获得确定性）。
     */
    public LogThreadRouter(Supplier<B> factory, Predicate<B> isCollecting, Predicate<B> isIdle,
            int maxBuckets, boolean routeNoTokenToRecent, Function0<Long> clock) {
        this.factory = factory;
        this.isCollecting = isCollecting;
        this.isIdle = isIdle;
        this.maxBuckets = maxBuckets;
        this.routeNoTokenToRecent = routeNoTokenToRecent;
        this.clock = clock;
        this.fallback = factory.get();
    }

    /**
     * T-ConsolePendingFlush：已静默 ≥ {@code settleMs} 的桶（分桶在前、回退桶在后，与 {@link #allBuckets} 同序）。
     *
     * <p>用途：按线程分桶后，一条语句的条目只在「<b>同线程桶</b>里再来一行」时才闭合产出；而该行可能永不
     * 到来——JdbcTemplate 的参数行是 TRACE 级（默认不开），Tomcat 又按请求新建线程，于是每条语句都
     * 停在自己的桶里等一个不存在的后继行，{@code takeEntries()} 永远取不到（面板「已扫描 N 行 / 产出 0 条」）。
     * 静默超过阈值即可认定「后续行不会再来了」（参数行与语句行是同线程相邻输出，正常间隔 1ms 量级），
     * 此时闭合是安全的。时钟取 {@link #clock}（Kotlin 默认参数 {@code nowMs = clock()} 的便捷重载）。
     */
    public List<B> settledBuckets(long settleMs) {
        return settledBuckets(settleMs, clock.invoke());
    }

    /** 同上，显式传当前时刻（master 的 {@code settledBuckets(settleMs, nowMs)} 全参形态）。 */
    public List<B> settledBuckets(long settleMs, long nowMs) {
        List<B> out = new ArrayList<>();
        for (B bucket : buckets.values()) {
            Long last = lastActivity.get(bucket);
            if (last == null) {
                continue;
            }
            if (nowMs - last >= settleMs) {
                out.add(bucket);
            }
        }
        Long fallbackLast = lastActivity.get(fallback);
        if (fallbackLast != null && nowMs - fallbackLast >= settleMs) {
            out.add(fallback);
        }
        return out;
    }

    /** 记录活动时间并返回该桶（路由的三个出口共用）。 */
    private B touch(B bucket) {
        lastActivity.put(bucket, clock.invoke());
        return bucket;
    }

    /** 按行路由到对应桶。 */
    public B bucketFor(String line) {
        String token = threadTokenOf(line);
        if (token != null) {
            B existing = buckets.get(token);
            if (existing != null) {
                recent = existing;
                return touch(existing);
            }
            if (buckets.size() >= maxBuckets) {
                evictIdle();
            }
            B created = factory.get();
            buckets.put(token, created);
            recent = created;
            return touch(created);
        }
        // 无线程 token 行（Hibernate 的多行 SQL 正文 / `Hibernate: ` stdout 标志行等）：
        // 1) MRU 桶优先——Hibernate 一条日志消息只有一个行首前缀，正文行紧跟在**同线程**那条
        //    带 token 行之后（stdout 标志行同理），MRU 是最强信号；且这是「下一条消息的正文」
        //    与「当前块续行」唯一都成立过的解释。
        //    （两者在观测上不可区分：MRU=B 且 A 在收集时，既可能是 B 的正文行、也可能是 A 的续行；
        //     按真实控制台的书写节奏，整条消息一次写出、跨消息交错远比跨行交错常见 → 取 MRU。）
        // 2) 无 MRU 时退「收集态桶」（唯一收集态=确定归属）→ 3) 回退共享桶。
        if (routeNoTokenToRecent) {
            if (recent != null) {
                return touch(recent);
            }
            List<B> collecting = collectCollecting();
            if (!collecting.isEmpty()) {
                return touch(collecting.get(0));
            }
            return touch(fallback);
        }
        // MyBatis/JdbcTemplate：收集态桶优先（其裸行必在等待态桶内），否则回退共享桶
        List<B> collecting = collectCollecting();
        if (!collecting.isEmpty()) {
            for (B b : collecting) {
                if (b == recent) {
                    return touch(b);
                }
            }
            return touch(collecting.get(0));
        }
        return touch(fallback);
    }

    /** 全部桶（分桶在前、回退桶在后）——closePending 全量冲刷用。 */
    public List<B> allBuckets() {
        List<B> result = new ArrayList<>(buckets.values());
        result.add(fallback);
        return result;
    }

    /** 当前活跃桶数（诊断/测试用）。 */
    public int bucketCount() {
        return buckets.size();
    }

    /** 清空（面板销毁/重挂载时）。 */
    public void clear() {
        buckets.clear();
        lastActivity.clear();
        recent = null;
    }

    /** 收集处于「多行 SQL 收集中」的桶（master 的 {@code filter { isCollecting(it) }}）。 */
    private List<B> collectCollecting() {
        List<B> collecting = new ArrayList<>();
        for (B b : buckets.values()) {
            if (isCollecting.test(b)) {
                collecting.add(b);
            }
        }
        return collecting;
    }

    /** 淘汰空闲桶直到低于上限（新建桶前调用）。 */
    private void evictIdle() {
        var iterator = buckets.entrySet().iterator();
        while (iterator.hasNext() && buckets.size() >= maxBuckets) {
            var entry = iterator.next();
            if (isIdle.test(entry.getValue())) {
                if (recent == entry.getValue()) {
                    recent = null;
                }
                lastActivity.remove(entry.getValue()); // 活动时间随桶一并清理（防已淘汰桶滞留）
                iterator.remove();
            }
        }
    }

    /** 单实例最大线程桶数（超限时空闲桶冲刷淘汰，防异常形态下内存膨胀）。 */
    public static final int DEFAULT_MAX_BUCKETS = 64;

    /** 线程 token 提取时，日志级别关键词前后窗口大小（字符）。 */
    private static final int THREAD_WINDOW = 35;

    /** 匹配行内方括号段（线程名 / TID 段 / 纯数字下标）。 */
    private static final Pattern THREAD_BRACKET = Pattern.compile("\\[([^\\]]*)]");

    /** 纯数字判定（column index / 数组下标等非线程名）。 */
    private static final Pattern PURE_DIGIT = Pattern.compile("\\d+");

    /** 日志级别关键词锚点（线程 token 定位；T-PerfSweep 收口为常量，逐行热路径复用）。 */
    private static final Pattern LEVEL_KEYWORD_PATTERN = Pattern.compile("\\b(DEBUG|TRACE|INFO|WARN|ERROR)\\b");

    /** 行首时间戳特征（{@link #looksLikeLogLine} 用，master 文件级 val）。 */
    private static final Pattern REGEX_LINE_START_TIMESTAMP =
            Pattern.compile("^\\s*\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}");

    /** 日志级别关键词特征（{@link #looksLikeLogLine} 用，master 文件级 val）。 */
    private static final Pattern REGEX_LEVEL_KEYWORD = Pattern.compile("\\b(DEBUG|TRACE|INFO|WARN|ERROR)\\b");

    /**
     * 从日志行提取线程 token（并发分桶键）。兼容三种形态：
     * - SkyWalking 注入：{@code 2026-09-15 13:47:17.843 [TID: AE51C…] [http-nio-…-exec-4] DEBUG …}
     * - logback 默认：{@code 2026-08-21 10:00:00.124 TRACE [main] o.h… : …}
     * - Spring Boot 默认：{@code 2026-08-24 15:46:42.444 [TID: null] [cache-preheat-1] TRACE …}
     *
     * 以日志级别关键词为锚，取前后窗口内最近的非 TID 非纯数字方括号段。
     * 返回 null 表示无法可靠提取（→ 回退共享默认桶，fail-open）。
     */
    public static String threadTokenOf(String line) {
        Matcher levelMatch = LEVEL_KEYWORD_PATTERN.matcher(line);
        if (!levelMatch.find()) {
            return null;
        }
        int levelPos = levelMatch.start();
        String beforeLevel = line.substring(Math.max(levelPos - THREAD_WINDOW, 0), levelPos);
        String afterLevel = line.substring(levelPos, Math.min(levelPos + THREAD_WINDOW, line.length()));
        String before = findThread(beforeLevel);
        if (before != null) {
            return before;
        }
        return findThread(afterLevel);
    }

    /** 窗口内最后一个非 TID、非纯数字、非空白的方括号段（master 的 {@code lastOrNull}）。 */
    private static String findThread(String s) {
        String last = null;
        Matcher matcher = THREAD_BRACKET.matcher(s);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (!token.regionMatches(true, 0, "TID:", 0, 4)
                    && !PURE_DIGIT.matcher(token).matches()
                    && !token.isBlank()) {
                last = token;
            }
        }
        return last;
    }

    /**
     * 行是否<b>带日志行特征</b>（T-LogParseConc）：供「空 {@code Parameters:} 等待态」与「SQL 续行」判定共用。
     *
     * 用途有二，缺它两处都会误判：
     * - 空 {@code Parameters:} 行后的下一行若是日志行，说明该语句<b>本就是无参数语句</b>（MyBatis 对无参语句
     *   打印空前缀行）——须闭合且<b>不得吞掉该行</b>；否则整行日志会被当作参数值塞进 {@code ?}。
     * - 多行 SQL 续行判定：日志行即续行终止符。
     *
     * 判定特征（任一命中即为日志行）：{@code ==>} / {@code <==} 前缀、含给定语句关键字（{@code Preparing:}/{@code Parameters:} 等）、
     * 行首时间戳、或「日志级别关键词 + 方括号段」组合（裸参数文本形态 {@code 10(Long), abc(String)} 三者皆不命中）。
     *
     * <p>master 顶层函数 {@code internal fun looksLikeLogLine} 的 Java 承载。</p>
     */
    public static boolean looksLikeLogLine(String line, String... keywords) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (trimmed.startsWith("==>") || trimmed.startsWith("<==")) {
            return true;
        }
        for (String keyword : keywords) {
            if (!keyword.isEmpty() && trimmed.contains(keyword)) {
                return true;
            }
        }
        if (REGEX_LINE_START_TIMESTAMP.matcher(line).find()) {
            return true;
        }
        return REGEX_LEVEL_KEYWORD.matcher(trimmed).find() && trimmed.contains("[");
    }
}
