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

    /** 暂停态。 */
    private volatile boolean paused;

    /** 去重开关（默认开——用户实测后要求默认勾选，T-M31.13）。 */
    private volatile boolean dedupeEnabled = true;

    /**
     * 去重丢弃计数（键 = restoredSql 原文，与 {@link #isDuplicate(LogSqlEntry)} 匹配同源）：去重开启期间每丢弃
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
     */
    public boolean addEntry(LogSqlEntry entry) {
        if (paused) {
            return false;
        }
        if (isFiltered(entry.restoredSql())) {
            return false;
        }
        if (dedupeEnabled && isDuplicate(entry)) {
            dedupeCounts.merge(entry.restoredSql(), 1, Integer::sum);
            notifyChanged(); // ← 徽章计数变了 → UI 必须即时刷新
            return false;
        }
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > maxEntries) {
                LogSqlEntry evicted = entries.removeFirst();
                if (onEvicted != null) {
                    onEvicted.accept(evicted);
                }
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
                for (LogSqlEntry k : keep) {
                    if (k.restoredSql().equals(entry.restoredSql()) &&
                            Math.abs(entry.timestamp() - k.timestamp()) < dedupeWindowMs) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) {
                    dedupeCounts.merge(entry.restoredSql(), 1, Integer::sum);
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

    private boolean isDuplicate(LogSqlEntry entry) {
        long now = entry.timestamp();
        synchronized (entries) {
            for (LogSqlEntry it : entries) {
                if (it.restoredSql().equals(entry.restoredSql()) && now - it.timestamp() < dedupeWindowMs) {
                    return true;
                }
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

    /** 指定 SQL 的去重丢弃次数（无丢弃记录返回 0）。 */
    public int dedupeCount(String sql) {
        return dedupeCounts.getOrDefault(sql, 0);
    }

    /** 去重丢弃计数快照（键 = restoredSql，值 = 丢弃次数；供面板渲染徽章）。 */
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
