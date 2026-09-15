package cn.song.mybatisplus.visualizer.sql.rollback

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import cn.song.mybatisplus.visualizer.log.normalizeSqlWhitespace
import cn.song.mybatisplus.visualizer.log.sqlFingerprint

/**
 * 回滚快照登记簿（T-RollbackSql P2-F5/F6）：project 级内存轻服务。
 *
 * dbx 的回滚留痕哲学（`operation="ROLLBACK"`、`rollback_of` 指针）的最小化对应物：
 * 经执行按钮执行的 UPDATE/DELETE 在**执行前**登记一条快照条目——记录目标表/WHERE/快照
 * 查询模板与执行序号，回滚视图据此提供「粘贴快照结果 → 生成完整回滚」的工作流；执行回滚
 * 后置「已执行」标记形成可审计链条。
 *
 * 设计约束：
 *  - **内存态**：会话生命周期即生命周期，不持久化（dbx 的 SQLite 历史库明确不在本期）；
 *  - LogSqlEntry 是 data class 不动字段，快照走本旁路（计划 F5 定案）；
 *  - 路线定夺（javap 实证）：262 连接协议收敛到 `DatabaseConnectionManager.build().createBlocking()`
 *    → `DatabaseConnectionCore.getRemoteConnection()`，其返回类型 `RemoteConnection` **不在本地
 *    发行 jar**（remote 驱动运行时按需加载），且 `AbstractDataSource` 无任何连接方法——程序化
 *    JDBC 快照不可达，按计划预设降级为「快照 SQL 模板 + 用户粘贴结果」半自动方案；
 *  - 条目上限 [MAX_ENTRIES]（LRU 淘汰，防长会话无限膨胀）。
 */
@Service(Service.Level.PROJECT)
class RollbackSnapshotStore(private val project: Project) {

    /** 快照条目状态。 */
    enum class Status {
        /** 已登记（执行按钮已触发 UPDATE/DELETE，快照工作流可用）。 */
        REGISTERED,
        /** 回滚已执行（F6 留痕终态）。 */
        ROLLBACK_EXECUTED,
    }

    /**
     * 快照条目。
     *
     * @param executionId       本次 DML 执行序号（单调递增）
     * @param parentExecutionId 回滚执行时指向被回滚的 DML 执行序号（dbx `rollback_of` 语义；0 = 无）
     * @param createdAt         登记时刻（epoch ms）
     * @param sql               原始执行的 UPDATE/DELETE 文本
     * @param targetTable       目标表（解析失败为 null——不可快照，登记无意义，调用方不登记）
     * @param snapshotSelectSql 快照查询模板 `SELECT * FROM t [WHERE …]`
     * @param setColumns        UPDATE 的 SET 列清单（DELETE 恒空）
     * @param status            状态（见 [Status]）
     */
    data class SnapshotEntry(
        val executionId: Long,
        val parentExecutionId: Long = NO_EXECUTION_ID,
        val createdAt: Long = System.currentTimeMillis(),
        val sql: String,
        val targetTable: String?,
        val snapshotSelectSql: String?,
        val setColumns: List<String> = emptyList(),
        val status: Status = Status.REGISTERED,
    )

    private val entries = object : LinkedHashMap<Long, SnapshotEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, SnapshotEntry>): Boolean =
            size > MAX_ENTRIES
    }

    private val sqlIndex = HashMap<String, Long>()

    /** 执行序号发生器（单调递增）。 */
    private var nextExecutionId = 1L

    /**
     * 登记 DML 快照条目（执行前调用）。按规范化 SQL 指纹建索引，同语句重复执行时
     * 覆盖旧条目（同一语句多次执行，只有最新一次的快照工作流有意义）。
     *
     * @param parentExecutionId 本次执行本身是回滚语句时，指向被回滚的原执行序号
     *                          （dbx `rollback_of` 指针语义；[NO_EXECUTION_ID] = 普通 DML）
     * @return 登记的条目（含分配的 executionId，LOG 埋点用）
     */
    @Synchronized
    fun register(
        sql: String,
        targetTable: String?,
        snapshotSelectSql: String?,
        setColumns: List<String> = emptyList(),
        parentExecutionId: Long = NO_EXECUTION_ID,
    ): SnapshotEntry {
        val fingerprint = fingerprintOf(sql)
        val previous = sqlIndex[fingerprint]
        val entry = SnapshotEntry(
            executionId = nextExecutionId++,
            parentExecutionId = parentExecutionId,
            sql = sql,
            targetTable = targetTable,
            snapshotSelectSql = snapshotSelectSql,
            setColumns = setColumns,
        )
        if (previous != null) {
            entries.remove(previous)
        }
        entries[entry.executionId] = entry
        sqlIndex[fingerprint] = entry.executionId
        LOG.info(
            "[TRollbackSql] snapshot registered: executionId=${entry.executionId}, parent=$parentExecutionId, " +
                "table=${entry.targetTable}, sqlLen=${sql.length}, setColumns=${entry.setColumns}",
        )
        return entry
    }

    /** 按预览/编辑器 SQL 文本查找最近登记的条目（规范化指纹匹配；未命中 null）。 */
    @Synchronized
    fun findBySql(sql: String): SnapshotEntry? {
        val id = sqlIndex[fingerprintOf(sql)] ?: return null
        return entries[id]
    }

    /**
     * F6 留痕：回滚执行完成——原执行条目置「已执行」终态（回滚弹窗横幅与审计链依据）。
     * 返回 false = 条目已不在（LRU 淘汰/未登记），调用方忽略即可。
     */
    @Synchronized
    fun markRollbackExecuted(entry: SnapshotEntry): Boolean {
        val current = entries[entry.executionId] ?: return false
        entries[current.executionId] = current.copy(status = Status.ROLLBACK_EXECUTED)
        LOG.info(
            "[TRollbackSql] rollback executed: targetExecutionId=${entry.executionId}, table=${entry.targetTable}",
        )
        return true
    }

    /** 当前条目数（测试断言用）。 */
    @Synchronized
    fun size(): Int = entries.size

    /** 条目只读快照（测试断言用）。 */
    @Synchronized
    fun entriesForTest(): List<SnapshotEntry> = entries.values.toList()

    /** 清空（测试隔离用）。 */
    @Synchronized
    fun clearForTest() {
        entries.clear()
        sqlIndex.clear()
        nextExecutionId = 1
    }

    private fun fingerprintOf(sql: String): String = sqlFingerprint(normalizeSqlWhitespace(sql))

    companion object {
        /** 条目上限（LRU）。 */
        const val MAX_ENTRIES = 100

        /** 无父执行序号。 */
        const val NO_EXECUTION_ID = 0L

        private val LOG = Logger.getInstance(RollbackSnapshotStore::class.java)
    }
}
