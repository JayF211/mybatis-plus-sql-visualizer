package cn.song.mybatisplus.visualizer.history

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * SQL 历史记录管理器（F-7）：增删改查 + 上限淘汰 + 收藏 + 标签 + 搜索，跨会话持久化。
 *
 * application-level 轻服务（@Service(Service.Level.APP)，跨项目共享），经 XmlSerializer
 * 持久化到 `mp-sql-visualizer.xml`（与 [PluginSettings] 同文件）。
 *
 * 性能约束：
 * - [add] 均摊 O(1)（队尾追加 + 队首淘汰）
 * - 上限默认 200（可经 [maxSize] 配置），超出时淘汰最旧非收藏条目
 * - [search] 子串匹配，200 条 < 10ms
 */
@State(
    name = "MpSqlVisualizerHistory",
    storages = [Storage("mp-sql-visualizer.xml", roamingType = RoamingType.DISABLED)],
)
@Service(Service.Level.APP)
class SqlHistoryManager : PersistentStateComponent<SqlHistoryManager.State> {

    /**
     * 可持久化状态（XmlSerializer 序列化）。
     * 条目按时间倒序排列（index 0 = 最新）；[favoriteEntries] 独立存放，不参与上限淘汰。
     */
    data class State(
        var entries: MutableList<SqlHistoryEntry> = mutableListOf(),
        var favoriteEntries: MutableList<SqlHistoryEntry> = mutableListOf(),
    )

    private var state = State()

    /** 历史记录上限（非收藏条目）。默认 200，跟随 [PluginSettings.historyMaxSize] 配置。 */
    var maxSize: Int = DEFAULT_MAX_SIZE
        set(value) { field = value.coerceAtLeast(1) }

    // ---------- 添加 ----------

    /**
     * 添加一条历史记录（均摊 O(1)）。
     *
     * 自动分配 id；按时间戳插入正确位置（倒序）。收藏条目进 [State.favoriteEntries]，
     * 非收藏条目进 [State.entries] 并在超出 [maxSize] 时淘汰最旧者。
     *
     * @return 添加后的条目（含分配的 id）
     */
    fun add(entry: SqlHistoryEntry): SqlHistoryEntry {
        entry.ensureId()
        if (entry.favorite) {
            insertSorted(state.favoriteEntries, entry)
            evictFavoritesIfNeeded()
        } else {
            insertSorted(state.entries, entry)
            evictIfNeeded()
        }
        return entry
    }

    /**
     * T-Perf-19：收藏条目上限（[MAX_FAVORITES]）——此前 favoriteEntries 永不淘汰，
     * 长期使用无限增长（持久化文件与内存双膨胀）。超出时淘汰最旧收藏（队尾）。
     */
    private fun evictFavoritesIfNeeded() {
        while (state.favoriteEntries.size > MAX_FAVORITES) {
            state.favoriteEntries.removeAt(state.favoriteEntries.lastIndex)
        }
    }

    /**
     * 按时间戳倒序插入（index 0 = 最新）。遍历找到第一个 timestamp <= 新条目的位置插入。
     * 列表最多 200 条，线性遍历 O(n) 可接受（均摊仍 O(1)——追加到头部最常见）。
     */
    private fun insertSorted(list: MutableList<SqlHistoryEntry>, entry: SqlHistoryEntry) {
        val idx = list.indexOfFirst { it.timestamp <= entry.timestamp }
        if (idx < 0) list.add(entry) else list.add(idx, entry)
    }

    /**
     * 超出上限时淘汰最旧的非收藏条目（队尾）。R15：钉住条目跳过（防翻找丢失）——
     * 从队尾向前找首个非钉住条目移除；全为钉住则不淘汰（钉住总量由 MAX_PINNED 把关）。
     */
    private fun evictIfNeeded() {
        while (state.entries.size > maxSize) {
            val idx = state.entries.indexOfLast { !it.pinned }
            if (idx < 0) return // 全为钉住：不再淘汰（钉住上限由 togglePin 把关）
            state.entries.removeAt(idx)
        }
    }

    /** R15：钉住上限（防钉住分区无限膨胀挤占淘汰机制）。 */
    private val MAX_PINNED = 50

    /** R15：切换钉住态；钉住数达 [MAX_PINNED] 上限时拒绝并返回 false。 */
    fun togglePin(id: String): Boolean {
        val entry = findById(id) ?: return false
        if (!entry.pinned) {
            val pinnedCount = state.entries.count { it.pinned } + state.favoriteEntries.count { it.pinned }
            if (pinnedCount >= MAX_PINNED) return false
        }
        entry.pinned = !entry.pinned
        return true
    }

