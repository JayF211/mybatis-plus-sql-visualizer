package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * SQL 屏蔽规则管理器（O-69 T-SqlSuppress）：心跳类 SQL（如 `SELECT 1`）的「不再捕获」规则——
 * 增删查 + 上限拒绝 + 命中计数 + 变更通知，跨会话持久化。
 *
 * application-level 轻服务（@Service(Service.Level.APP)，跨项目共享——心跳 SQL 本就跨项目通用，
 * 与 logFilterKeywords/历史记录同作用域）。经 XmlSerializer 持久化到独立文件
 * `mp-sql-visualizer-suppress.xml`（不与 [PluginSettings] 的 `mp-sql-visualizer.xml` 互踩写盘）；
 * 应用级必须 `RoamingType.DISABLED`（Settings Sync 回滚坑，PluginSettings KDoc）。
 *
 * 运行时匹配集 [suppressedKeys]：各规则 [SqlSuppressRule.pattern] 的 [suppressFingerprint]
 * 指纹不可变集合，增删规则时**整体替换**发布——项目级 [SqlLogEntryBuffer] 只读该集
 * （`entry.suppressKey in set`）。口径 = 去重口径（normalizeSqlWhitespace + sqlFingerprint）
 * 追加**尾部分号剥离**（20260924 用户走查返修：规则粘贴态常带收尾 `;` 而日志还原链不带）；
 * 空白/换行差异天然命中、大小写不命中（D2 定案不变）。
 *
 * 变更通知：监听器列表（照 `SqlLogEntryBuffer.addListener` 形态），各项目 [SqlLogManager]
 * 订阅——回调里重算缓冲的 suppressKeys 并触发面板存量刷新；订阅挂 SqlLogManager 生命周期，
 * project close 时注销。
 *
 * 命中计数：闸门命中（[SqlLogEntryBuffer.onSuppressedHit] → [recordHit]）时 +1，
 * 仅会话内有效**不持久化**；管理对话框展示「已拦截 N 次」用于确认规则有效（D4）。
 *
 * headless 测试约束：本类除 `getInstance` 外无平台依赖，可直接 `SqlSuppressRuleManager()` 构造纯 JUnit。
 */
@State(
    name = "MpSqlVisualizerSqlSuppressRules",
    storages = [Storage("mp-sql-visualizer-suppress.xml", roamingType = RoamingType.DISABLED)],
)
@Service(Service.Level.APP)
class SqlSuppressRuleManager : PersistentStateComponent<SqlSuppressRuleManager.State> {

    /** 可持久化状态（XmlSerializer 序列化）。规则按创建序排列（index 0 = 最早）。 */
    data class State(
        var rules: MutableList<SqlSuppressRule> = mutableListOf(),
    )

    private var state = State()

    /** 规则变更互斥锁（EDT 单写为主，与后台 getState（IDE 保存）隔离，防并发遍历 CME）。 */
    private val rulesLock = Any()

    /**
     * 运行时匹配集（指纹 → 闸门判据）：不可变集合，增删规则时整体替换发布（@Volatile 可见性）。
     * 空集时闸门零成本短路（不触 `entry.dedupeKey` 的 lazy 计算）。
     */
    @Volatile
    var suppressedKeys: Set<String> = emptySet()
        private set

    /** 指纹 → 规则 id 索引（与 [suppressedKeys] 同批重建发布；命中计数按 id 归账）。 */
    @Volatile
    private var keyIndex: Map<String, String> = emptyMap()

    /** 会话内命中计数（键 = 规则 id，不持久化）。 */
    private val hitCounts = ConcurrentHashMap<String, AtomicInteger>()

    /** 变更监听器（[SqlLogManager] 订阅；CopyOnWriteArrayList——注册/注销低频、回调遍历高频）。 */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    // ---------- 查询 ----------

    /** 全部规则快照（创建序）。 */
    val rules: List<SqlSuppressRule>
        get() = synchronized(rulesLock) { state.rules.toList() }

    /** 指定指纹是否被屏蔽（面板存量过滤与测试复用；与闸门同判据）。 */
    fun isSuppressed(key: String): Boolean = suppressedKeys.contains(key)

    /** 规则 id 的会话内命中次数（无记录返回 0）。 */
    fun hitCount(ruleId: String): Int = hitCounts[ruleId]?.get() ?: 0

