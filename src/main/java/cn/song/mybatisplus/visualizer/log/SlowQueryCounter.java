package cn.song.mybatisplus.visualizer.log;

/**
 * 慢查询计数器（有状态，工具窗口级别共享）。
 *
 * <p>T-RA-04 判定：本类<b>不是</b>只写不读残留——slowCount 的唯一读取点在
 * {@code log/SqlLogPanel.updateSlowQueryLabel()}（工具栏「慢查询: N」标签，bundle 键
 * {@code log.toolbar.slowQueryCount}），产出点同文件 {@code refreshList} 路径（阈值同步 →
 * reset → 逐条 report → 更新标签）。审计脚本当时按「声明文件内无读」判定，
 * 跨文件读取未纳入，属误报；后续同类扫描须全仓（含测试）核读点再下结论。
 *
 * @param thresholdMs 慢查询阈值
 */
public class SlowQueryCounter {

    /** 慢查询阈值（可变，设置页修改后同步）。 */
    private long thresholdMs;

    /**
     * 当前慢查询计数（读点见类 KDoc：SqlLogPanel 工具栏标签；测试亦直接断言）。
     */
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
