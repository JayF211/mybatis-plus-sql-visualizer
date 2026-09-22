// T-SyncK2JMaster309 自 master sql/rollback/RollbackSnapshotStore.kt 移植
package cn.song.mybatisplus.visualizer.sql.rollback;

import cn.song.mybatisplus.visualizer.log.LogSqlModelsKt;
import cn.song.mybatisplus.visualizer.sql.Dialect;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
     * @param dialect           登记时的方言（O-52 回滚侧 identity 包装用；<b>null = 未知 → 不包装</b>）
     * @param identityColumns   目标表的自增列名（O-52；<b>空 = 未知 → 不包装</b>）
     *
     * <p>O-52（D1=A 定案）：回滚链的行值来自粘贴 TSV、{@code backfillInsert} 生成 INSERT，显式插入自增列
     * 在 SQL Server 上必然报 544——identity 知识只有<b>登记时</b>持有，故随快照一起留存。
     * 两个字段均带默认值：旧条目（本会话改前登记的）与未传参的调用方<b>逐字节不变地</b>落到
     * 「未知」，包装不触发（与既有「未知语义不臆造」口径一致）；{@link RollbackSnapshotStore} 是内存态、
     * 不持久化（见类 KDoc），不存在跨会话反序列化旧格式的问题，默认值只服务「调用方未传」这一形态。</p>
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
            @NotNull Status status,
            /** 登记时的方言（O-52；null = 未知 → 不包装）。 */
            @Nullable Dialect dialect,
            /** 目标表的自增列名（O-52；空 = 未知 → 不包装）。 */
            @NotNull List<String> identityColumns
    ) {
        /** 常用形态：createdAt 取当前时刻、status 初始 REGISTERED、O-52 两字段落「未知」。 */
        public SnapshotEntry(long executionId, long parentExecutionId, @NotNull String sql,
                             @Nullable String targetTable, @Nullable String snapshotSelectSql,
                             @NotNull List<String> setColumns) {
            this(executionId, parentExecutionId, System.currentTimeMillis(), sql, targetTable,
                    snapshotSelectSql, setColumns, Status.REGISTERED, null, List.of());
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

    /** 条目 LRU（容量 {@link #MAX_ENTRIES} 条；访问序）。 */
    private final LinkedHashMap<Long, SnapshotEntry> entries = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, SnapshotEntry> eldest) {
            if (size() <= MAX_ENTRIES) return false;
            // T-PS5-10：LRU 裁 entries 时**同步裁指纹索引**——此前只裁 entries，sqlIndex 随长会话
            // distinct DML 数单调增长（100 条容量语义只对 entries 生效，索引无上界）。
            pruneSqlIndex(eldest.getKey());
            return true;
        }
    };

    /** SQL 指纹 → 执行序号索引（register 覆盖写；条目被 LRU 淘汰时同步摘除，见上）。 */
    private final Map<String, Long> sqlIndex = new HashMap<>();

    /** T-PS5-10：摘掉指向 {@code executionId} 的指纹索引（至多一条；LRU 淘汰与惰性清理共用）。 */
    private void pruneSqlIndex(long executionId) {
        Iterator<Map.Entry<String, Long>> it = sqlIndex.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() == executionId) it.remove();
        }
    }

    /** T-PS5-10 测试缝：指纹索引规模（断言随 LRU 有界；生产链路无调用点）。 */
    public int sqlIndexSizeForTest() {
        return sqlIndex.size();
    }

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
     * @param dialect           登记时的方言（O-52；null = 未知 → 回滚侧不包装）
     * @param identityColumns   目标表的自增列名（O-52；空 = 未知 → 回滚侧不包装）
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

    /** {@link #register(String,String,String,List,long,Dialect,List)} 的 O-52 两字段落「未知」形态。 */
    @NotNull
    public synchronized SnapshotEntry register(@NotNull String sql, @Nullable String targetTable,
                                               @Nullable String snapshotSelectSql,
                                               @NotNull List<String> setColumns,
                                               long parentExecutionId) {
        return register(sql, targetTable, snapshotSelectSql, setColumns, parentExecutionId, null, List.of());
    }

    @NotNull
    public synchronized SnapshotEntry register(@NotNull String sql, @Nullable String targetTable,
                                               @Nullable String snapshotSelectSql,
                                               @NotNull List<String> setColumns,
                                               long parentExecutionId,
                                               @Nullable Dialect dialect,
                                               @NotNull List<String> identityColumns) {
        String fingerprint = fingerprintOf(sql);
        Long previous = sqlIndex.get(fingerprint);
        SnapshotEntry entry = new SnapshotEntry(
                nextExecutionId++,
                parentExecutionId,
                System.currentTimeMillis(),
                sql,
                targetTable,
                snapshotSelectSql,
                setColumns,
                Status.REGISTERED,
                dialect,
                identityColumns);
        if (previous != null) {
            entries.remove(previous);
        }
        entries.put(entry.executionId(), entry);
        sqlIndex.put(fingerprint, entry.executionId());
        LOG.info(
                "[TRollbackSql] snapshot registered: executionId=" + entry.executionId()
                        + ", parent=" + parentExecutionId + ", "
                        + "table=" + entry.targetTable() + ", sqlLen=" + sql.length()
                        + ", setColumns=" + entry.setColumns() + ", "
                        + "dialect=" + (entry.dialect() == null ? null : entry.dialect().getName())
                        + ", identityColumns=" + entry.identityColumns());
        return entry;
    }

    /** 按预览/编辑器 SQL 文本查找最近登记的条目（规范化指纹匹配；未命中 null）。 */
    @Nullable
    public synchronized SnapshotEntry findBySql(@NotNull String sql) {
        String fingerprint = fingerprintOf(sql);
        Long id = sqlIndex.get(fingerprint);
        if (id == null) return null;
        SnapshotEntry entry = entries.get(id);
        // T-PS5-10：索引指向的条目已不在（LRU 淘汰/终态清理）→ 顺手摘掉不可达指纹（懒清理双保险）
        if (entry == null) sqlIndex.remove(fingerprint);
        return entry;
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
                Status.ROLLBACK_EXECUTED,
                // O-52：copy(status=…) 语义——dialect/identityColumns 随条目保留
                current.dialect(),
                current.identityColumns()));
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
