// T-SyncK2JMaster309 自 master sql/rollback/RollbackSnapshotStore.kt 移植
package cn.song.mybatisplus.visualizer.sql.rollback;

import cn.song.mybatisplus.visualizer.log.LogSqlModelsKt;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回滚快照登记簿（T-RollbackSql P2-F5/F6）：project 级内存轻服务。
 *
 * <p>dbx 的回滚留痕哲学（`operation="ROLLBACK"`、`rollback_of` 指针）的最小化对应物：
 * 经执行按钮执行的 UPDATE/DELETE 在<b>执行前</b>登记一条快照条目——记录目标表/WHERE/快照
 * 查询模板与执行序号，回滚视图据此提供「粘贴快照结果 → 生成完整回滚」的工作流；执行回滚
 * 后置「已执行」标记形成可审计链条。
 *
 * <p>设计约束：
 * <ul>
 *  <li><b>内存态</b>：会话生命周期即生命周期，不持久化（dbx 的 SQLite 历史库明确不在本期）；</li>
 *  <li>LogSqlEntry 是 data class 不动字段，快照走本旁路（计划 F5 定案）；</li>
 *  <li>路线定夺（javap 实证）：262 连接协议收敛到 `DatabaseConnectionManager.build().createBlocking()`
 *      → `DatabaseConnectionCore.getRemoteConnection()`，其返回类型 `RemoteConnection` <b>不在本地
 *      发行 jar</b>（remote 驱动运行时按需加载），且 `AbstractDataSource` 无任何连接方法——程序化
 *      JDBC 快照不可达，按计划预设降级为「快照 SQL 模板 + 用户粘贴结果」半自动方案；</li>
 *  <li>条目上限 {@link #MAX_ENTRIES}（LRU 淘汰，防长会话无限膨胀）。</li>
 * </ul>
 */
@Service(Service.Level.PROJECT)
public final class RollbackSnapshotStore {

    private final Project project;

    /** 快照条目状态。 */
    public enum Status {
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
     * @param status            状态（见 {@link Status}）
     */
    public record SnapshotEntry(
            /** 本次 DML 执行序号（单调递增）。 */
            long executionId,
            /** 回滚执行时指向被回滚的 DML 执行序号（dbx `rollback_of` 语义；0 = 无）。 */
            long parentExecutionId,
            /** 登记时刻（epoch ms）。 */
            long createdAt,
            /** 原始执行的 UPDATE/DELETE 文本。 */
            @NotNull String sql,
            /** 目标表（解析失败为 null——不可快照，登记无意义，调用方不登记）。 */
            @Nullable String targetTable,
            /** 快照查询模板 `SELECT * FROM t [WHERE …]`。 */
            @Nullable String snapshotSelectSql,
            /** UPDATE 的 SET 列清单（DELETE 恒空）。 */
            @NotNull List<String> setColumns,
            /** 状态（见 {@link Status}）。 */
            @NotNull Status status
    ) {
        /** 常用形态：createdAt 取当前时刻、status 初始 REGISTERED。 */
        public SnapshotEntry(long executionId, long parentExecutionId, @NotNull String sql,
                             @Nullable String targetTable, @Nullable String snapshotSelectSql,
                             @NotNull List<String> setColumns) {
            this(executionId, parentExecutionId, System.currentTimeMillis(), sql, targetTable,
                    snapshotSelectSql, setColumns, Status.REGISTERED);
        }

        /** 最简形态：普通 DML（无父执行序号）。 */
        public SnapshotEntry(long executionId, @NotNull String sql,
                             @Nullable String targetTable, @Nullable String snapshotSelectSql) {
            this(executionId, NO_EXECUTION_ID, sql, targetTable, snapshotSelectSql, List.of());
        }
    }

    /** 条目上限（LRU）。 */
    public static final int MAX_ENTRIES = 100;

    /** 无父执行序号。 */
    public static final long NO_EXECUTION_ID = 0L;

    private static final Logger LOG = Logger.getInstance(RollbackSnapshotStore.class);

    /** LRU（访问序）条目表。 */
    private final LinkedHashMap<Long, SnapshotEntry> entries = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, SnapshotEntry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private final Map<String, Long> sqlIndex = new HashMap<>();

    /** 执行序号发生器（单调递增）。 */
    private long nextExecutionId = 1L;

    public RollbackSnapshotStore(@NotNull Project project) {
        this.project = project;
    }

    /**
     * 登记 DML 快照条目（执行前调用）。按规范化 SQL 指纹建索引，同语句重复执行时
     * 覆盖旧条目（同一语句多次执行，只有最新一次的快照工作流有意义）。
     *
     * @param parentExecutionId 本次执行本身是回滚语句时，指向被回滚的原执行序号
     *                          （dbx `rollback_of` 指针语义；{@link #NO_EXECUTION_ID} = 普通 DML）
     * @return 登记的条目（含分配的 executionId，LOG 埋点用）
     */
    @NotNull
    public synchronized SnapshotEntry register(@NotNull String sql, @Nullable String targetTable,
                                               @Nullable String snapshotSelectSql) {
        return register(sql, targetTable, snapshotSelectSql, List.of(), NO_EXECUTION_ID);
    }

    /** {@link #register(String,String,String,List,long)} 的默认 setColumns 形态。 */
    @NotNull
    public synchronized SnapshotEntry register(@NotNull String sql, @Nullable String targetTable,
                                               @Nullable String snapshotSelectSql,
                                               @NotNull List<String> setColumns) {
        return register(sql, targetTable, snapshotSelectSql, setColumns, NO_EXECUTION_ID);
    }

    @NotNull
    public synchronized SnapshotEntry register(@NotNull String sql, @Nullable String targetTable,
                                               @Nullable String snapshotSelectSql,
                                               @NotNull List<String> setColumns,
                                               long parentExecutionId) {
        String fingerprint = fingerprintOf(sql);
        Long previous = sqlIndex.get(fingerprint);
        SnapshotEntry entry = new SnapshotEntry(
                nextExecutionId++,
                parentExecutionId,
                sql,
                targetTable,
                snapshotSelectSql,
                setColumns);
        if (previous != null) {
            entries.remove(previous);
        }
        entries.put(entry.executionId(), entry);
        sqlIndex.put(fingerprint, entry.executionId());
        LOG.info(
                "[TRollbackSql] snapshot registered: executionId=" + entry.executionId()
                        + ", parent=" + parentExecutionId + ", "
                        + "table=" + entry.targetTable() + ", sqlLen=" + sql.length()
                        + ", setColumns=" + entry.setColumns());
        return entry;
    }

    /** 按预览/编辑器 SQL 文本查找最近登记的条目（规范化指纹匹配；未命中 null）。 */
    @Nullable
    public synchronized SnapshotEntry findBySql(@NotNull String sql) {
        Long id = sqlIndex.get(fingerprintOf(sql));
        if (id == null) return null;
        return entries.get(id);
    }

    /**
     * F6 留痕：回滚执行完成——原执行条目置「已执行」终态（回滚弹窗横幅与审计链依据）。
     * 返回 false = 条目已不在（LRU 淘汰/未登记），调用方忽略即可。
     */
    public synchronized boolean markRollbackExecuted(@NotNull SnapshotEntry entry) {
        SnapshotEntry current = entries.get(entry.executionId());
        if (current == null) return false;
        entries.put(current.executionId(), new SnapshotEntry(
                current.executionId(),
                current.parentExecutionId(),
                current.createdAt(),
                current.sql(),
                current.targetTable(),
                current.snapshotSelectSql(),
                current.setColumns(),
                Status.ROLLBACK_EXECUTED));
        LOG.info(
                "[TRollbackSql] rollback executed: targetExecutionId=" + entry.executionId()
                        + ", table=" + entry.targetTable());
        return true;
    }

    /** 当前条目数（测试断言用）。 */
    public synchronized int size() {
        return entries.size();
    }

    /** 条目只读快照（测试断言用）。 */
    @NotNull
    public synchronized List<SnapshotEntry> entriesForTest() {
        return new ArrayList<>(entries.values());
    }

    /** 清空（测试隔离用）。 */
    public synchronized void clearForTest() {
        entries.clear();
        sqlIndex.clear();
        nextExecutionId = 1;
    }

    @NotNull
    private String fingerprintOf(@NotNull String sql) {
        return LogSqlModelsKt.sqlFingerprint(LogSqlModelsKt.normalizeSqlWhitespace(sql));
    }
}
