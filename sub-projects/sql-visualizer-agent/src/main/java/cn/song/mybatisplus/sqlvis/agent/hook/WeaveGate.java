package cn.song.mybatisplus.sqlvis.agent.hook;

/**
 * 双通道互斥门（T-StatementWeave S7 去重红线）：代理层（[StatementHandler]）在 invoke 原始语句
 * 方法期间置位本线程标记，语句面织入钩子（SqlHookCallbacks.onStatementEnter）读到标记即短路——
 *
 * <p>为什么必须有：连接被包装后，代理层 {@code method.invoke(raw, args)} 调到的正是**已被织入**的
 * 原始 execute 方法体——不设门，同一条 SQL 会双通道各产出一条事件（代理层一条 + 织入一条）。
 * JDBC execute 同步同线程（入口/出口成对），ThreadLocal 语义精确。
 *
 * <p>开销：无代理路径（attach 兜底的老连接）零读写（只有织入钩子读一次 volatile ThreadLocal 槽），
 * 代理路径每语句两次置/清位——与现代理层每语句开销同级。
 */
public final class WeaveGate {

    private static final ThreadLocal<Integer> PROXY_ACTIVE = new ThreadLocal<Integer>();

    private WeaveGate() {
    }

    /** 代理层进入原始方法调用前置位（必须 try/finally 配对 [exitProxy]）。 */
    public static void enterProxy() {
        Integer depth = PROXY_ACTIVE.get();
        PROXY_ACTIVE.set(depth == null ? 1 : depth + 1);
    }

    /** 代理层离开原始方法调用后复位。 */
    public static void exitProxy() {
        Integer depth = PROXY_ACTIVE.get();
        if (depth == null || depth <= 1) PROXY_ACTIVE.remove();
        else PROXY_ACTIVE.set(depth - 1);
    }

    /** 织入钩子读：true = 本线程正处于代理层原始调用内，织入通道须短路让代理层独占采集。 */
    public static boolean isProxyActive() {
        final Integer v = PROXY_ACTIVE.get();
        return v != null && v > 0;
    }
}
