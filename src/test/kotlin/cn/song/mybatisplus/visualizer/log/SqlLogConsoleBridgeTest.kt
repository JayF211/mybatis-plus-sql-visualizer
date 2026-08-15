package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 运行控制台反射桥环境降级测试（T-M31-03）。
 *
 * 注意：本测试断言「当前构建环境（IPGP 发行版）无 execution API」——
 * 若未来构建 classpath 提供 com.intellij.execution，此断言需按新环境调整（桥本身行为不变）。
 */
class SqlLogConsoleBridgeTest {

    @Test
    fun `构建环境无 execution API 时优雅降级`() {
        // IPGP 发行版裁剪 execution 模块（编译 classpath 无 com.intellij.execution.*），
        // 桥应探测到不可用并返回 false，不抛异常（运行期真实 IDE 提供后为 true）。
        assertFalse(SqlLogConsoleBridge.isConsoleApiAvailable())
    }
}
