package cn.song.mybatisplus.sqlvis.agent.hook;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 语句归属登记表（T-StatementWeave S3）：prepare 面（连接实现类的 prepareStatement/prepareCall
 * 织入出口）把「PS 实例 → SQL + URL」写入本表；语句面 execute 钩子对**无参 PS 形态**
 * （PreparedStatement.execute() 无 SQL 参数）从本表取 SQL。
 *
 * <p>内存边界（计划 §四.1 定案）：键为**弱引用**（WeakHashMap）——PS 被宿主弃用后随 GC 自然清账，
 * 连接池「close=归还」复用同一实例期间登记保持有效（不织 close，归还≠废弃）；不设规模上限，
 * 泄漏面=未回收的弱键本身，与宿主 PS 生命周期同寿。
 *
 * <p>盲区（如实登记，计划 §三 S3）：attach（agentmain retransform）**之前**已 prepare 的 PS
 * 从未经过织入的 prepare 出口，本表无其 SQL——一期处置=跳过不上报 + 计数（[unresolvedCount]，
 * 宿主侧可观测），不产伪事件；字段反射兜底列二期（计划 §四.2/3 先证伪）。
 */
public final class StatementRegistry {

    /** 单条登记：SQL 文本 + prepare 时的连接 URL（归属链首选源）。 */
    public static final class Ctx {
        public final String sql;
        public final String url;
        /**
         * 批处理累积（statement 面 addBatch(String) 织入出口追加）。
         * 生命周期对齐驱动批缓冲的 JDBC 语义（F74）：executeBatch 消费即复位（onExecuteExit 读后
         * setLength(0)）、clearBatch() 织入出口清账（clearBatchOf）——因此规模天然有界＝宿主单个
         * 批周期内的 addBatch 总量，与驱动自身批缓冲同寿，不另设截断上限（截断会静默篡改事件内容）。
         */
        public final StringBuilder batch = new StringBuilder();

        Ctx(String sql, String url) {
            this.sql = sql;
            this.url = url;
        }
    }

    private static final Map<Object, Ctx> REGISTRY =
            Collections.synchronizedMap(new WeakHashMap<Object, Ctx>());

    /** attach 前 prepare、映射无登记的 PS 执行次数（盲区可观测口径）。 */
    private static final AtomicLong UNRESOLVED = new AtomicLong();

    private StatementRegistry() {
    }

    /** prepare 面登记（幂等：同实例重 prepare 覆盖，值一致）。 */
    public static void register(Object statement, String sql, String url) {
        if (statement == null || sql == null || sql.isEmpty()) {
            return;
        }
        REGISTRY.put(statement, new Ctx(sql, url));
    }

    /** 取登记（无则 null）。 */
    public static Ctx get(Object statement) {
        if (statement == null) {
            return null;
        }
        return REGISTRY.get(statement);
    }

    /** 取登记，无则登记盲区计数后返回 null（execute 出口调用方据此跳过不上报）。 */
    public static Ctx getOrCountUnresolved(Object statement) {
        final Ctx ctx = get(statement);
        if (ctx == null) {
            UNRESOLVED.incrementAndGet();
        }
        return ctx;
    }

    /** statement 面 addBatch 目标 ctx（无则惰性建——URL 未知，execute 出口再走归属链）。 */
    public static Ctx batchCtxOf(Object statement) {
        synchronized (REGISTRY) {
            Ctx ctx = REGISTRY.get(statement);
            if (ctx == null) {
                ctx = new Ctx(null, null);
                REGISTRY.put(statement, ctx);
            }
            return ctx;
        }
    }

    /** statement 面 clearBatch 出口：清批串（有登记才清——清账不建账，无登记即无事可清）。 */
    public static void clearBatchOf(Object statement) {
        if (statement == null) {
            return;
        }
        synchronized (REGISTRY) {
            final Ctx ctx = REGISTRY.get(statement);
            if (ctx != null) {
                ctx.batch.setLength(0);
            }
        }
    }

    /** 盲区计数（排障/测试观测）。 */
    public static long unresolvedCount() {
        return UNRESOLVED.get();
    }

    /** 仅测试用：清表复位（夹具间隔离；与 AgentState.resetPortAbandonedForTest 同款测试缝）。 */
    public static void clearForTest() {
        synchronized (REGISTRY) {
            REGISTRY.clear();
        }
        UNRESOLVED.set(0L);
    }
}
