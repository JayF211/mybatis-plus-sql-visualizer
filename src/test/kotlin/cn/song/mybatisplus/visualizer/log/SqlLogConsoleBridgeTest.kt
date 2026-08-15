package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行控制台反射桥环境探测测试（T-M31-03）。
 *
 * 环境依赖说明：IPGP 测试 flat classpath 含 execution 模块（新包名
 * `com.intellij.execution.ui.RunContentManager`，2024.2+ 平台包移动实证）→ 探测为 true；
 * 若未来 SDK 发行版裁剪变化，此断言按新环境调整（桥本身行为不变——attach 失败时优雅降级）。
 */
class SqlLogConsoleBridgeTest {

    @Test
    fun `环境探测返回布尔且不抛异常`() {
        assertTrue(SqlLogConsoleBridge.isConsoleApiAvailable())
    }
}
