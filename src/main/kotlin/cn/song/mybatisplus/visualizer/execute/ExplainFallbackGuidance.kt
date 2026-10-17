package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat

/**
 * T-UI030：结构化 EXPLAIN 回退/关闭场景的**可操作引导**纯逻辑（无 IDE 类型，EDT 直调安全）。
 *
 * 起因（2026-10-18 真机）：无已绑数据源时只弹一条「已回退原生控制台」的告知通知，用户拿不到
 * 下一步。改造后：
 *  - 无已绑数据源 → 直接复用既有数据源选择/准备链（只绑定、不执行），绑定成功后自动重试；
 *  - 结构化开关关闭 → 通知里挂「启用结构化并重试」动作。
 *
 * 本对象只承载判定，UI 接线在 `SqlPreviewPanelExecution`（接线守卫
 * `ExplainPanelWiringGuardTest` 钉住），判定与接线分开回归、互不掩盖。
 */
object ExplainFallbackGuidance {

    /** 等待数据源绑定期间每个轮询拍的处置（抽成纯函数，取消/超时路径可脱离真机断言）。 */
    enum class DataSourceBindTick {
        /** 绑定尚未落地且准备链仍在飞——继续等，不另建第二条链、不重复弹选择器。 */
        WAIT,

        /** 已拿到已绑数据源——提交结构化 EXPLAIN 重试。 */
        RETRY,

        /** 准备链已终结仍未绑定（用户取消/选择失败）或等待超时——回退说明，不再弹第二次。 */
        GIVE_UP,
    }

    /**
     * 轮询一拍判定。
     *
     * 判定序：① 已绑定 → 重试（绑定优先级最高——准备链收尾动作不影响已可用的数据源）；
     * ② 超时 → 放弃；③ 准备链已不在飞 → 放弃（这就是「用户取消选择」的收口：取消会把单飞位
     * 复位，下一拍即判定放弃，绝不再次弹选择器）；④ 其余 → 继续等待。
     */
    fun dataSourceBindTick(bound: Boolean, prepareInFlight: Boolean, timeoutReached: Boolean): DataSourceBindTick =
        when {
            bound -> DataSourceBindTick.RETRY
            timeoutReached -> DataSourceBindTick.GIVE_UP
            !prepareInFlight -> DataSourceBindTick.GIVE_UP
            else -> DataSourceBindTick.WAIT
        }

    /**
     * 入口是否直接弹数据源选择器：已有准备链在飞时只等待，绝不再起一条链
     * （`PrepareSingleFlightGate` 对预览准备的并发是 PASS_THROUGH，会重建第二条会话/控制台）。
     */
    fun shouldPromptForDataSource(prepareInFlight: Boolean): Boolean = !prepareInFlight

    /**
     * 开关关闭时是否值得提示「启用结构化并重试」：只有该方言**确有结构化格式**且 Database
     * 插件在场时才提示。TEXT/XML/未知格式即便开启也不走结构化（不硬解析红线），提示只会
     * 变成噪音；Community 没有 Database 插件，开启也绑不上数据源。
     */
    fun shouldOfferStructuredEnable(
        enabled: Boolean,
        format: ExplainOutputFormat?,
        databaseAvailable: Boolean,
    ): Boolean = !enabled && databaseAvailable &&
        ExplainPanelChannel.supportsStructuredFormat(format)
}
