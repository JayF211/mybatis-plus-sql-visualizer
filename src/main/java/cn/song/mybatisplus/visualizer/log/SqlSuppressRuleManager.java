// T-SyncK2JMaster187 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlSuppressRuleManager.kt 移植
package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SQL 屏蔽规则管理器（O-69 T-SqlSuppress）：心跳类 SQL（如 {@code SELECT 1}）的「不再捕获」规则——
 * 增删查 + 上限拒绝 + 命中计数 + 变更通知，跨会话持久化。
 *
 * <p>application-level 轻服务（@Service(Service.Level.APP)，跨项目共享——心跳 SQL 本就跨项目通用，
 * 与 logFilterKeywords/历史记录同作用域）。经 XmlSerializer 持久化到独立文件
 * {@code mp-sql-visualizer-suppress.xml}（不与 {@link PluginSettings} 的 {@code mp-sql-visualizer.xml}
 * 互踩写盘）；应用级必须 {@code RoamingType.DISABLED}（Settings Sync 回滚坑，PluginSettings KDoc）。
 *
 * <p>运行时匹配集 {@link #getSuppressedKeys()}: 各规则 pattern 的 suppressFingerprint
 * 指纹不可变集合，增删规则时<b>整体替换</b>发布——项目级 {@link SqlLogEntryBuffer} 只读该集。
 * 口径 = 去重口径（normalizeSqlWhitespace + sqlFingerprint）追加<b>尾部分号剥离</b>
 * （20260924 用户走查返修：规则粘贴态常带收尾 {@code ;} 而日志还原链不带）；
 * 空白/换行差异天然命中、大小写不命中（D2 定案不变）。
 *
 * <p>变更通知：监听器列表（照 {@code SqlLogEntryBuffer.addListener} 形态），各项目 {@link SqlLogManager}
 * 订阅——回调里重算缓冲的 suppressKeys 并触发面板存量刷新；订阅挂 SqlLogManager 生命周期，
 * project close 时注销。
 *
 * <p>命中计数：闸门命中（{@link SqlLogEntryBuffer} 的 onSuppressedHit → {@link #recordHit}）时 +1，
 * 仅会话内有效<b>不持久化</b>；管理对话框展示「已拦截 N 次」用于确认规则有效（D4）。
 *
 * <p>headless 测试约束：本类除 {@link #getInstance} 外无平台依赖，可直接 {@code new SqlSuppressRuleManager()}
 * 构造纯 JUnit。
 */
@State(
        name = "MpSqlVisualizerSqlSuppressRules",
        storages = @Storage(value = "mp-sql-visualizer-suppress.xml", roamingType = RoamingType.DISABLED)
)
@Service(Service.Level.APP)
public class SqlSuppressRuleManager implements PersistentStateComponent<SqlSuppressRuleManager.State> {

    /** 可持久化状态（XmlSerializer 序列化）。规则按创建序排列（index 0 = 最早）。 */
    public static class State {
        private List<SqlSuppressRule> rules = new ArrayList<>();

        public State() {
        }

        public State(List<SqlSuppressRule> rules) {
            this.rules = rules;
        }

        @NotNull
        public List<SqlSuppressRule> getRules() {
            return rules;
        }

        public void setRules(List<SqlSuppressRule> rules) {
            this.rules = rules;
        }
    }

    private State state = new State();

    /** 规则变更互斥锁（EDT 单写为主，与后台 getState（IDE 保存）隔离，防并发遍历 CME）。 */
    private final Object rulesLock = new Object();

    /**
     * 运行时匹配集（指纹 → 闸门判据）：不可变语义集合，增删规则时整体替换发布（volatile 可见性）。
     * 空集时闸门零成本短路（不触 {@code entry.dedupeKey()} 的惰性计算）。
     */
    private volatile Set<String> suppressedKeys = Set.of();

    /** 指纹 → 规则 id 索引（与 {@link #suppressedKeys} 同批重建发布；命中计数按 id 归账）。 */
    private volatile Map<String, String> keyIndex = Map.of();

    /** 会话内命中计数（键 = 规则 id，不持久化）。 */
    private final ConcurrentHashMap<String, AtomicInteger> hitCounts = new ConcurrentHashMap<>();

    /** 变更监听器（{@link SqlLogManager} 订阅；CopyOnWriteArrayList——注册/注销低频、回调遍历高频）。 */
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    // ---------- 查询 ----------

    /** 全部规则快照（创建序）。 */
    @NotNull
    public List<SqlSuppressRule> getRules() {
        synchronized (rulesLock) {
            return new ArrayList<>(state.getRules());
        }
    }

    /** 运行时匹配集只读入口（{@link SqlLogManager} 订阅回调整批拷入缓冲 suppressKeys）。 */
    @NotNull
    public Set<String> getSuppressedKeys() {
        return suppressedKeys;
    }

    /** 指定指纹是否被屏蔽（面板存量过滤与测试复用；与闸门同判据）。 */
    public boolean isSuppressed(@NotNull String key) {
        return suppressedKeys.contains(key);
    }

    /** 规则 id 的会话内命中次数（无记录返回 0）。 */
    public int hitCount(@NotNull String ruleId) {
        AtomicInteger count = hitCounts.get(ruleId);
        return count != null ? count.get() : 0;
    }

    /**
     * 命中归账：闸门命中指纹 → 规则 id → 计数 +1。规则刚被删除时索引无此键——静默跳过
     * （计数语义「该规则生效期间拦了多少次」，规则已不存在则无处可记）。
     */
    public void recordHit(@NotNull String key) {
        String ruleId = keyIndex.get(key);
        if (ruleId == null) {
            return;
        }
        hitCounts.computeIfAbsent(ruleId, k -> new AtomicInteger()).incrementAndGet();
    }

    // ---------- 增 / 删 / 清 ----------

    /**
     * 新增规则：入参为<b>原始还原 SQL</b>，内部按规范化文本（normalizeSqlWhitespace）成规则——
     * 与运行时匹配同口径，空白差异的变体天然去重。
     *
     * @return {@link AddResult#ADDED}（新建并发布通知）/ {@link AddResult#DUPLICATE}（同规范化文本已存在，
     * 幂等跳过；含空白输入——不产生规则）/ {@link AddResult#LIMIT_REACHED}（达 {@link #MAX_RULES}
     * 上限拒新增，D6：先到先得不做 LRU 淘汰——静默淘汰会让「屏蔽突然失效」无从排查）。
     */
    @NotNull
    public AddResult addRule(@NotNull String sampleSql) {
        String normalized = LogSqlModelsKt.normalizeSqlWhitespace(sampleSql);
        if (normalized.isEmpty()) {
            return AddResult.DUPLICATE; // 无效输入不成规则（调用方 UI 层另有提示）
        }
        synchronized (rulesLock) {
            for (SqlSuppressRule it : state.getRules()) {
                if (it.getPattern().equals(normalized)) {
                    return AddResult.DUPLICATE;
                }
            }
            if (state.getRules().size() >= MAX_RULES) {
                return AddResult.LIMIT_REACHED;
            }
            SqlSuppressRule rule = new SqlSuppressRule(
                    "", normalized, sampleSql.trim(), System.currentTimeMillis(), false);
            rule.ensureId();
            state.getRules().add(rule);
        }
        republish();
        return AddResult.ADDED;
    }

    /** 按 id 删除单条规则；确有删除时发布通知。 */
    public void removeRule(@NotNull String id) {
        boolean removed;
        synchronized (rulesLock) {
            var it = state.getRules().iterator();
            removed = false;
            while (it.hasNext()) {
                if (it.next().getId().equals(id)) {
                    it.remove();
                    removed = true;
                }
            }
        }
        if (removed) {
            republish();
        }
    }

    /** 清空全部规则（管理对话框「清空」；确认交互由调用方负责）；本就为空时不发通知。 */
    public void clearRules() {
        boolean cleared;
        synchronized (rulesLock) {
            boolean wasEmpty = state.getRules().isEmpty();
            state.getRules().clear();
            cleared = !wasEmpty;
        }
        if (cleared) {
            republish();
        }
    }

    /**
     * T-SqlSuppressUx 二段：切换单条规则的<b>结构匹配</b>开关（用户定案：手动开启，默认关）。
     * 未知 id / 状态无变化时不发布通知；确有变化时重建键集（结构开 = 结构指纹键，关 = 精确键）。
     *
     * @return 切换后的实际状态；规则不存在返回 null（调用方 UI 层刷新即可，无需特判）。
     */
    @Nullable
    public Boolean setStructureMatch(@NotNull String ruleId, boolean enabled) {
        boolean[] changed = {false};
        Boolean[] result = {null};
        synchronized (rulesLock) {
            SqlSuppressRule rule = null;
            for (SqlSuppressRule it : state.getRules()) {
                if (it.getId().equals(ruleId)) {
                    rule = it;
                    break;
                }
            }
            if (rule == null) {
                return null; // Kotlin 「?: return null」：锁内短路返回
            }
            changed[0] = rule.isStructureMatch() != enabled;
            rule.setStructureMatch(enabled);
            result[0] = enabled;
        }
        if (changed[0]) {
            republish();
        }
        return result[0];
    }

    /** 批量：全部规则结构匹配开/关（管理对话框「全部按结构匹配」勾选）；无规则或状态无变化不发布。 */
    public void setStructureMatchAll(boolean enabled) {
        boolean changed;
        synchronized (rulesLock) {
            changed = false;
            for (SqlSuppressRule it : state.getRules()) {
                if (it.isStructureMatch() != enabled) {
                    changed = true;
                }
                it.setStructureMatch(enabled);
            }
        }
        if (changed) {
            republish();
        }
    }

    // ---------- 变更通知 ----------

    public void addListener(@NotNull Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull Runnable listener) {
        listeners.remove(listener);
    }

    /**
     * 整体重建并发布运行时匹配集 + 通知监听器。发布与通知的顺序：先原子替换
     * suppressedKeys/keyIndex 再遍历监听器——订阅者回调里读到的必是新集合。
     */
    private void republish() {
        List<SqlSuppressRule> current = getRules();
        Set<String> keys = new HashSet<>(current.size() * 2);
        Map<String, String> index = new HashMap<>(current.size() * 2);
        for (SqlSuppressRule rule : current) {
            // T-SqlSuppressUx：精确口径（去重口径 + 尾分号剥离）与条目侧 LogSqlEntry.suppressKey
            // 同函数；结构匹配开启的规则改用结构指纹（字面量/数字参数化），与
            // LogSqlEntry.structureKey 同源。大小写均仍敏感（D2），空白差异均仍命中
            String key = rule.isStructureMatch()
                    ? LogSqlModelsKt.structureFingerprint(rule.getPattern())
                    : LogSqlModelsKt.suppressFingerprint(rule.getPattern());
            keys.add(key);
            index.put(key, rule.getId());
        }
        suppressedKeys = keys;
        keyIndex = index;
        for (Runnable it : listeners) {
            it.run();
        }
    }

    // ---------- 持久化 ----------

    @NotNull
    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        // 防御 null/损坏：列表为 null 时置空（XmlSerializer 不产 null，外部覆写可能）
        synchronized (rulesLock) {
            this.state = new State(state.getRules() != null ? state.getRules() : new ArrayList<>());
        }
        republish(); // 重建匹配集（启动恢复 / 测试 round-trip 同路）
    }

    /** 新增结果（{@link #addRule} 返回）。 */
    public enum AddResult {
        /** 新建成功。 */
        ADDED,
        /** 已存在同规范化文本规则（或空白输入）——幂等跳过。 */
        DUPLICATE,
        /** 达上限拒新增（{@link #MAX_RULES}）。 */
        LIMIT_REACHED,
    }

    /** 规则上限（D6 定案 500）：超限拒新增并提示，防无界增长。 */
    public static final int MAX_RULES = 500;

    /** 全局实例获取。 */
    @NotNull
    public static SqlSuppressRuleManager getInstance() {
        return ApplicationManager.getApplication().getService(SqlSuppressRuleManager.class);
    }
}
