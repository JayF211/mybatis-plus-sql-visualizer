package cn.song.mybatisplus.visualizer.log;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * 控制台日志时间相关器（T-AgentDialectLoop 建，T-AgentLogTime 改键）：
 * 增强捕获接管期间（{@code SqlCaptureGate.paused}=true）控制台链不再产出条目，但日志行里的原始时间戳
 * 仍可提取——本相关器暂存「控制台日志时间」，agent 事件入队前按键取回回填（{@link LogSqlEntry#logTimestamp()}）。
 *
 * <b>键 = logTimeKey（占位符版 rawSql 指纹）</b>：两侧同源原材料，不经过参数还原。
 * 旧口径用 {@code dedupeKey}（还原后 SQL 指纹），要求控制台与 agent 两套还原器逐字符一致——
 * 布尔参数、注释内 {@code ?}、参数个数不等三类分叉都会静默失配（T-AgentLogTime 实证）。
 * 参数值序列（{@code logTimeParamSignature}）只作<b>次级判别</b>：同键多次执行时挑参数一致的那条，
 * 不是命中条件——参数渲染口径再分叉也不会把时间整条丢掉。
 *
 * 结构：{@code LinkedHashMap<键, 队列>}，同键<b>排队</b>而非覆盖（旧实现同键 {@code remove}+{@code put}，
 * 同一条 SQL 连续执行时先来的时间会被后来的顶掉）。{@code take} 先扫同键队列找签名一致项，
 * 找不到退回队首（宁用近似时间也不错失）；超出时间窗（{@link #WINDOW_MS}）的陈旧项直接丢弃，
 * 防长时间接管里与本次执行配错。
 *
 * 并线程模型：控制台过滤线程 put / agent 会话线程 take，两侧各为每条 SQL 一次 O(1)~O(队列长)
 * 操作，锁竞争可忽略。有界（{@link #CAPACITY} 个键、单键 {@link #PER_KEY_CAPACITY} 条）防驻留膨胀。
 *
 * 时序痕迹：MyBatis/JdbcTemplate 日志行打印于 execute 之前，控制台链通常先于 agent 事件到达；
 * 无参语句的条目要等下一条语句到来才闭合（{@code MyBatisLogParser.closePending}），其日志时间可能
 * 赶不上 agent 事件——此时 take 未命中回落捕获时间（T-AgentLogTime 已把捕获时间改为
 * execute <b>开始</b>时刻，与该日志行只差毫秒级），不重试不积压。
 *
 * <p>master 的 {@code object LogTimeCorrelator} 的 Java 承载（全 static + 类锁 synchronized，
 * 并发安全语义与 master 的 {@code @Synchronized} 一致）。</p>
 */
public final class LogTimeCorrelator {

    private LogTimeCorrelator() {
    }

    /** 键上限（512 键 × 单键 ≤ {@link #PER_KEY_CAPACITY} 条；T-PS4-05 起签名单值截断 256 字符，总量典型 MB 级封顶——旧「数万字节」口径按截断前签名失真，已随截断修正）。 */
    private static final int CAPACITY = 512;

    /** 单键队列上限（同一条 SQL 连续执行的待配对条数；防循环里同语句刷爆队列）。 */
    private static final int PER_KEY_CAPACITY = 8;

    /** 陈旧项时间窗：入册后超过此值仍无人认领即丢弃（防「事件被丢后的残留条」配到下一次执行）。 */
    private static final long WINDOW_MS = 30_000L;

    /** master 的 {@code private class Item(val time: Long, val signature: String, val receivedAt: Long)}。 */
    private static final class Item {
        final long time;
        final String signature;
        final long receivedAt;

        Item(long time, String signature, long receivedAt) {
            this.time = time;
            this.signature = signature;
            this.receivedAt = receivedAt;
        }
    }

    private static final LinkedHashMap<String, ArrayDeque<Item>> MAP = new LinkedHashMap<>();

    /** 在册条目总数（容量判定用，单键队列长度不是容量口径）。 */
    private static int items = 0;

    /** 累计未命中次数（诊断：真机排障看是「键不等」还是「时序赶不上」）。 */
    private static long misses = 0L;

    /**
     * 暂存一条控制台日志时间（master 的 {@code signature} 默认参数重载）。
     *
     * @param key {@code logTimeKey}（占位符版 SQL 指纹）
     * @param logTime 日志行原始时间（未解析到时间戳时由调用方回落条目捕获时间）
     */
    public static void put(String key, long logTime) {
        put(key, logTime, "");
    }

    /**
     * 暂存一条控制台日志时间。
     *
     * @param key {@code logTimeKey}（占位符版 SQL 指纹）
     * @param logTime 日志行原始时间（未解析到时间戳时由调用方回落条目捕获时间）
     * @param signature {@code logTimeParamSignature}（次级判别用）
     */
    public static synchronized void put(String key, long logTime, String signature) {
        putAt(key, logTime, signature, System.currentTimeMillis());
    }

    /**
     * {@link #put(String, long, String)} 的显式入册时刻版本——陈旧判定只用<b>入册时刻</b>（本机 IDE 时钟），不用日志行时间：
     * 应用 JVM 时区/时钟与 IDE 不同时（{@code -Duser.timezone}、日志格式带固定时区），日志行时间会整体
     * 偏移，拿它比墙钟会把全部条目误判为陈旧、日志时间静默全失。测试构造「已陈旧」条目也走这里。
     */
    public static synchronized void putAt(String key, long logTime, String signature, long receivedAt) {
        if (key.isEmpty()) {
            return;
        }
        pruneExpired(key, receivedAt);
        ArrayDeque<Item> queue = MAP.computeIfAbsent(key, k -> new ArrayDeque<>());
        queue.addLast(new Item(logTime, signature, receivedAt));
        items++;
        while (queue.size() > PER_KEY_CAPACITY) {
            queue.removeFirst();
            items--;
        }
        while (MAP.size() > CAPACITY) {
            Iterator<ArrayDeque<Item>> eldest = MAP.values().iterator();
            ArrayDeque<Item> entry = eldest.next();
            items -= entry.size();
            eldest.remove();
        }
    }

    /**
     * 取回并消费一条日志时间；未命中返回 null（调用方回落捕获时间）（master 的 {@code signature} 默认参数重载）。
     */
    public static synchronized Long take(String key) {
        return take(key, "");
    }

    /**
     * 取回并消费一条日志时间；未命中返回 null（调用方回落捕获时间）。
     * 同键队列里优先取参数签名一致的那条，找不到退回队首。
     */
    public static synchronized Long take(String key, String signature) {
        ArrayDeque<Item> queue = MAP.get(key);
        if (queue == null) {
            return miss();
        }
        pruneExpired(key, System.currentTimeMillis());
        if (queue.isEmpty()) {
            MAP.remove(key);
            return miss();
        }
        Item matched = null;
        for (Item candidate : queue) {
            if (candidate.signature.equals(signature)) {
                matched = candidate;
                break;
            }
        }
        Item item = matched != null ? matched : queue.getFirst();
        queue.remove(item);
        items--;
        if (queue.isEmpty()) {
            MAP.remove(key);
        }
        return item.time;
    }

    /** 清空（在线态回落/会话切换时调用，防跨运行陈旧匹配）。 */
    public static synchronized void clear() {
        MAP.clear();
        items = 0;
    }

    /** 当前暂存量（测试/诊断用）。 */
    public static synchronized int size() {
        return items;
    }

    /** 累计未命中次数（诊断用）。 */
    public static synchronized long missCount() {
        return misses;
    }

    /** 丢弃超窗陈旧项（按入册时刻判定，见 {@link #putAt}）；队列清空时顺手移除键。 */
    private static void pruneExpired(String key, long now) {
        ArrayDeque<Item> queue = MAP.get(key);
        if (queue == null) {
            return;
        }
        while (!queue.isEmpty() && now - queue.getFirst().receivedAt > WINDOW_MS) {
            queue.removeFirst();
            items--;
        }
        if (queue.isEmpty()) {
            MAP.remove(key);
        }
    }

    private static Long miss() {
        misses++;
        return null;
    }
}
