package cn.song.mybatisplus.visualizer.log;

import org.junit.Assert.assertFalse;
import org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * LogStartupCapture 启动条件测试（Wave2-P4）：
 * 测试 shouldStartCapture() 纯函数的条件矩阵。
 *
 * 条件组合：
 *  - suppressed=true → 不启动
 *  - suppressed=false, autoStart=true, hasOpenProject=true → 启动
 *  - suppressed=false, autoStart=false, hasOpenProject=true → 不启动
 *  - suppressed=false, autoStart=false, hasOpenProject=false → 不启动
 *
 * 纯函数测试，不需要 PSI 环境。
 */
public class LogStartupCaptureTest {

    @Test
    public void suppressed_true_does_not_start() {
        // 用户已显式停止 → 无论其他条件如何，不启动
        assertFalse(
                "suppressed=true 时应不启动",
                LogStartupCapture.shouldStartCapture(true, true, true));
        assertFalse(
                "suppressed=true + autoStart=false 应不启动",
                LogStartupCapture.shouldStartCapture(true, false, true));
        assertFalse(
                "suppressed=true + hasOpenProject=false 应不启动",
                LogStartupCapture.shouldStartCapture(true, true, false));
    }

    @Test
    public void autoStart_true_not_suppressed_starts() {
        // 设置开启 + 未抑制 + 有项目 → 启动
        assertTrue(
                "autoStart=true, suppressed=false, hasOpenProject=true 应启动",
                LogStartupCapture.shouldStartCapture(false, true, true));
    }

    @Test
    public void autoStart_false_does_not_start_even_with_project() {
        // 设置未开启 → 不启动（无论是否有项目）
        assertFalse(
                "autoStart=false + hasOpenProject=true 应不启动",
                LogStartupCapture.shouldStartCapture(false, false, true));
    }

    @Test
    public void autoStart_false_no_project_does_not_start() {
        // 设置未开启 + 无项目 → 不启动
        assertFalse(
                "autoStart=false + hasOpenProject=false 应不启动",
                LogStartupCapture.shouldStartCapture(false, false, false));
    }

    @Test
    public void no_open_project_does_not_start() {
        // 有设置 + 未抑制 + 无打开项目 → 不启动
        assertFalse(
                "hasOpenProject=false 时应不启动",
                LogStartupCapture.shouldStartCapture(false, true, false));
    }
}
