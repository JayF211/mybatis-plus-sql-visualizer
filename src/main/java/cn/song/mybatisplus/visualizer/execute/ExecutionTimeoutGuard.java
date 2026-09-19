// T-SyncK2JMaster309 自 execute/ExecutionTimeoutGuard.kt 移植
package cn.song.mybatisplus.visualizer.execute;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;

/**
 * 执行链超时护栏纯函数（T-ExecTimeoutGuard，O-25，DBX 1ed06f2 线性公式吸收）。
 *
 * <p>两个决策照抄 DBX：</p>
 * <ul>
 *  <li><b>超时预算随语句数线性增长</b>——单语句=基础值；多语句=基础值×语句数，但不设 ×2 基准
 *      放大、不设武断 60s 下限；封顶 ×{@link #MAX_STATEMENT_FACTOR} 防滥用（10 条 30s 慢语句脚本
 *      不该给 300s 还嫌少，同时 100 条语句脚本也不该拿到 3000s）。</li>
 *  <li><b>先收尾再报错</b>——超时触发路径：优先 Statement.cancel() → 回收资源 → 才反馈错误，
 *      不留半开连接（接线面见 {@code cn.song.mybatisplus.visualizer.mcp.McpDbExecutor}）。</li>
 * </ul>
 *
 * <p>接入点登记（v1，诚实降级）：</p>
 * <ul>
 *  <li>MCP 直连链（RemoteStatement）：setQueryTimeout(每语句基础值) + cancel 收尾，已生效；</li>
 *  <li>共享控制台链（console.executeQueries）：DBN 控制台托管语句生命周期，插件侧无语句级
 *      句柄（DatabaseSettings.ExecOption 亦无超时面），<b>v1 不覆盖</b>——多语句预算函数作为
 *      该链接入时的规格先行交付（消费方接线时按 timeoutBudgetSeconds 取看门狗上限）。</li>
 * </ul>
 *
 * <p>JDBC setQueryTimeout 是驱动级建议值（MySQL/PG 成熟，部分信创驱动忽略）——超时检测按
 * 异常形态多路判定（SQLTimeoutException / SQLState 57014 / 消息签名），证明不了的按
 * 普通错误呈现，绝不虚标「已超时取消」。</p>
 */
public final class ExecutionTimeoutGuard {

    public static final ExecutionTimeoutGuard INSTANCE = new ExecutionTimeoutGuard();
    private ExecutionTimeoutGuard() {}

    /** 默认执行超时（秒）；0=不限（设置项「执行超时」）。 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** 多语句预算封顶倍数（基础值 × 8）。 */
    public static final int MAX_STATEMENT_FACTOR = 8;

    /**
     * 脚本整体超时预算（秒，看门狗上限）。
     *
     * @param baseSecs       基础值（每语句超时，秒；&lt;=0 返回 0=不限）
     * @param statementCount 语句数（SqlStatementSplitter 拆句计数；&lt;=1 按单语句）
     * @return 预算秒数：单语句=基础值；多语句=min(基础值×语句数, 基础值×封顶)；0=不限
     */
    public static int timeoutBudgetSeconds(int baseSecs, int statementCount) {
        if (baseSecs <= 0) return 0;
        if (statementCount <= 1) return baseSecs;
        return Math.min(baseSecs * statementCount, baseSecs * MAX_STATEMENT_FACTOR);
    }

    /**
     * 每语句超时（秒）——逐语句 setQueryTimeout 用（与脚本预算分离：单条慢不该拖垮整脚本
     * 判定，整脚本慢不该被单条快掩盖，DBX 同款两级形态）。
     */
    public static int perStatementTimeoutSeconds(int baseSecs) {
        return Math.max(baseSecs, 0);
    }

    /**
     * 异常是否为超时形态（反射链包裹逐层解包：InvocationTargetException → cause 链）。
     *
     * <p>判定三路（任一命中）：</p>
     * <ol>
     *  <li>{@link SQLTimeoutException}（JDBC 4.1+ 标准形态，MySQL Connector/J 使用）；</li>
     *  <li>SQLState {@code 57014}（PG query_canceled / Oracle 01013 等驱动常用）；</li>
     *  <li>消息签名（小写包含）：statement timeout / query timeout / cancelled due to
     *      timeout / canceled due to timeout / timed out（兜底信创驱动自造文案）。</li>
     * </ol>
     *
     * @return true=按超时收尾路径处理（cancel→报「执行超时」）；false=普通错误路径
     */
    public static boolean isTimeoutLike(@org.jetbrains.annotations.Nullable Throwable error) {
        int depth = 0;
        Throwable e = error;
        while (e != null && depth < 8) {
            if (e instanceof SQLTimeoutException) return true;
            if (e instanceof SQLException && "57014".equals(((SQLException) e).getSQLState())) return true;
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : null;
            if (msg != null) {
                if (msg.contains("statement timeout") || msg.contains("query timeout")) return true;
                if (msg.contains("cancelled due to timeout") || msg.contains("canceled due to timeout")) return true;
                if (msg.contains("timed out")) return true;
            }
            // 反射链：InvocationTargetException.getCause 为目标异常；普通链走 cause
            //（Kotlin `as? ITE ?.targetException ?: e.cause`——ITE 且 targetException 为 null 时也退回 e.cause）
            if (e instanceof java.lang.reflect.InvocationTargetException) {
                Throwable target = ((java.lang.reflect.InvocationTargetException) e).getTargetException();
                e = target != null ? target : e.getCause();
            } else {
                e = e.getCause();
            }
            depth++;
        }
        return false;
    }
}