    /**
     * 命中归账：闸门命中指纹 → 规则 id → 计数 +1。规则刚被删除时索引无此键——静默跳过
     * （计数语义「该规则生效期间拦了多少次」，规则已不存在则无处可记）。
     */
    fun recordHit(key: String) {
        val ruleId = keyIndex[key] ?: return
        hitCounts.computeIfAbsent(ruleId) { AtomicInteger() }.incrementAndGet()
    }

    // ---------- 增 / 删 / 清 ----------

    /**
     * 新增规则：入参为**原始还原 SQL**，内部按规范化文本（[normalizeSqlWhitespace]）成规则——
     * 与运行时匹配同口径，空白差异的变体天然去重。
     *
     * @return [AddResult.ADDED]（新建并发布通知）/ [AddResult.DUPLICATE]（同规范化文本已存在，
     * 幂等跳过；含空白输入——不产生规则）/ [AddResult.LIMIT_REACHED]（达 [MAX_RULES] 上限拒新增，
     * D6：先到先得不做 LRU 淘汰——静默淘汰会让「屏蔽突然失效」无从排查）。
     */
    fun addRule(sampleSql: String): AddResult {
        val normalized = normalizeSqlWhitespace(sampleSql)
        if (normalized.isEmpty()) return AddResult.DUPLICATE // 无效输入不成规则（调用方 UI 层另有提示）
        synchronized(rulesLock) {
            if (state.rules.any { it.pattern == normalized }) return AddResult.DUPLICATE
            if (state.rules.size >= MAX_RULES) return AddResult.LIMIT_REACHED
            state.rules += SqlSuppressRule(
                pattern = normalized,
                sampleSql = sampleSql.trim(),
                createdAt = System.currentTimeMillis(),
            ).apply { ensureId() }
        }
        republish()
        return AddResult.ADDED
    }

    /** 按 id 删除单条规则；确有删除时发布通知。 */
    fun removeRule(id: String) {
        val removed = synchronized(rulesLock) {
            val it = state.rules.iterator()
            var removed = false
            while (it.hasNext()) {
                if (it.next().id == id) {
                    it.remove()
                    removed = true
                }
            }
            removed
        }
        if (removed) republish()
    }

    /** 清空全部规则（管理对话框「清空」；确认交互由调用方负责）；本就为空时不发通知。 */
    fun clearRules() {
        val cleared = synchronized(rulesLock) {
            val wasEmpty = state.rules.isEmpty()
            state.rules.clear()
            !wasEmpty
        }
        if (cleared) republish()
    }

    // ---------- 变更通知 ----------

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /**
     * 整体重建并发布运行时匹配集 + 通知监听器。发布与通知的顺序：先原子替换
     * [suppressedKeys]/[keyIndex] 再遍历监听器——订阅者回调里读到的必是新集合。
     */
    private fun republish() {
        val current = rules
        val keys = HashSet<String>(current.size * 2)
        val index = HashMap<String, String>(current.size * 2)
        for (rule in current) {
            // T-SqlSuppressUx（20260924）：走屏蔽口径（去重口径 + 尾分号剥离）——与条目侧
            // LogSqlEntry.suppressKey 同函数同源；大小写仍敏感（D2），空白差异仍命中
            val key = suppressFingerprint(rule.pattern)
            keys += key
            index[key] = rule.id
        }
        suppressedKeys = keys
        keyIndex = index
        listeners.forEach { it() }
    }

    // ---------- 持久化 ----------

    override fun getState(): State = state

    override fun loadState(state: State) {
        // 防御 null/损坏：列表为 null 时置空（XmlSerializer 不产 null，外部覆写可能）
        synchronized(rulesLock) {
            this.state = State(rules = state.rules ?: mutableListOf())
        }
        republish() // 重建匹配集（启动恢复 / 测试 round-trip 同路）
    }

    /** 新增结果（[addRule] 返回）。 */
    enum class AddResult {
        /** 新建成功。 */
        ADDED,
        /** 已存在同规范化文本规则（或空白输入）——幂等跳过。 */
        DUPLICATE,
        /** 达上限拒新增（[MAX_RULES]）。 */
        LIMIT_REACHED,
    }

    companion object {
        /** 规则上限（D6 定案 500）：超限拒新增并提示，防无界增长。 */
        const val MAX_RULES = 500

        /** 全局实例获取。 */
        @JvmStatic
        fun getInstance(): SqlSuppressRuleManager =
            ApplicationManager.getApplication().getService(SqlSuppressRuleManager::class.java)
    }
}
