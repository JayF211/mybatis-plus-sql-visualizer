package cn.song.mybatisplus.visualizer.sql.rollback

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import cn.song.mybatisplus.visualizer.log.normalizeSqlWhitespace
import cn.song.mybatisplus.visualizer.log.sqlFingerprint
import cn.song.mybatisplus.visualizer.sql.Dialect

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
        /** 回滚已提交执行（F6 留痕终态）；不代表数据库执行成功或事务已提交。 */
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
     * @param dialect           登记时的方言（O-52 回滚侧 identity 包装用；**null = 未知 → 不包装**）
     * @param identityColumns   目标表的自增列名（O-52；**空 = 未知 → 不包装**）
     *
     * O-52（D1=A 定案）：回滚链的行值来自粘贴 TSV、`backfillInsert` 生成 INSERT，显式插入自增列
     * 在 SQL Server 上必然报 544——identity 知识只有**登记时**持有，故随快照一起留存。
     * 两个字段均带默认值：旧条目（本会话改前登记的）与未传参的调用方**逐字节不变地**落到
     * 「未知」，包装不触发（与既有「未知语义不臆造」口径一致）；[RollbackSnapshotStore] 是内存态、
     * 不持久化（见类 KDoc），不存在跨会话反序列化旧格式的问题，默认值只服务「调用方未传」这一形态。
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
        val dialect: Dialect? = null,
        val identityColumns: List<String> = emptyList(),
    )

    /** 条目 LRU（容量 [MAX_ENTRIES] 条；访问序）。 */
    private val entries = object : LinkedHashMap<Long, SnapshotEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, SnapshotEntry>): Boolean {
            if (size <= MAX_ENTRIES) return false
            // T-PS5-10：LRU 裁 entries 时**同步裁指纹索引**——此前只裁 entries，[sqlIndex] 随长会话
            // distinct DML 数单调增长（100 条容量语义只对 entries 生效，索引无上界）。
            pruneSqlIndex(eldest.key)
            return true
        }
    }

    /** SQL 指纹 → 执行序号索引（register 覆盖写；条目被 LRU 淘汰时同步摘除，见上）。 */
    private val sqlIndex = HashMap<String, Long>()

    /** T-PS5-10：摘掉指向 [executionId] 的指纹索引（至多一条；LRU 淘汰与惰性清理共用）。 */
    private fun pruneSqlIndex(executionId: Long) {
        val it = sqlIndex.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value == executionId) it.remove()
        }
    }

    /** T-PS5-10 测试缝：指纹索引规模（断言随 LRU 有界；生产链路无调用点）。 */
    internal fun sqlIndexSizeForTest(): Int = sqlIndex.size

    /** 执行序号发生器（单调递增）。 */
    private var nextExecutionId = 1L

    /**
     * 登记 DML 快照条目（执行前调用）。按规范化 SQL 指纹建索引，同语句重复执行时
     * 覆盖旧条目（同一语句多次执行，只有最新一次的快照工作流有意义）。
     *
     * @param parentExecutionId 本次执行本身是回滚语句时，指向被回滚的原执行序号
     *                          （dbx `rollback_of` 指针语义；[NO_EXECUTION_ID] = 普通 DML）
     * @param dialect           登记时的方言（O-52；null = 未知 → 回滚侧不包装）
     * @param identityColumns   目标表的自增列名（O-52；空 = 未知 → 回滚侧不包装）
     * @return 登记的条目（含分配的 executionId，LOG 埋点用）
     */
    @Synchronized
    fun register(
        sql: String,
        targetTable: String?,
        snapshotSelectSql: String?,
        setColumns: List<String> = emptyList(),
        parentExecutionId: Long = NO_EXECUTION_ID,
        dialect: Dialect? = null,
        identityColumns: List<String> = emptyList(),
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
            dialect = dialect,
            identityColumns = identityColumns,
        )
        if (previous != null) {
            entries.remove(previous)
        }
        entries[entry.executionId] = entry
        sqlIndex[fingerprint] = entry.executionId
        LOG.info(
            "[TRollbackSql] snapshot registered: executionId=${entry.executionId}, parent=$parentExecutionId, " +
                "table=${entry.targetTable}, sqlLen=${sql.length}, setColumns=${entry.setColumns}, " +
                "dialect=${entry.dialect?.name}, identityColumns=${entry.identityColumns}",
        )
        return entry
    }

    /** 按预览/编辑器 SQL 文本查找最近登记的条目（规范化指纹匹配；未命中 null）。 */
    @Synchronized
    fun findBySql(sql: String): SnapshotEntry? {
        val fingerprint = fingerprintOf(sql)
        val id = sqlIndex[fingerprint] ?: return null
        val entry = entries[id]
        // T-PS5-10：索引指向的条目已不在（LRU 淘汰/终态清理）→ 顺手摘掉不可达指纹（懒清理双保险）
        if (entry == null) sqlIndex.remove(fingerprint)
        return entry
    }

    /**
     * F6 留痕：收到真实 SUBMITTED 回执后置提交终态（兼容保留 ROLLBACK_EXECUTED 枚举名）。
     * 只记录回滚已交给执行器，不证明数据库执行成功；禁止在选择、排队或取消时调用。
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
