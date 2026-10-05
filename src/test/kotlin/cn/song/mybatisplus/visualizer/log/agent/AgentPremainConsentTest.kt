package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.EnhancedCaptureConsent
import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * T-FirstUsePremainConsent S1/S2 守卫测试。
 *
 * 2026-10-05 沙盒复现（idea.log 10:15:17~10:16:05）：首次使用 confirmed 出厂 false → 首轮 Run
 * 注入跳过 enhanced-unconfirmed → 同意弹窗由 attach 链在进程启动后弹 → attach 兜底对池化连接
 * 结构性零捕获（agent 只织驱动 connect，retransform 不能回溯已存在连接）→ 重启项目才正常。
 *
 * 修复语义：
 * - S1 确认前置：[AgentManager.ensureConfirmedBeforePremain] 在 updateJavaParameters 窗口内
 *   同步确认，同意后首轮 Run 即产出 premain VM option（[PremainRunConfigurationExtension]
 *   接线由端到端用例钉住——摘掉该调用必红）；
 * - S2 诚实兜底：attach 兜底挂载成功发能力边界通知，每轮至多一次（onRunEnded 复位）。
 *
 * headless 沙盒口径：premainConsentGate 注入驱动「可弹/不可弹」两分支；生产默认 = EDT 且非 headless。
 */
class AgentPremainConsentTest : BasePlatformTestCase() {

    private lateinit var manager: AgentManager
    private var askedCount = 0

    override fun setUp() {
        super.setUp()
        manager = AgentManager.getInstance(project)
        manager.clockMs = { System.currentTimeMillis() }
        // 实例可能跨测试类复用（同 AgentConsentFlowTest 防御口径）：复位「每轮一次」通知标记
        manager.attachFallbackNoticedThisRun.set(false)
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = false
        state.enhancedCaptureRejectedAtMs = 0
        manager.rejectCooldownMs = AgentManager.DEFAULT_REJECT_COOLDOWN_MS
        askedCount = 0
    }

    override fun tearDown() {
        try {
            manager.shutdownAll()
            val state = PluginSettings.getInstance().state
            state.enhancedCaptureEnabled = false
            state.enhancedCaptureConfirmed = false
            state.enhancedCaptureRejectedAtMs = 0
            manager.clockMs = { System.currentTimeMillis() }
            manager.premainConsentGate = {
                val app = com.intellij.openapi.application.ApplicationManager.getApplication()
                app != null && !app.isHeadlessEnvironment && app.isDispatchThread
            }
            manager.attachFallbackNotifier = null
        } finally {
            super.tearDown()
        }
    }

    private fun providerReturning(outcome: EnhancedCaptureConsent): () -> EnhancedCaptureConsent {
        return { askedCount++; outcome }.also { manager.consentProvider = it }
    }

    // ---------- S1：ensureConfirmedBeforePremain 三态语义 ----------

    fun test未确认_可弹_同意_confirmed持久化_返回true() {
        manager.premainConsentGate = { true }
        providerReturning(EnhancedCaptureConsent.ACCEPTED)
        assertTrue(manager.ensureConfirmedBeforePremain())
        assertEquals("可弹形态应弹一次", 1, askedCount)
        assertTrue("同意后 confirmed 应持久化", PluginSettings.getInstance().state.enhancedCaptureConfirmed)
    }

    fun test未确认_可弹_拒绝_冷却持久化_返回false() {
        manager.premainConsentGate = { true }
        providerReturning(EnhancedCaptureConsent.REJECTED)
        assertFalse(manager.ensureConfirmedBeforePremain())
        assertEquals(1, askedCount)
        assertTrue(
            "拒绝应持久化拒绝时刻（进冷却）",
            PluginSettings.getInstance().state.enhancedCaptureRejectedAtMs > 0,
        )
        assertFalse("拒绝不得置 confirmed", PluginSettings.getInstance().state.enhancedCaptureConfirmed)
    }

    fun test未确认_可弹_关闭增强_总开关false_返回false() {
        manager.premainConsentGate = { true }
        providerReturning(EnhancedCaptureConsent.DISABLE_CAPTURE)
        assertFalse(manager.ensureConfirmedBeforePremain())
        assertFalse(
            "「关闭增强捕获」应持久化 enabled=false",
            PluginSettings.getInstance().state.enhancedCaptureEnabled,
        )
        assertFalse(PluginSettings.getInstance().state.enhancedCaptureConfirmed)
    }

