package cn.song.mybatisplus.visualizer.log;

/**
 * 慢查询计数器（有状态，工具窗口级别共享）。
 *
 * @param thresholdMs 慢查询阈值
 */
public class SlowQueryCounter {

    /** 慢查询阈值（可变，设置页修改后同步）。 */
    private long thresholdMs;

    /** 当前慢查询计数。 */
    private int slowCount = 0;

    public SlowQueryCounter() {
        this(1000L);
    }

    public SlowQueryCounter(long thresholdMs) {
        this.thresholdMs = thresholdMs;
    }

    /** 当前慢查询计数。 */
    public int getSlowCount() {
        return slowCount;
    }

    /** 慢查询阈值（可变，设置页修改后同步）。 */
    public long getThresholdMs() {
        return thresholdMs;
    }

    public void setThresholdMs(long value) {
        this.thresholdMs = value;
        // 阈值变化时重置计数（避免新旧阈值混计）
        this.slowCount = 0;
    }

    /**
     * 报告一条查询的耗时，若超过阈值则计数 +1。
     *
     * @param executionTimeMs 耗时毫秒
     */
    public void report(Long executionTimeMs) {
        if (SlowQueryDetector.isSlow(executionTimeMs, thresholdMs)) {
            slowCount++;
        }
    }

    /** 重置计数器。 */
    public void reset() {
        slowCount = 0;
    }
}
