package cn.song.mybatisplus.visualizer.execute

import java.sql.SQLException
import java.sql.SQLTimeoutException

/**
 * 执行链超时护栏纯函数（T-ExecTimeoutGuard，O-25，DBX 1ed06f2 线性公式吸收）。
 *
 * 两个决策照抄 DBX：
 *  - **超时预算随语句数线性增长**——单语句=基础值；多语句=基础值×语句数，但不设 ×2 基准
 *    放大、不设武断 60s 下限；封顶 ×[MAX_STATEMENT_FACTOR] 防滥用（10 条 30s 慢语句脚本
 *    不该给 300s 还嫌少，同时 100 条语句脚本也不该拿到 3000s）。
 *  - **先收尾再报错**——超时触发路径：优先 Statement.cancel() → 回收资源 → 才反馈错误，
 *    不留半开连接（接线面见 [cn.song.mybatisplus.visualizer.mcp.McpDbExecutor]）。
 *
 * 接入点登记（v1，诚实降级）：
 *  - MCP 直连链（RemoteStatement）：setQueryTimeout(每语句基础值) + cancel 收尾，已生效；
 *  - 共享控制台链（console.executeQueries）：DBN 控制台托管语句生命周期，插件侧无语句级
 *    句柄（DatabaseSettings.ExecOption 亦无超时面），**v1 不覆盖**——多语句预算函数作为
 *    该链接入时的规格先行交付（消费方接线时按 timeoutBudgetSeconds 取看门狗上限）。
 *
 * JDBC setQueryTimeout 是驱动级建议值（MySQL/PG 成熟，部分信创驱动忽略）——超时检测按
 * 异常形态多路判定（SQLTimeoutException / SQLState 57014 / 消息签名），证明不了的按
 * 普通错误呈现，绝不虚标「已超时取消」。
 */
object ExecutionTimeoutGuard {

    /** 默认执行超时（秒）；0=不限（设置项「执行超时」）。 */
    const val DEFAULT_TIMEOUT_SECONDS = 30

    /** 多语句预算封顶倍数（基础值 × 8）。 */
    const val MAX_STATEMENT_FACTOR = 8

    /**
     * 脚本整体超时预算（秒，看门狗上限）。
     *
     * @param baseSecs 基础值（每语句超时，秒；<=0 返回 0=不限）
     * @param statementCount 语句数（SqlStatementSplitter 拆句计数；<=1 按单语句）
     * @return 预算秒数：单语句=基础值；多语句=min(基础值×语句数, 基础值×封顶)；0=不限
     */
    fun timeoutBudgetSeconds(baseSecs: Int, statementCount: Int): Int {
        if (baseSecs <= 0) return 0
        if (statementCount <= 1) return baseSecs
        return minOf(baseSecs * statementCount, baseSecs * MAX_STATEMENT_FACTOR)
    }

    /**
     * 每语句超时（秒）——逐语句 setQueryTimeout 用（与脚本预算分离：单条慢不该拖垮整脚本
     * 判定，整脚本慢不该被单条快掩盖，DBX 同款两级形态）。
     */
    fun perStatementTimeoutSeconds(baseSecs: Int): Int = baseSecs.coerceAtLeast(0)

    /**
     * 异常是否为超时形态（反射链包裹逐层解包：InvocationTargetException → cause 链）。
     *
     * 判定三路（任一命中）：
     *  1. [SQLTimeoutException]（JDBC 4.1+ 标准形态，MySQL Connector/J 使用）；
     *  2. SQLState `57014`（PG query_canceled / Oracle 01013 等驱动常用）；
     *  3. 消息签名（小写包含）：statement timeout / query timeout / cancelled due to
     *     timeout / canceled due to timeout / timed out（兜底信创驱动自造文案）。
     *
     * @return true=按超时收尾路径处理（cancel→报「执行超时」）；false=普通错误路径
     */
    fun isTimeoutLike(error: Throwable?): Boolean {
        var depth = 0
        var e = error
        while (e != null && depth < 8) {
            if (e is SQLTimeoutException) return true
            if (e is SQLException && e.sqlState == "57014") return true
            val msg = e.message?.lowercase()
            if (msg != null) {
                if ("statement timeout" in msg || "query timeout" in msg) return true
                if ("cancelled due to timeout" in msg || "canceled due to timeout" in msg) return true
                if ("timed out" in msg) return true
            }
            // 反射链：InvocationTargetException.getCause 为目标异常；普通链走 cause
            e = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e.cause
            depth++
        }
        return false
    }
}