    fun test不可弹_EDT守卫false_静默回落_不弹不落盘() {
        manager.premainConsentGate = { false }
        providerReturning(EnhancedCaptureConsent.ACCEPTED)
        assertFalse("EDT/headless 前提不成立应静默回落", manager.ensureConfirmedBeforePremain())
        assertEquals("静默回落不得弹窗", 0, askedCount)
        assertFalse("静默回落不得写 confirmed", PluginSettings.getInstance().state.enhancedCaptureConfirmed)
    }

    fun test冷却期内_不弹_返回false() {
        PluginSettings.getInstance().state.enhancedCaptureRejectedAtMs = System.currentTimeMillis()
        manager.premainConsentGate = { true }
        providerReturning(EnhancedCaptureConsent.ACCEPTED)
        assertFalse("冷却期内不得提前确认", manager.ensureConfirmedBeforePremain())
        assertEquals("冷却期内不得弹窗", 0, askedCount)
    }

    fun test已确认_直接true_不弹() {
        PluginSettings.getInstance().state.enhancedCaptureConfirmed = true
        manager.premainConsentGate = { true }
        providerReturning(EnhancedCaptureConsent.REJECTED)
        assertTrue(manager.ensureConfirmedBeforePremain())
        assertEquals("已确认态不得再弹", 0, askedCount)
    }

    fun test未开启_直接false_不弹() {
        PluginSettings.getInstance().state.enhancedCaptureEnabled = false
        manager.premainConsentGate = { true }
        providerReturning(EnhancedCaptureConsent.ACCEPTED)
        assertFalse(manager.ensureConfirmedBeforePremain())
        assertEquals(0, askedCount)
    }

    // ---------- S1 端到端守卫：未确认 + 同意 → 首轮 Run 产出 premain VM option ----------

    fun test首轮Run链路_未确认可弹同意_即产出premainVM选项() {
        manager.premainConsentGate = { true }
        providerReturning(EnhancedCaptureConsent.ACCEPTED)
        assertTrue(manager.ensureConfirmedBeforePremain())
        val option = manager.preparePremainVmOption()
        assertNotNull("前置同意后首轮 Run 必须产出 -javaagent VM 选项", option)
        assertTrue(option!!.startsWith("-javaagent:"))
        manager.shutdownAll() // 通道随用例收口，不留挂起端口给下个用例
    }

    /**
     * 扩展接线字节码守卫：[PremainRunConfigurationExtension.updateJavaParameters] 必须在
     * preparePremainVmOption 之前调用 [AgentManager.ensureConfirmedBeforePremain]。
     * 摘掉该调用 → 常量池不再含该方法引用 → 本用例必红。
     * 证据边界：证明接线在场，不证明执行时序（时序由 S1 方法自身单测 + runIde 沙盒走查承接）。
     */
    fun test扩展接线字节码守卫_updateJavaParameters必须走前置确认() {
        val classBytes = PremainRunConfigurationExtension::class.java
            .getResourceAsStream("PremainRunConfigurationExtension.class")!!.readBytes()
        val constantPool = String(classBytes, Charsets.ISO_8859_1)
        assertTrue(
            "扩展必须在注入前调用 ensureConfirmedBeforePremain（T-FirstUsePremainConsent S1 接线，摘掉必红）",
            constantPool.contains("ensureConfirmedBeforePremain"),
        )
        assertTrue(
            "扩展注入入口应仍走 preparePremainVmOption（防接线漂移成直连其他产物）",
            constantPool.contains("preparePremainVmOption"),
        )
    }

    // ---------- S2：attach 兜底能力边界通知 ----------

    fun testattach兜底通知_每轮至多一次_运行结束复位() {
        var notified = 0
        manager.attachFallbackNotifier = { notified++ }
        manager.notifyAttachFallbackOnce()
        manager.notifyAttachFallbackOnce()
        manager.notifyAttachFallbackOnce()
        assertEquals("同一轮运行内至多通知一次", 1, notified)
        manager.onRunEnded()
        manager.notifyAttachFallbackOnce()
        assertEquals("运行结束后新的一轮应可再通知", 2, notified)
    }

    fun testattach兜底通知_通知出口异常不外泄() {
        var notified = 0
        manager.attachFallbackNotifier = { notified++; error("通知链路炸了") }
        try {
            manager.notifyAttachFallbackOnce()
        } catch (e: IllegalStateException) {
            throw AssertionError("通知出口异常不得打断挂载链路", e)
        }
        assertEquals(1, notified)
    }
}