    // ---------- 查询（最新在前） ----------

    /** 全部条目（含收藏与非收藏，按时间倒序合并）。 */
    val entries: List<SqlHistoryEntry>
        get() = mergeByTimestamp(state.favoriteEntries, state.entries)

    /** 按 id 查找条目（含收藏分区）。 */
    fun findById(id: String): SqlHistoryEntry? =
        state.entries.firstOrNull { it.id == id } ?: state.favoriteEntries.firstOrNull { it.id == id }

    /** 收藏条目（时间倒序）。 */
    fun filterFavorites(): List<SqlHistoryEntry> = state.favoriteEntries.toList()

    // ---------- 收藏 ----------

    /**
     * 切换收藏状态。
     * 收藏 → 移入 [State.favoriteEntries]；取消收藏 → 移回 [State.entries]。
     */
    fun toggleFavorite(id: String) {
        val inNormal = state.entries.indexOfFirst { it.id == id }
        if (inNormal >= 0) {
            val e = state.entries.removeAt(inNormal)
            e.favorite = true
            insertSorted(state.favoriteEntries, e)
            evictFavoritesIfNeeded()
            return
        }
        val inFav = state.favoriteEntries.indexOfFirst { it.id == id }
        if (inFav >= 0) {
            val e = state.favoriteEntries.removeAt(inFav)
            e.favorite = false
            insertSorted(state.entries, e)
            evictIfNeeded()
        }
    }

    // ---------- 标签 ----------

    /** 为条目添加标签（去重）。 */
    fun addTag(id: String, tag: String) {
        if (tag.isBlank()) return
        findById(id)?.let { e ->
            if (e.tags.none { it == tag }) e.tags.add(tag)
        }
    }

    /** 移除条目的指定标签。 */
    fun removeTag(id: String, tag: String) {
        findById(id)?.tags?.remove(tag)
    }

    /** 全部标签集合（所有条目的标签并集）。 */
    fun allTags(): Set<String> =
        (state.entries + state.favoriteEntries).flatMap { it.tags }.toSet()

    /** 按标签筛选条目（精确匹配，时间倒序）。 */
    fun filterByTag(tag: String): List<SqlHistoryEntry> =
        entries.filter { it.tags.contains(tag) }

    // ---------- 搜索 ----------

    /**
     * 子串搜索（SQL 文本，忽略大小写）。空查询返回全量。
     * 简单 substring 匹配，200 条 < 10ms。
     */
    fun search(query: String): List<SqlHistoryEntry> {
        if (query.isBlank()) return entries
        val q = query.trim().lowercase()
        return entries.filter { it.sql.lowercase().contains(q) || it.compactSql.lowercase().contains(q) }
    }

    // ---------- 删除 / 清空 ----------

    /** 按 id 删除条目。 */
    fun remove(id: String) {
        state.entries.removeAll { it.id == id }
        state.favoriteEntries.removeAll { it.id == id }
    }

    /** 清空全部历史（含收藏）。 */
    fun clear() {
        state.entries.clear()
        state.favoriteEntries.clear()
    }

    // ---------- 持久化 ----------

    override fun getState(): State = state

    override fun loadState(state: State) {
        // 防御 null/损坏：任一列表为 null 时置空（XmlSerializer 不会产 null，但外部覆写可能）
        this.state = State(
            entries = state.entries ?: mutableListOf(),
            favoriteEntries = state.favoriteEntries ?: mutableListOf(),
        )
    }

    // ---------- 内部工具 ----------

    /** 两个分区按时间戳倒序合并。 */
    private fun mergeByTimestamp(
        a: List<SqlHistoryEntry>,
        b: List<SqlHistoryEntry>,
    ): List<SqlHistoryEntry> {
        val result = ArrayList<SqlHistoryEntry>(a.size + b.size)
        var i = 0; var j = 0
        while (i < a.size && j < b.size) {
            if (a[i].timestamp >= b[j].timestamp) result.add(a[i++]) else result.add(b[j++])
        }
        while (i < a.size) result.add(a[i++])
        while (j < b.size) result.add(b[j++])
        return result
    }

    companion object {
        /** 默认历史记录上限（非收藏条目）。 */
        const val DEFAULT_MAX_SIZE = 200

        /** T-Perf-19：收藏条目上限（宽松封顶防无限增长）。 */
        const val MAX_FAVORITES = 500

        /** 全局实例获取。 */
        @JvmStatic
        fun getInstance(): SqlHistoryManager =
            ApplicationManager.getApplication().getService(SqlHistoryManager::class.java)
    }
}
