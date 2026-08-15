package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * SqlLogManager 设置生效性行为测试（T-xx 设置生效性审计）：
 *
 *  - capturing 状态机（D3）：桥 attach 失败**不得**进入「已捕捉但无监听」的假状态
 *    （修复前 capturing 先置 true、attach 失败静默无监听且无感知无重试）；
 *  - 自动打开决策与面板解耦（D2）：窗口从未打开（未 addUiListener）时首条捕获也触发自动打开
 *    （修复前自动打开监听仅在面板创建时接线——启动自动监听场景永不触发，logAutoOpenOnCapture 形同虚设）；
 *  - 启动自动监听传播（D4）：设置页开启后对已打开项目立即生效
 *    （postStartupActivity 仅项目打开时触发——不重开项目也要本会话能捕捉）。
 */
class SqlLogManagerBehaviorTest : BasePlatformTestCase() {

    /** 受控桥（生产桥 open 化——内部测试缝）：attach 结果注入，验证 capturing 状态机。 */
    private class FakeBridge(private val attachResult: Boolean) : SqlLogConsoleBridge(SqlLogEntryBuffer()) {
        override fun attach(project: Project): Boolean = attachResult
    }

    /** 装配注入受控桥的 manager（生产构造 + 测试缝替换桥——不触碰轻服务构造函数解析）。 */
    private fun managerWith(bridge: SqlLogConsoleBridge): SqlLogManager =
        SqlLogManager(project).apply { installBridgeForTest(bridge) }

    fun testStartCaptureKeepsCapturingOffWhenAttachFails() {
        val manager = managerWith(FakeBridge(attachResult = false))
        manager.startCapture()
        assertFalse(
            "attach 失败不得进入「已捕捉但无监听」的假状态（修复前 capturing 恒被置 true，静默失败）",
            manager.capturing,
        )
    }

    fun testStartCaptureSetsCapturingWhenAttachSucceeds() {
        val manager = managerWith(FakeBridge(attachResult = true))
        manager.startCapture()
        assertTrue("attach 成功才进入捕捉态", manager.capturing)
        manager.stopCapture()
        assertFalse("停止后恢复未捕捉态", manager.capturing)
    }

    fun testAutoOpenDecisionFiresWithoutUiListener() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.logAutoOpenOnCapture
        try {
            settings.state.logAutoOpenOnCapture = true
            // 不调用 addUiListener——模拟工具窗口从未打开（启动自动监听旗舰场景）
            val manager = SqlLogManager(project)
            assertFalse(manager.autoOpenFiredForTest())
            manager.entries.addEntry(
                LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList(), timestamp = 0L),
            )
            assertTrue(
                "窗口未打开（无 UI 订阅）时首条捕获也应触发自动打开决策（修复前监听仅在面板创建时接线）",
                manager.autoOpenFiredForTest(),
            )
        } finally {
            settings.state.logAutoOpenOnCapture = old
        }
    }

    fun testAutoOpenDisabledStaysSilent() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.logAutoOpenOnCapture
        try {
            settings.state.logAutoOpenOnCapture = false
            val manager = SqlLogManager(project)
            manager.entries.addEntry(
                LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList(), timestamp = 0L),
            )
            assertFalse("开关关闭时自动打开决策不触发", manager.autoOpenFiredForTest())
        } finally {
            settings.state.logAutoOpenOnCapture = old
        }
    }

    fun testStartAutoCapturePropagatesToOpenProjects() {
        val manager = managerWith(FakeBridge(attachResult = true))
        SqlLogManager.startAutoCaptureForOpenProjects(provider = { manager })
        assertTrue("传播应调用已打开项目的 startCapture（幂等）", manager.capturing)
        manager.stopCapture()
    }
}
