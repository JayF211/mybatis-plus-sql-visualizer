package cn.song.mybatisplus.visualizer.log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 日志条目缓冲（T-M31-03，纯逻辑，不依赖平台）：条目列表（最早在前、上限裁剪）、
 * 暂停态、去重（对齐 MybatisCodeHelper-Pro「移除重复的sql」——相同 SQL 在窗口内重复丢弃）、
 * 监听器登记——线程安全（任意线程可 {@link #addEntry(LogSqlEntry)}，监听器回调不在此处调度）。
 *
 * <p>T-PerfBatch 监听器分层：原先单一 notifyChanged 通道同时承载三种及时性语义——
 * 首条自动开窗（须即时）、徽章计数（须即时）、列表内容（可批量）——任何「取消逐条通知」
 * 的批量节流都会同时切断前两者（初版全量回归 11 败的根因）。分层后拆为三个独立出口：
 * {@link #setOnEntryAccepted}（每次成功入队，自动展开等「有新 SQL」语义，即时）、
 * {@link #setOnDedupeCountsChanged}（徽章计数变更，即时）、内容监听器（{@link #addListener}，仅列表内容
 * 变更，入队路径走「数量 20 + 时间 200ms」节流，未达阈值条目由 {@link #setOnContentNotifySuppressed}
 * 兜底给管理器的幂等 flush）。
 *
 * <p>与平台壳 {@link SqlLogManager} 分离：本类可纯 JUnit 测试（无 Application 依赖）。
 */
public class SqlLogEntryBuffer {

    private static final int DEFAULT_MAX_ENTRIES = 200;
    private static final long DEFAULT_DEDUPE_WINDOW_MS = 60000L;

    /** T-M55-02：错误反向绑定时间窗默认值（慢查询执行期异常打印仍落在窗口内）。 */
    private static final long DEFAULT_ERROR_BIND_WINDOW_MS = 5 * 60_000L;

    /** T-PerfBatch：内容通知数量阈值——时间窗内累计 N 条才再通知（首条不受限，即时）。 */
    private static final int CONTENT_NOTIFY_BATCH = 20;

    /** T-PerfBatch：内容通知时间阈值（200ms）——距上次通知超窗即即时通知，保证低流量等价即时。 */
    private static final long CONTENT_NOTIFY_INTERVAL_NANOS = 200_000_000L;

    /** T-PerfSweep F3：去重丢弃计数键上限——超限淘汰最旧（见 dedupeCounts 字段注释）。 */
    private static final int MAX_DEDUPE_COUNT_KEYS = 4096;

    /** 最大条目数（可变——运行时动态调整上限，超出自动淘汰最早）。 */
    private volatile int maxEntries;

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<LogSqlEntry> entries = new ArrayDeque<>();

    /** T-LogRowNumber：会话内捕获序号计数器（成功入队时分配；启动项目/手动清空归零。可从任意线程调用故原子）。 */
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    /** T-Perf-03（自 dev 移植）：自上次 drain 以来的头部淘汰条数（面板增量刷新对齐用；drain 约定单消费方——面板 EDT 刷新）。 */
    private final java.util.concurrent.atomic.AtomicInteger evictedSinceDrain = new java.util.concurrent.atomic.AtomicInteger();

    /** T-PerfBatch：内容通知节流原子量——时间窗内累计的未通知条数与上次通知时刻（无 Timer）。 */
    private final AtomicInteger pendingContentNotify = new AtomicInteger(0);
    private final AtomicLong lastContentNotifyNanos = new AtomicLong(0);

    /** 暂停态。 */
    private volatile boolean paused;

    /** 去重开关（默认开——用户实测后要求默认勾选，T-M31.13）。 */
    private volatile boolean dedupeEnabled = true;

    /**
     * 去重丢弃计数（T-Perf-19：键 = restoredSql 指纹 sqlFingerprint，非 SQL 原文——
     * 原文键在条目淘汰后文本永久驻留，长会话可达数十 MB）：去重开启期间每丢弃
     * 一条相同 SQL 计数 +1，供列表「⨉N」徽章展示该 SQL 被去掉过几次。
     * 仅在 dedupeEnabled 为 true 时累计；{@link #clear()} 与 {@link #resetDedupeCounts()} 归零。
     *
     * <p>T-PerfSweep F3 有界化：插入序 LinkedHashMap + 上限 {@link #MAX_DEDUPE_COUNT_KEYS} 淘汰最旧
     * （原 ConcurrentHashMap 无界，条目淘汰后计数不随移除，十万级去重会话可累积百万级键）。
     * 徽章「本次去重开启以来的总丢弃量」语义在上限窗口内不变；超限淘汰最旧属极端会话的
     * 软降级（&gt;4096 种不同 SQL 且都发生过重复），只丢最旧键的计数，列表内容不受影响。
     */
    private final LinkedHashMap<String, Integer> dedupeCounts = new LinkedHashMap<>();

    /** 去重窗口（毫秒）：相同还原 SQL 在该窗口内重复出现时丢弃后到者。 */
    private volatile long dedupeWindowMs = DEFAULT_DEDUPE_WINDOW_MS;

    /** 过滤关键字（T-M31.14，`;` 分隔后传入）：还原 SQL 包含任一关键字则丢弃。 */
    private volatile List<String> filterKeywords = List.of();

    /**
     * T-SqlSuppress（O-69）：屏蔽规则指纹集合（各规则规范化文本的 {@link LogSqlModelsKt#sqlFingerprint}，由
     * {@link SqlSuppressRuleManager} 的项目订阅者 {@link SqlLogManager} 整体替换写入）。入队闸门命中即丢弃——
     * 语义与关键字过滤一致（「从未捕获过」：不进去重计数、不占捕获序号、不触发任何内容/徽章/
     * 入队回调）。空集时短路零成本（不触 {@code entry.dedupeKey()} 的惰性计算——无规则的常态）。
     */
    private volatile Set<String> suppressKeys = Set.of();

    /**
     * T-SqlSuppress：屏蔽闸门命中回调（参数 = 命中指纹键）——{@link SqlSuppressRuleManager#recordHit}
     * 命中计数的唯一出口（管理对话框「已拦截 N 次」，D4）。专用通道，不与既有回调混用：
     * 被拦条目不触发 onEntryAccepted/onEntryStored/onDedupeCountsChanged/内容监听（从未捕获过）。
     */
    private Consumer<String> onSuppressedHit = null;

    /** 条目被淘汰时的回调（最早条目超出上限时被移除）；用于自动保存历史 SQL（F-43）。 */
    private Consumer<LogSqlEntry> onEvicted = null;

    /**
     * T-SqlHistoryFull：成功入队回调（非暂停/过滤/去重丢弃）——历史「全量」写入出口，带条目标。
     * 与 {@link #onEntryAccepted} 的分工：onEntryAccepted 是「有新 SQL」即时语义（无参数）；
     * 本回调把成功入队条目交给写盘方（自动保存），每条成功入队触发一次。
     */
    private Consumer<LogSqlEntry> onEntryStored = null;

    /**
     * T-PerfBatch：每次成功入队（非暂停/过滤/去重丢弃）即回调——「有新 SQL」语义（自动展开）
     * 的即时性出口；去重丢弃分支不回调（重复 SQL 不是新 SQL，且空缓冲不可能产生重复）。
     */
    private Runnable onEntryAccepted = null;

    /**
     * T-PerfBatch：徽章计数变更出口（去重丢弃/裁剪计数/关闭清零/清空列表归零）——
     * ⨉N 徽章必须即时刷新（用户实测「不及时」是缺陷），不走内容节流。
     */
    private Runnable onDedupeCountsChanged = null;

    /**
     * T-PerfBatch：内容通知被节流抑制时的兜底出口——管理器把它接到幂等 flush 上，
     * 保证未达阈值条目最迟在下一次 EDT flush 可见（flush 读当下快照，不丢条目）。
     */
    private Runnable onContentNotifySuppressed = null;

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

    /** Kotlin {@code var dedupeEnabled} 的 JVM 访问器等价入口（Kotlin 调用方按属性名读取）。 */
    public boolean getDedupeEnabled() {
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

    public Set<String> getSuppressKeys() {
        return suppressKeys;
    }

    public void setSuppressKeys(Set<String> suppressKeys) {
        this.suppressKeys = suppressKeys;
    }

    public Consumer<String> getOnSuppressedHit() {
        return onSuppressedHit;
    }

    public void setOnSuppressedHit(Consumer<String> onSuppressedHit) {
        this.onSuppressedHit = onSuppressedHit;
    }

    public Consumer<LogSqlEntry> getOnEvicted() {
        return onEvicted;
    }

    public void setOnEvicted(Consumer<LogSqlEntry> onEvicted) {
        this.onEvicted = onEvicted;
    }

    public Consumer<LogSqlEntry> getOnEntryStored() {
        return onEntryStored;
    }

    public void setOnEntryStored(Consumer<LogSqlEntry> onEntryStored) {
        this.onEntryStored = onEntryStored;
    }

    public Runnable getOnEntryAccepted() {
        return onEntryAccepted;
    }

    public void setOnEntryAccepted(Runnable onEntryAccepted) {
        this.onEntryAccepted = onEntryAccepted;
    }

    public Runnable getOnDedupeCountsChanged() {
        return onDedupeCountsChanged;
    }

    public void setOnDedupeCountsChanged(Runnable onDedupeCountsChanged) {
        this.onDedupeCountsChanged = onDedupeCountsChanged;
    }

    public Runnable getOnContentNotifySuppressed() {
        return onContentNotifySuppressed;
    }

    public void setOnContentNotifySuppressed(Runnable onContentNotifySuppressed) {
        this.onContentNotifySuppressed = onContentNotifySuppressed;
    }

    /** 计数登记（去重丢弃/裁剪共用）；超限淘汰最旧键。 */
    private void recordDedupeDrop(String key) {
        synchronized (dedupeCounts) {
            dedupeCounts.merge(key, 1, Integer::sum);
            if (dedupeCounts.size() > MAX_DEDUPE_COUNT_KEYS) {
                var it = dedupeCounts.keySet().iterator();
                it.next();
                it.remove();
            }
        }
    }

    /**
     * 新条目入队（最早在前，追加到队尾；暂停态丢弃；过滤关键字命中丢弃；去重窗口内相同 SQL 丢弃并计数；超过上限裁掉最旧）。
     *
     * <p>T-PerfBatch 分层通知：去重丢弃分支只走 {@link #onDedupeCountsChanged}（徽章即时，列表内容未变）；
     * 成功入队走 {@link #onEntryAccepted}（即时）+ 内容通知节流（{@link #notifyContentChangedThrottled()}）。
     *
     * <p>T-Perf-06（自 dev 移植）：去重判定与入队合并进同一 synchronized(entries) 临界区——此前 isDuplicate
     * 在锁外无锁遍历 ArrayDeque，与 EDT 清空/删除（clear/removeEntry）并发修改会触发
     * ConcurrentModificationException，经 LogFilterProxyHandler（无 catch）上浮为 IDE 红色报错弹窗。
     */
    /** 普通入队：受「SQL 日志捕获」开关门控（{@code paused} 期间丢弃）。 */
    public boolean addEntry(LogSqlEntry entry) {
        return addEntryInternal(entry, true);
    }

    /**
     * 审计入队：<b>不受「SQL 日志捕获」开关约束</b>（D1 定案，2026-09-22 用户）。
     *
     * <p>MCP 执行类工具（当前唯一产出点 {@code sqlv_explain}）在真实数据源上跑过的语句属<b>审计留痕</b>，
     * 其可用性只由 MCP 工具自身的开关决定（设置页「启用 SQL Visualizer MCP 工具」＋工具级禁用清单，
     * 见 {@code McpToolGate}）——与「SQL 日志」页签的捕获按钮无关。故开关关着（含用户显式停止后的
     * {@code paused=true}）时审计条目<b>照样落列表</b>。
     *
     * <p>其余门与 {@link #addEntry(LogSqlEntry)} 完全一致：关键字过滤 / 去重窗口 / 上限淘汰——它们是列表展示偏好，不是开关。
     */
    public boolean addAuditEntry(LogSqlEntry entry) {
        return addEntryInternal(entry, false);
    }

    private boolean addEntryInternal(LogSqlEntry entry, boolean respectPaused) {
        if (respectPaused && paused) {
            return false;
        }
        if (isFiltered(entry.restoredSql())) {
            return false;
        }
        // T-SqlSuppress（O-69）：屏蔽规则命中 → 入队前丢弃（isFiltered 之后、去重/上限之前——
        // 「从未捕获过」：不进去重计数、不占捕获序号、不触发任何内容回调；命中计数走
        // onSuppressedHit 专用出口）。空集短路：无规则时零成本，不触 dedupeKey 惰性计算。
        // 【反向验证探针锚点】注释掉下面 if 块 = 摘除屏蔽闸门，SqlLogEntryBufferTest 屏蔽用例必红。
        // T-SqlSuppressUx 二段：精确键（默认口径规则）或结构键（手动开启结构匹配的规则）任一命中即拦；
        // 命中键回传 recordHit 归账到对应规则。空集短路下 structureKey 的惰性亦不被触达。
        if (!suppressKeys.isEmpty()) {
            String hitKey = null;
            if (suppressKeys.contains(entry.suppressKey())) {
                hitKey = entry.suppressKey();
            } else if (suppressKeys.contains(entry.structureKey())) {
                hitKey = entry.structureKey();
            }
            if (hitKey != null) {
                Consumer<String> hit = onSuppressedHit;
                if (hit != null) {
                    hit.accept(hitKey);
                }
                return false;
            }
        }
        boolean duplicate = false;
        List<LogSqlEntry> evictedEntries = new ArrayList<>();
        LogSqlEntry[] storedEntry = {null};
        synchronized (entries) {
            if (dedupeEnabled && isDuplicate(entry)) {
                duplicate = true;
            } else {
                // T-LogRowNumber：入队时分配捕获序号（去重丢弃/暂停/过滤不占号，列表不跳号）
                LogSqlEntry added = addNumbered(entry);
                storedEntry[0] = added;
                entries.addLast(added);
                while (entries.size() > maxEntries) {
                    evictedEntries.add(entries.removeFirst());
                    evictedSinceDrain.incrementAndGet(); // T-Perf-03：头部淘汰计数（面板增量刷新）
                }
            }
        }
        if (duplicate) {
            recordDedupeDrop(entry.dedupeKey());
            Runnable badge = onDedupeCountsChanged;
            if (badge != null) {
                badge.run(); // 徽章计数变了 → 即时刷新（不走内容节流）
            }
            return false;
        }
        // T-Perf-12（自 dev 移植）：onEvicted 回调移出 synchronized(entries)——回调链（自动保存磁盘写）
        // 在锁内同步执行会阻塞 EDT snapshot() 抢同一把锁（控制台输出热路径互相卡）
        // T-SqlHistoryFull：被淘汰条目先经 onEntryStored 写过（入队成功即写），此处 onEvicted 不再重复消费
        for (LogSqlEntry evicted : evictedEntries) {
            if (onEvicted != null) {
                onEvicted.accept(evicted);
            }
        }
        // T-SqlHistoryFull：成功入队即写历史——「全量」出口（含随后被淘汰的条目，它们也先进了列表）
        if (storedEntry[0] != null && onEntryStored != null) {
            onEntryStored.accept(storedEntry[0]);
        }
        Runnable accepted = onEntryAccepted;
        if (accepted != null) {
            accepted.run(); // 「有新 SQL」语义即时出口（自动展开等），先于内容节流
        }
        notifyContentChangedThrottled();
        return true;
    }

    /**
     * 手动触发去重裁剪（用户勾选「移除重复的sql」时）：扫描现有 entries，
     * 相同 SQL（按 {@link LogSqlEntry#dedupeKey()} 空白规范化指纹）保留最早一条、丢弃后续；丢弃数累加到 dedupeCounts
     * （与运行时去重共享计数——徽章反映「本次去重开启以来的总丢弃量」，含开启前已存在的重复）。
     *
     * <p>T-LogRowNumber 追加（2026-09-10）：手动全量裁剪<b>不看时间窗口</b>——去重窗口
     * （dedupeWindowMs，默认 60s）是运行时的「相同 SQL 短时间内重复打印」语义，而「点击移除重复」
     * 是用户显式的一次性全量去重，跨窗口的重复（用户不勾选去重运行很久后累积的同文本 SQL）也必须裁掉，
     * 否则点击后重复项仍在列表（用户反馈「显示不正确」）。仅按键判重，不比较时间戳。
     *
     * @return 本次裁剪移除的条目数（面板日志/测试断言）。
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
                // T-LogDedupeMpMark：与 isDuplicate 同键（空白规范化指纹），勾选时对既有跨格式副本同样生效；
                // T-LogRowNumber 追加：手动全量裁剪不比较时间窗口（见方法 KDoc），纯按指纹判重。
                for (LogSqlEntry k : keep) {
                    if (k.dedupeKey().equals(entry.dedupeKey())) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) {
                    recordDedupeDrop(entry.dedupeKey());
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
            notifyChanged(); // 用户操作：内容即时刷新（不节流）
            Runnable badge = onDedupeCountsChanged;
            if (badge != null) {
                badge.run();
            }
        }
        return removed[0];
    }

    /**
     * 关闭去重：清空累计计数（语义：「关闭」= 清零；再次开启时从零起算）。
     * 若原本未开启或计数本就为空，跳过通知（避免无意义 EDT 调度）。
     * T-PerfBatch：只动徽章数据（列表内容不变）——走 {@link #onDedupeCountsChanged}，不走内容通道。
     */
    public void disableDedupe() {
        if (!dedupeEnabled) {
            return;
        }
        dedupeEnabled = false;
        boolean hadCounts;
        synchronized (dedupeCounts) {
            hadCounts = !dedupeCounts.isEmpty();
        }
        resetDedupeCounts();
        if (hadCounts) {
            Runnable badge = onDedupeCountsChanged;
            if (badge != null) {
                badge.run();
            }
        }
    }

    // ---------- T-M55-02：执行报错反向绑定（原地更新最近条目） ----------

    /** 错误反向绑定结果（丢弃原因显式化——桥层据此 LOG.debug，不阻塞主链）。 */
    public enum ErrorBindResult {
        /** 已绑定（含同文本幂等重绑）。 */
        BOUND,
        /** 缓冲为空（条目已被清空/尚未捕获）→ 错误丢弃。 */
        DROPPED_EMPTY,
        /** 最近条目超出绑定时间窗（{@link #getErrorBindWindowMs()}）→ 错误丢弃。 */
        DROPPED_WINDOW_EXPIRED,
        /** 错误文本为空 → 无事可绑。 */
        DROPPED_BLANK,
    }

    /**
     * 错误绑定时间窗（毫秒）：异常打印在 Preparing/Parameters 之后，仅反向绑定该窗口内
     * 入列的最近条目——超窗视为「与缓冲内条目无关的错误」（启动期残留/应用级报错），显式丢弃。
     */
    private volatile long errorBindWindowMs = DEFAULT_ERROR_BIND_WINDOW_MS;

    public long getErrorBindWindowMs() {
        return errorBindWindowMs;
    }

    public void setErrorBindWindowMs(long value) {
        this.errorBindWindowMs = value;
    }

    /**
     * T-M55-02：错误文本反向绑定<b>时间窗内同控制台最近条目</b>（原地更新 errorText）。
     *
     * <ul>
     * <li>原地更新 = 队尾条目 copy(errorText=...) 替换——{@link LogSqlEntry#dedupeKey()} 不含 errorText
     * （SQL 指纹稳定），去重计数与 ⨉N 徽章语义不受影响；</li>
     * <li>绑定成功走 {@link #notifyChanged()}（面板差分刷新；尾位替换在 VisibleDiff 下为全量重建，
     * 由管理器置脏+幂等 flush 合并频率，错误块逐行多次绑定最终只刷一次 EDT）；</li>
     * <li>条目可能已被去重丢弃/淘汰/清空——此处表现为「最近条目缺位或超窗」，显式返回丢弃原因，
     * 绝不阻塞主链（错误块解析失败不影响 SQL 捕获）。</li>
     * </ul>
     */
    public ErrorBindResult bindErrorToRecent(String errorText) {
        if (errorText.isBlank()) return ErrorBindResult.DROPPED_BLANK;
        boolean[] replaced = {false};
        ErrorBindResult[] result = {null};
        synchronized (entries) {
            LogSqlEntry recent = entries.isEmpty() ? null : entries.getLast();
            ErrorBindResult r;
            if (recent == null) {
                r = ErrorBindResult.DROPPED_EMPTY;
            } else if (System.currentTimeMillis() - recent.timestamp() > errorBindWindowMs) {
                r = ErrorBindResult.DROPPED_WINDOW_EXPIRED;
            } else if (errorText.equals(recent.errorText())) {
                // 幂等：同一错误块逐行追加会重复绑定，文本相同视为已绑（不替换不刷新）
                r = ErrorBindResult.BOUND;
            } else {
                entries.removeLast();
                entries.addLast(recent.withErrorText(errorText));
                replaced[0] = true;
                r = ErrorBindResult.BOUND;
            }
            result[0] = r;
        }
        if (replaced[0]) notifyChanged();
        return result[0];
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
     * 当前条目数（T-UxOnboard T-UX-03 增设）：入队热路径（onEntryAccepted 回调）需要 O(1) 判
     * 「本条是否会话首条」——snapshot() 全量拷贝在流式灌入下是 O(n²) 累积成本，不得用于热路径。
     */
    public int size() {
        synchronized (entries) {
            return entries.size();
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

    /**
     * 清空全部（去重计数一并归零——徽章与列表内容同生命周期；
     * T-LogRowNumber：捕获序号一并归零——清空后下一条从 #1 起计数）。
     */
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
        resetDedupeCounts();
        sequenceCounter.set(0);
        pendingContentNotify.set(0); // 面板即将全量重建，悬挂的节流余量作废
        notifyChanged(); // 用户操作：内容即时刷新（不节流）
        Runnable badge = onDedupeCountsChanged;
        if (badge != null) {
            badge.run(); // 计数一并归零（T-PerfBatch 徽章通道）
        }
    }

    /** 去重丢弃计数归零（清空列表、面板重新开启去重时调用）。 */
    public void resetDedupeCounts() {
        synchronized (dedupeCounts) {
            dedupeCounts.clear();
        }
    }

    /**
     * T-LogRowNumber：捕获序号归零（项目启动 processStarted 回调调用）。
     * 不清列表——旧条目保留原行号，仅新一轮启动后的 SQL 从 #1 重新计数。
     */
    public void resetEntrySequence() {
        sequenceCounter.set(0);
    }

    /** 指定 SQL 的去重丢弃次数（参数为还原 SQL 原文，内部按规范化+指纹查，与 LogSqlEntry.dedupeKey 同键；无丢弃记录返回 0）。 */
    public int dedupeCount(String sql) {
        Integer count;
        synchronized (dedupeCounts) {
            count = dedupeCounts.get(LogSqlModelsKt.sqlFingerprint(LogSqlModelsKt.normalizeSqlWhitespace(sql)));
        }
        return count != null ? count : 0;
    }

    /** 去重丢弃计数快照（键 = sqlFingerprint 指纹，值 = 丢弃次数；供面板渲染徽章）。 */
    public Map<String, Integer> dedupeCountsSnapshot() {
        synchronized (dedupeCounts) {
            return new HashMap<>(dedupeCounts);
        }
    }

    /** T-LogRowNumber：入队时以 entry 为原型分配捕获序号（copy 出带号实例入队）。 */
    private LogSqlEntry addNumbered(LogSqlEntry entry) {
        return entry.withSequenceNumber(sequenceCounter.incrementAndGet());
    }

    /**
     * 删除单条（面板右键菜单）。
     *
     * <p>T-LogRowNumber：buffer 入库的是带行号 copy（{@link #addNumbered}），而面板持有的引用来自
     * 列表模型（即入库实例本身）——两者同一，按 <b>同一性</b> 删除即可；不能用 record equals：
     * 序号字段参与 equals，外部构造/改造过的等值条目不应误删列表中的真身。
     */
    public void removeEntry(LogSqlEntry entry) {
        synchronized (entries) {
            entries.removeIf(it -> it == entry);
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

    /**
     * T-PerfBatch：列表内容通知节流——「数量 {@link #CONTENT_NOTIFY_BATCH} 条 + 时间
     * {@link #CONTENT_NOTIFY_INTERVAL_NANOS}」双阈值（原子量，无 Timer）：时间窗内快速灌入时
     * 首条即时通知、后续累计到阈值才再通知；未达阈值条目由 {@link #onContentNotifySuppressed}
     * 兜底给管理器的幂等 flush（flush 读当下快照，不丢条目）。低流量（两条间隔超窗）下
     * 每条即时，行为与节流前等价。
     */
    private void notifyContentChangedThrottled() {
        int pending = pendingContentNotify.incrementAndGet();
        long now = System.nanoTime();
        if (pending >= CONTENT_NOTIFY_BATCH
                || now - lastContentNotifyNanos.get() >= CONTENT_NOTIFY_INTERVAL_NANOS) {
            pendingContentNotify.set(0);
            lastContentNotifyNanos.set(now);
            notifyChanged();
        } else {
            Runnable suppressed = onContentNotifySuppressed;
            if (suppressed != null) {
                suppressed.run();
            }
        }
    }
}
