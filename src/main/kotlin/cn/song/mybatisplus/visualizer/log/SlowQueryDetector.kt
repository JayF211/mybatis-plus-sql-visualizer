package cn.song.mybatisplus.visualizer.log

/**
 * 慢查询检测器（O-1 Phase C，纯逻辑，可独立单测）：基于阈值判定慢查询 + 计数统计。
 *
 * 功能：
 * - 静态判定 [isSlow]：耗时 >= 阈值即慢查询
 * - 实例计数器 [SlowQueryCounter]：累计慢查询数量，支持重置
 */
object SlowQueryDetector {

    /**
     * 判定是否为慢查询（纯函数）。
     *
     * @param executionTimeMs 耗时毫秒（null 表示无耗时信息，不判定为慢）
     * @param thresholdMs 慢查询阈值毫秒
     * @return true = 慢查询
     */
    fun isSlow(executionTimeMs: Long?, thresholdMs: Long): Boolean {
        if (executionTimeMs == null) return false
        return executionTimeMs >= thresholdMs
    }

    /**
     * 耗时等级（供 UI 着色用）。
     */
    enum class DurationLevel {
        /** < 100ms — 快。 */
        FAST,
        /** 100ms ~ 1000ms — 中等。 */
        MODERATE,
        /** > 1000ms — 慢。 */
        SLOW,
        /** 无耗时信息。 */
        NONE,
    }

    /**
     * 根据耗时返回等级（供列表渲染着色）。
     *
     * @param executionTimeMs 耗时毫秒
     * @return 等级枚举
     */
    fun level(executionTimeMs: Long?): DurationLevel = when {
        executionTimeMs == null -> DurationLevel.NONE
        executionTimeMs < 100L -> DurationLevel.FAST
        executionTimeMs <= 1000L -> DurationLevel.MODERATE
        else -> DurationLevel.SLOW
    }
}

/**
 * 慢查询计数器（有状态，工具窗口级别共享）。
 *
 * T-RA-04 判定：本类**不是**只写不读残留——[slowCount] 的唯一读取点在
 * `log/SqlLogPanel.updateSlowQueryLabel()`（工具栏「慢查询: N」标签，bundle 键
 * `log.toolbar.slowQueryCount`），产出点同文件 `refreshList` 路径（阈值同步 →
 * reset → 逐条 report → 更新标签）。审计脚本当时按「声明文件内无读」判定，
 * 跨文件读取未纳入，属误报；后续同类扫描须全仓（含测试）核读点再下结论。
 *
 * @param thresholdMs 慢查询阈值
 */
class SlowQueryCounter(thresholdMs: Long = 1000L) {

    /**
     * 当前慢查询计数（读点见类 KDoc：SqlLogPanel 工具栏标签；测试亦直接断言）。
     */
    var slowCount: Int = 0
        private set

    /** 慢查询阈值（可变，设置页修改后同步）。 */
    var thresholdMs: Long = thresholdMs
        set(value) {
            field = value
            // 阈值变化时重置计数（避免新旧阈值混计）
            slowCount = 0
        }

    /**
     * 报告一条查询的耗时，若超过阈值则计数 +1。
     *
     * @param executionTimeMs 耗时毫秒
     */
    fun report(executionTimeMs: Long?) {
        if (SlowQueryDetector.isSlow(executionTimeMs, thresholdMs)) {
            slowCount++
        }
    }

    /** 重置计数器。 */
    fun reset() {
        slowCount = 0
    }
}
