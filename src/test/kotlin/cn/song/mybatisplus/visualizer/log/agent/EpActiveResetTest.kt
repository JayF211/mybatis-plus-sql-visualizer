package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.SqlCaptureGate
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-PS5-07：`SqlCaptureGate.epActive` 复位点守护测试（平台用例——设置变更经 app 总线广播到
 * 项目级订阅者）。
 *
 * 缺陷背景（第八轮报告发现 8）：`epActive` 是「EP 在本会话实证产出过条目」的**会话级粘滞**标志，
 * 置位点在 SqlConsoleBridgeFilterProvider，解耦前**无任何复位点**——关掉增强捕获再新建控制台时，
 * 桥侧仍被粘滞标志短路 = 两条通道同时零产出（静默零捕获的复发形态）。
 * 现复位点挂 [AgentManager] 的 SETTINGS_CHANGE 监听（关闭下降沿，与 T-PS4-01 上升沿重置拒绝冷却对称）。
 */
class EpActiveResetTest : BasePlatformTestCase() {

    override fun tearDown() {
        SqlCaptureGate.epActive = false
        SqlCaptureGate.paused.set(false)
        super.tearDown()
    }

    private fun publishSettingsChange() {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC)
            .settingsChanged()
    }

    fun test关闭增强捕获复位EP短路标志() {
        val state = PluginSettings.getInstance().state
        val enabledSnapshot = state.enhancedCaptureEnabled
        val manager = AgentManager(project)
        try {
            state.enhancedCaptureEnabled = true
            publishSettingsChange() // 建立「开关为开」基线（不动 epActive）
            SqlCaptureGate.epActive = true

            state.enhancedCaptureEnabled = false
            publishSettingsChange()

            assertFalse("关闭增强捕获 → EP 短路标志复位（桥路恢复解析）", SqlCaptureGate.epActive)
        } finally {
            state.enhancedCaptureEnabled = enabledSnapshot
            Disposer.dispose(manager)
        }
    }

    fun test重新开启后短路标志保持复位态() {
        val state = PluginSettings.getInstance().state
        val enabledSnapshot = state.enhancedCaptureEnabled
        val manager = AgentManager(project)
        try {
            SqlCaptureGate.epActive = true
            state.enhancedCaptureEnabled = false
            publishSettingsChange()
            assertFalse(SqlCaptureGate.epActive)

            // 再开启：复位态保持（EP 需重新实证产出条目才置位——语义由置位点承担，此处只锁不复位回潮）
            state.enhancedCaptureEnabled = true
            publishSettingsChange()
            assertFalse("重新开启不预置 epActive（须 EP 实证产出）", SqlCaptureGate.epActive)
        } finally {
            state.enhancedCaptureEnabled = enabledSnapshot
            Disposer.dispose(manager)
        }
    }
}
