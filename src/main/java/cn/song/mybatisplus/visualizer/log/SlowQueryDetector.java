package cn.song.mybatisplus.visualizer.log;

/**
 * 慢查询检测器（O-1 Phase C，纯逻辑，可独立单测）：基于阈值判定慢查询 + 计数统计。
 *
 * 功能：
 * - 静态判定 {@link #isSlow(Long, long)}：耗时 >= 阈值即慢查询
 * - 实例计数器 {@link SlowQueryCounter}：累计慢查询数量，支持重置
 */
public final class SlowQueryDetector {

    public static final SlowQueryDetector INSTANCE = new SlowQueryDetector();

    private SlowQueryDetector() {
    }

    /**
     * 判定是否为慢查询（纯函数）。
     *
     * @param executionTimeMs 耗时毫秒（null 表示无耗时信息，不判定为慢）
     * @param thresholdMs     慢查询阈值毫秒
     * @return true = 慢查询
     */
    public static boolean isSlow(Long executionTimeMs, long thresholdMs) {
        if (executionTimeMs == null) {
            return false;
        }
        return executionTimeMs >= thresholdMs;
    }

    /**
     * 耗时等级（供 UI 着色用）。
     */
    public enum DurationLevel {
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
    public static DurationLevel level(Long executionTimeMs) {
        if (executionTimeMs == null) {
            return DurationLevel.NONE;
        }
        if (executionTimeMs < 100L) {
            return DurationLevel.FAST;
        }
        if (executionTimeMs <= 1000L) {
            return DurationLevel.MODERATE;
        }
        return DurationLevel.SLOW;
    }
}
