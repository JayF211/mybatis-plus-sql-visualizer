package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.EnhancedCaptureConsent
import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.SettingsChangeListener
import cn.song.mybatisplus.visualizer.log.agent.AgentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * O-66 T-GuideEnhancedFirst：引导弹框「开启增强捕获」动作的编排测试。
 *
 * 弹框本体 headless 不可测（家规同 [SourceCaptureGuideChoiceTest]），本测试锁定编排函数
 * [SourceCaptureGuide.enableEnhancedCapture] 的可观察契约：
 *  - 总开关关闭时：置 enabled=true 并发布 SETTINGS_CHANGE_TOPIC（AgentManager 上升沿清冷却 +
 *    CONFIG 链跟随的触发面，与设置页 Apply 同通道）；已开启时**不得**重复发布；
 *  - 确认流复用 `AgentManager.requestReauthorization`（consentProvider 注入三态）：
 *    同意 → confirmed 持久化 + 冷却清零 + 回调收到 ACCEPTED；拒绝 → 冷却落盘 + 回调收到 REJECTED；
 *    「关闭增强捕获并不再询问」→ enabled=false 持久化 + 回调收到 DISABLE_CAPTURE；
 *  - 回调是唯一结果通道：headless 不可见弹窗，编排不得在回调之外另藏持久化。
 *
 * 三态持久化语义本体由 AgentConsentFlowTest 锁定（挂载链原路径），此处只验引导侧接线不绕路。
 */
class SourceCaptureGuideEnableFlowTest : BasePlatformTestCase() {

    private lateinit var manager: AgentManager
    private lateinit var savedProvider: () -> EnhancedCaptureConsent
    private var settingsChangeCount = 0

    override fun setUp() {
        super.setUp()
        manager = AgentManager.getInstance(project)
        // 防御性还原默认时间源（同 AgentConsentFlowTest：实例跨测试类复用防闭包污染）
        manager.clockMs = { System.currentTimeMillis() }
        // 去重窗复位（同 AgentConsentFlowTest）：引导链「置位+发布+requestReauthorization」
        // 的上升沿会先消耗去重窗，不复位则引导链 onOutcome 被吞、断言收到 null
        manager.consentAskGuardMs = 0
        manager.lastConsentAskAtMs.set(0)
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = false
        state.enhancedCaptureConfirmed = false
        state.enhancedCaptureRejectedAtMs = 0
        manager.rejectCooldownMs = AgentManager.DEFAULT_REJECT_COOLDOWN_MS
        savedProvider = manager.consentProvider
        settingsChangeCount = 0
        // 探针：enableEnhancedCapture 置开关后的手动发布（设置页 Apply 同通道）
        ApplicationManager.getApplication().messageBus
            .connect(testRootDisposable)
            .subscribe(PluginSettings.SETTINGS_CHANGE_TOPIC, SettingsChangeListener { settingsChangeCount++ })
    }

    override fun tearDown() {
        manager.consentProvider = savedProvider
        manager.shutdownAll()
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = false
        state.enhancedCaptureConfirmed = false
        state.enhancedCaptureRejectedAtMs = 0
        super.tearDown()
    }

    private fun flushEdt() {
        UIUtil.dispatchAllInvocationEvents()
    }

    fun test总开关关闭时置位并发布设置变更_拒绝进冷却() {
        manager.consentProvider = { EnhancedCaptureConsent.REJECTED }
        var outcome: EnhancedCaptureConsent? = null
        SourceCaptureGuide.enableEnhancedCapture(project) { outcome = it }
        val state = PluginSettings.getInstance().state
        assertTrue("总开关应置位", state.enhancedCaptureEnabled)
        assertEquals("置开关应发布一次 SETTINGS_CHANGE", 1, settingsChangeCount)
        flushEdt()
        assertEquals("回调应收到 REJECTED", EnhancedCaptureConsent.REJECTED, outcome)
        assertTrue("拒绝应落冷却（引导侧不得绕开冷却语义）", state.enhancedCaptureRejectedAtMs > 0)
        assertFalse("拒绝不得置 confirmed", state.enhancedCaptureConfirmed)
    }

    fun test已开启未授权时不重复发布设置变更_同意清冷却并置confirmed() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        // 预置拒绝冷却中（用户曾在确认框点过取消）——显式开启意图＝重新授权语义，应清零重问
        state.enhancedCaptureRejectedAtMs = System.currentTimeMillis()
        manager.consentProvider = { EnhancedCaptureConsent.ACCEPTED }
        var outcome: EnhancedCaptureConsent? = null
        SourceCaptureGuide.enableEnhancedCapture(project) { outcome = it }
        assertEquals("已开启时不得重复发布 SETTINGS_CHANGE", 0, settingsChangeCount)
        flushEdt()
        assertEquals("回调应收到 ACCEPTED", EnhancedCaptureConsent.ACCEPTED, outcome)
        assertTrue("同意后 confirmed 应持久化", state.enhancedCaptureConfirmed)
        assertEquals("重新授权语义应清零拒绝冷却", 0L, state.enhancedCaptureRejectedAtMs)
    }

    fun test关闭增强捕获三态透传_总开关持久化false() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        manager.consentProvider = { EnhancedCaptureConsent.DISABLE_CAPTURE }
        var outcome: EnhancedCaptureConsent? = null
        SourceCaptureGuide.enableEnhancedCapture(project) { outcome = it }
        flushEdt()
        assertEquals("回调应收到 DISABLE_CAPTURE", EnhancedCaptureConsent.DISABLE_CAPTURE, outcome)
        assertFalse("「关闭增强捕获并不再询问」应持久化 enabled=false", state.enhancedCaptureEnabled)
    }

    fun test总开关关闭且确认同意_全链置位() {
        PluginSettings.getInstance().state.enhancedCaptureEnabled = false
        manager.consentProvider = { EnhancedCaptureConsent.ACCEPTED }
        var outcome: EnhancedCaptureConsent? = null
        SourceCaptureGuide.enableEnhancedCapture(project) { outcome = it }
        flushEdt()
        val state = PluginSettings.getInstance().state
        assertTrue("总开关应置位", state.enhancedCaptureEnabled)
        assertTrue("confirmed 应置位", state.enhancedCaptureConfirmed)
        assertEquals(EnhancedCaptureConsent.ACCEPTED, outcome)
        assertNull("无句柄确认流不得入 attach 队列（挂载由下一次应用进程启动承接）", manager.pendingPids.takeIf { it.isNotEmpty() })
    }
}
