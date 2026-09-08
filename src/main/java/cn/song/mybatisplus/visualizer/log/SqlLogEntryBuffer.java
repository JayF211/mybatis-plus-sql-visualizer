package cn.song.mybatisplus.visualizer.log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 日志条目缓冲（T-M31-03，纯逻辑，不依赖平台）：条目列表（最早在前、上限裁剪）、
 * 暂停态、去重（对齐 MybatisCodeHelper-Pro「移除重复的sql」——相同 SQL 在窗口内重复丢弃）、
 * 监听器登记——线程安全（任意线程可 {@link #addEntry(LogSqlEntry)}，监听器回调不在此处调度）。
 *
 * 与平台壳 {@link SqlLogManager} 分离：本类可纯 JUnit 测试（无 Application 依赖）。
 */
public class SqlLogEntryBuffer {

    private static final int DEFAULT_MAX_ENTRIES = 200;
    private static final long DEFAULT_DEDUPE_WINDOW_MS = 60000L;

    /** 最大条目数（可变——运行时动态调整上限，超出自动淘汰最早）。 */
    private volatile int maxEntries;

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<LogSqlEntry> entries = new ArrayDeque<>();

    /** T-Perf-03（自 dev 移植）：自上次 drain 以来的头部淘汰条数（面板增量刷新对齐用；drain 约定单消费方——面板 EDT 刷新）。 */
    private final java.util.concurrent.atomic.AtomicInteger evictedSinceDrain = new java.util.concurrent.atomic.AtomicInteger();

    /** 暂停态。 */
    private volatile boolean paused;

    /** 去重开关（默认开——用户实测后要求默认勾选，T-M31.13）。 */
    private volatile boolean dedupeEnabled = true;

    /**
     * 去重丢弃计数（T-Perf-19：键 = restoredSql 指纹 sqlFingerprint，非 SQL 原文——
     * 原文键在条目淘汰后文本永久驻留，长会话可达数十 MB）：去重开启期间每丢弃
     * 一条相同 SQL 计数 +1，供列表「⨉N」徽章展示该 SQL 被去掉过几次。
     */
    private final ConcurrentHashMap<String, Integer> dedupeCounts = new ConcurrentHashMap<>();

    /** 去重窗口（毫秒）：相同还原 SQL 在该窗口内重复出现时丢弃后到者。 */
    private volatile long dedupeWindowMs = DEFAULT_DEDUPE_WINDOW_MS;

    /** 过滤关键字（T-M31.14，`;` 分隔后传入）：还原 SQL 包含任一关键字则丢弃。 */
    private volatile List<String> filterKeywords = List.of();

    /** 条目被淘汰时的回调（最早条目超出上限时被移除）；用于自动保存历史 SQL（F-43）。 */
    private Consumer<LogSqlEntry> onEvicted = null;

    public SqlLogEntryBuffer() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public SqlLogEntryBuffer(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isDedupeEnabled() {
        return dedupeEnabled;
    }

    public void setDedupeEnabled(boolean dedupeEnabled) {
        this.dedupeEnabled = dedupeEnabled;
    }

    public long getDedupeWindowMs() {
        return dedupeWindowMs;
    }

    public void setDedupeWindowMs(long dedupeWindowMs) {
        this.dedupeWindowMs = dedupeWindowMs;
    }

    public List<String> getFilterKeywords() {
        return filterKeywords;
    }

    public void setFilterKeywords(List<String> filterKeywords) {
        this.filterKeywords = filterKeywords;
    }

    public Consumer<LogSqlEntry> getOnEvicted() {
        return onEvicted;
    }

    public void setOnEvicted(Consumer<LogSqlEntry> onEvicted) {
        this.onEvicted = onEvicted;
    }

    /**
     * 新条目入队（最早在前，追加到队尾；暂停态丢弃；过滤关键字命中丢弃；去重窗口内相同 SQL 丢弃并计数；超过上限裁掉最旧）。
     *
     * T-Perf-06（自 dev 移植）：去重判定与入队合并进同一 synchronized(entries) 临界区——此前 isDuplicate
     * 在锁外无锁遍历 ArrayDeque，与 EDT 清空/删除（clear/removeEntry）并发修改会触发
     * ConcurrentModificationException，经 LogFilterProxyHandler（无 catch）上浮为 IDE 红色报错弹窗。
     */
    public boolean addEntry(LogSqlEntry entry) {
        if (paused) {
            return false;
        }
        if (isFiltered(entry.restoredSql())) {
            return false;
        }
        boolean duplicate = false;
        List<LogSqlEntry> evictedEntries = new ArrayList<>();
        synchronized (entries) {
            if (dedupeEnabled && isDuplicate(entry)) {
                duplicate = true;
            } else {
                entries.addLast(entry);
                while (entries.size() > maxEntries) {
                    evictedEntries.add(entries.removeFirst());
                    evictedSinceDrain.incrementAndGet(); // T-Perf-03：头部淘汰计数（面板增量刷新）
                }
            }
        }
        if (duplicate) {
            dedupeCounts.merge(entry.dedupeKey(), 1, Integer::sum);
            notifyChanged(); // ← 徽章计数变了 → UI 必须即时刷新
            return false;
        }
        // T-Perf-12（自 dev 移植）：onEvicted 回调移出 synchronized(entries)——回调链（自动保存磁盘写）
        // 在锁内同步执行会阻塞 EDT snapshot() 抢同一把锁（控制台输出热路径互相卡）
        for (LogSqlEntry evicted : evictedEntries) {
            if (onEvicted != null) {
                onEvicted.accept(evicted);
            }
        }
        notifyChanged();
        return true;
    }

    /**
     * 手动触发去重裁剪（用户勾选「移除重复的sql」时）：扫描现有 entries，
     * 时间窗内相同 SQL 保留最早一条、丢弃后续；丢弃数累加到 dedupeCounts。
     *
     * @return 本次裁剪移除的条目数。
     */
    public int dedupeExisting() {
        if (!dedupeEnabled) {
            return 0;
        }
        int[] removed = {0};
        synchronized (entries) {
            if (entries.size() < 2) {
                return 0;
            }
            List<LogSqlEntry> keep = new ArrayList<>();
            for (LogSqlEntry entry : entries) {
                boolean duplicate = false;
                // T-LogDedupeMpMark：与 isDuplicate 同键（空白规范化指纹），勾选时对既有跨格式副本同样生效
                for (LogSqlEntry k : keep) {
                    if (k.dedupeKey().equals(entry.dedupeKey()) &&
                            Math.abs(entry.timestamp() - k.timestamp()) < dedupeWindowMs) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) {
                    dedupeCounts.merge(entry.dedupeKey(), 1, Integer::sum);
                    removed[0]++;
                } else {
                    keep.add(entry);
                }
            }
            if (removed[0] > 0) {
                entries.clear();
                entries.addAll(keep);
            }
        }
        if (removed[0] > 0) {
            notifyChanged();
        }
        return removed[0];
    }

    /**
     * 关闭去重：清空累计计数（语义：「关闭」= 清零；再次开启时从零起算）。
     */
    public void disableDedupe() {
        if (!dedupeEnabled) {
            return;
        }
        dedupeEnabled = false;
        boolean hadCounts = !dedupeCounts.isEmpty();
        resetDedupeCounts();
        if (hadCounts) {
            notifyChanged();
        }
    }

    private boolean isFiltered(String sql) {
        if (filterKeywords.isEmpty()) {
            return false;
        }
        String lower = sql.toLowerCase();
        for (String k : filterKeywords) {
            if (lower.contains(k.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 去重判定（T-Perf-06：必须在 synchronized(entries) 内调用——无锁遍历 ArrayDeque 会与清空/删除并发 CME）。
     * T-LogDedupeMpMark：按 LogSqlEntry.dedupeKey（空白规范化指纹）比较——JdbcTemplate 双路日志副本仅差
     * 首尾空白，原文精确相等永不命中；预计算指纹串比较也快于全文 ==。
     */
    private boolean isDuplicate(LogSqlEntry entry) {
        long now = entry.timestamp();
        for (LogSqlEntry it : entries) {
            if (it.dedupeKey().equals(entry.dedupeKey()) && now - it.timestamp() < dedupeWindowMs) {
                return true;
            }
        }
        return false;
    }

    /** 最早在前的快照（正序）。 */
    public List<LogSqlEntry> snapshot() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    /**
     * T-Perf-03（自 dev 移植）：取走自上次调用以来的头部淘汰条数并归零。
     * 单消费方约定（面板 EDT 刷新）：与 snapshot() 搭配，用于增量刷新的头部对齐；
     * 其他调用方调用会偷走面板的对齐信息（回退全量重建，正确性不受影响）。
     */
    public int drainEvictedCount() {
        return evictedSinceDrain.getAndSet(0);
    }

    /** 清空全部（去重计数一并归零）。 */
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
        resetDedupeCounts();
        notifyChanged();
    }

    /** 去重丢弃计数归零（清空列表、面板重新开启去重时调用）。 */
    public void resetDedupeCounts() {
        dedupeCounts.clear();
    }

    /** 指定 SQL 的去重丢弃次数（参数为还原 SQL 原文，内部按规范化+指纹查，与 LogSqlEntry.dedupeKey 同键；无丢弃记录返回 0）。 */
    public int dedupeCount(String sql) {
        return dedupeCounts.getOrDefault(LogSqlModelsKt.sqlFingerprint(LogSqlModelsKt.normalizeSqlWhitespace(sql)), 0);
    }

    /** 去重丢弃计数快照（键 = sqlFingerprint 指纹，值 = 丢弃次数；供面板渲染徽章）。 */
    public Map<String, Integer> dedupeCountsSnapshot() {
        return new HashMap<>(dedupeCounts);
    }

    /** 删除单条（面板右键菜单）。 */
    public void removeEntry(LogSqlEntry entry) {
        synchronized (entries) {
            entries.remove(entry);
        }
        notifyChanged();
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void notifyChanged() {
        for (Runnable it : listeners) {
            it.run();
        }
    }
}
