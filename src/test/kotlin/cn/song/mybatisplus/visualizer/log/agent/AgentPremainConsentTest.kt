package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.EnhancedCaptureConsent
import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.RunConfigurationProcessListener
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        // 去重窗默认关闭（按用例需要显式开启），防跨用例残留时间戳吞掉后续用例的确认请求
        manager.consentAskGuardMs = 0
        manager.lastConsentAskAtMs.set(0)
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
            manager.consentAskGuardMs = 0
            manager.lastConsentAskAtMs.set(0)
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

    // ---------- R3：出厂未确认态项目打开即确认 ----------

    fun testR3出厂态启动即弹_同意持久化() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = false
        manager.premainConsentGate = { true }
        providerReturning(EnhancedCaptureConsent.ACCEPTED)
        manager.maybeAskConsentAtStartup()
        flushEdt()
        assertEquals("出厂未确认态项目打开应弹一次确认（不等首轮 Run）", 1, askedCount)
        assertTrue("同意后 confirmed 应持久化——首轮 Run 即 premain", state.enhancedCaptureConfirmed)
    }

    fun testR3启动即弹_拒绝_冷却持久化() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = false
        manager.premainConsentGate = { true }
        providerReturning(EnhancedCaptureConsent.REJECTED)
        manager.maybeAskConsentAtStartup()
        flushEdt()
        assertEquals(1, askedCount)
        assertTrue(state.enhancedCaptureRejectedAtMs > 0)
        assertFalse(state.enhancedCaptureConfirmed)
    }

    fun testR3启动即弹_已确认态不弹() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = true
        manager.premainConsentGate = { true }
        providerReturning(EnhancedCaptureConsent.ACCEPTED)
        manager.maybeAskConsentAtStartup()
        flushEdt()
        assertEquals("已确认态启动不得弹窗", 0, askedCount)
    }

    fun testR3启动即弹_冷却期不弹() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = false
        state.enhancedCaptureRejectedAtMs = System.currentTimeMillis()
        manager.premainConsentGate = { true }
        providerReturning(EnhancedCaptureConsent.ACCEPTED)
        manager.maybeAskConsentAtStartup()
        flushEdt()
        assertEquals("冷却期内启动不得弹窗", 0, askedCount)
    }

    fun testR3启动即弹_EDT守卫false不弹() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = false
        manager.premainConsentGate = { false }
        providerReturning(EnhancedCaptureConsent.ACCEPTED)
        manager.maybeAskConsentAtStartup()
        flushEdt()
        assertEquals("headless/非EDT 前提不成立不得弹窗（回落 attach 链）", 0, askedCount)
        assertFalse(state.enhancedCaptureConfirmed)
    }

    /**
     * S1 接线源码守卫：LogStartupCapture.execute 必须调用 maybeAskConsentAtStartup 且在任何
     * early return 之前（摘掉接线必红）。扫剥注释后的源码代码事实——调用点级判据字节码
     * 探不到（R2 反向验证已实证勘误过一轮），用 SettingsTruthTest 同款源码扫描惯例。
     */
    fun testR3启动即弹接线源码守卫() {
        val src = stripKtComments(
            java.io.File("src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogStartupCapture.kt").readText(),
        )
        assertTrue(
            "LogStartupCapture.execute 必须调 maybeAskConsentAtStartup（T-FirstUseFreshInstallConsent S1 接线，摘掉必红）",
            src.contains("maybeAskConsentAtStartup()"),
        )
        // 必须在 shouldStartCapture 早退之前（出厂态与自动监听开关无关）
        val callIdx = src.indexOf("maybeAskConsentAtStartup()")
        val earlyReturnIdx = src.indexOf("if (!shouldStartCapture(")
        assertTrue("守卫前置：两锚点均应在场", callIdx in 0 until earlyReturnIdx)
    }

    /**
     * S2 接线源码守卫：补扫判据必须是 JVM 进程判定（BaseOSProcessHandler），不得再用
     * getRunProfile 探测（243 无此方法），且必须有无命中也输出的汇总诊断日志
     * （R2「结构性空转零诊断」形态禁再发生）。
     */
    fun testR3补扫判据与诊断源码守卫() {
        val src = stripKtComments(
            java.io.File("src/main/kotlin/cn/song/mybatisplus/visualizer/log/RunConfigurationProcessListener.kt").readText(),
        )
        assertTrue(
            "补扫主配置判据应为 JVM 进程判定（BaseOSProcessHandler 系）",
            src.contains("BaseOSProcessHandler"),
        )
        // 限定补扫函数体：getRunProfile 在 ExecutionEnvironment（事件链）上合法存在，
        // 禁令只针对 descriptor 探测（243 RunContentDescriptor 无此方法，结构性空转）。
        // 20261008：扫描实现从 backfillRunningProcesses 收敛到 scanAndAttachRunningProcesses/
        // doScanAndAttach（设置变更上升沿共用），切片取 doScanAndAttach 真身并一并覆盖 backfill 包装。
        val bfStart = src.indexOf("private fun backfillRunningProcesses")
        assertTrue("守卫前置：backfill 函数应在场", bfStart >= 0)
        val nextMember = src.indexOf("private fun", bfStart + 10)
        val bf = src.substring(bfStart, if (nextMember > bfStart) nextMember else src.length)
        val scanStart = src.indexOf("private fun doScanAndAttach")
        assertTrue("守卫前置：doScanAndAttach 扫描体应在场", scanStart >= 0)
        val scanEnd = src.indexOf("private fun", scanStart + 10)
        val scanBody = src.substring(scanStart, if (scanEnd > scanStart) scanEnd else src.length)
        assertFalse(
            "backfill 不得再用 getRunProfile 探测（243 RunContentDescriptor 无此方法，结构性空转）",
            bf.contains("getRunProfile"),
        )
        assertFalse(
            "扫描体不得再用 getRunProfile 探测",
            scanBody.contains("getRunProfile"),
        )
        // R3 诊断红线（20261008 措辞随收敛调整，判别力不变：无命中也必须输出汇总）：
        // 汇总行带 logTag 场景前缀与补发计数。
        assertTrue(
            "补扫必须有无命中也输出的汇总诊断日志",
            src.contains("\"\$logTag: 在跑进程扫描完成——描述符 \${descriptors.size} 个，补发 \$backfilled\""),
        )
        assertTrue(
            "backfill 场景应以 backfill 标签走同一扫描入口（诊断可区分场景）",
            src.contains("scanAndAttachRunningProcesses(project, logTag = \"backfill\")"),
        )
    }

    private fun stripKtComments(src: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(src) { m ->
            m.value.replace(Regex("[^\n]"), " ")
        }
        return noBlock.lineSequence().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }
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

    // ---------- R2-C：设置页开启上升沿立即确认 ----------

    private fun publishSettingsChange() {
        project.messageBus.syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()
    }

    /**
     * lastKnownEnabled 在 AgentManager 构造时快照 state（测试里 setUp 改 state 不发事件，快照滞留旧值）。
     * 先置关并发一次事件同步基线，再走「开」上升沿——生产不受影响（真实 toggle 每步都发事件）。
     */
    private fun armRisingEdgeBaseline() {
        PluginSettings.getInstance().state.enhancedCaptureEnabled = false
        publishSettingsChange()
    }

    fun testR2设置页开启上升沿_未确认_立即弹确认_同意持久化() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureConfirmed = false
        manager.premainConsentGate = { true }
        providerReturning(EnhancedCaptureConsent.ACCEPTED)
        armRisingEdgeBaseline()
        // 模拟设置页开启：先置位再发布（上升沿）
        state.enhancedCaptureEnabled = true
        publishSettingsChange()
        assertEquals("开启上升沿应立即弹一次确认（不等首轮 Run）", 1, askedCount)
        assertTrue("同意后 confirmed 应持久化——之后每轮 Run 天然带 premain", state.enhancedCaptureConfirmed)
    }

    fun testR2设置页开启上升沿_拒绝_冷却持久化且不置confirmed() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureConfirmed = false
        manager.premainConsentGate = { true }
        providerReturning(EnhancedCaptureConsent.REJECTED)
        armRisingEdgeBaseline()
        state.enhancedCaptureEnabled = true
        publishSettingsChange()
        assertEquals(1, askedCount)
        assertTrue("拒绝应持久化拒绝时刻", state.enhancedCaptureRejectedAtMs > 0)
        assertFalse(state.enhancedCaptureConfirmed)
    }

    fun testR2已确认或冷却态_上升沿不弹() {
        val state = PluginSettings.getInstance().state
        manager.premainConsentGate = { true }
        providerReturning(EnhancedCaptureConsent.ACCEPTED)
        armRisingEdgeBaseline()
        state.enhancedCaptureConfirmed = true
        state.enhancedCaptureEnabled = true
        publishSettingsChange()
        assertEquals("已确认态上升沿不得弹窗", 0, askedCount)
    }

    fun testR2设置页关闭_下降沿不弹() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        manager.premainConsentGate = { true }
        providerReturning(EnhancedCaptureConsent.ACCEPTED)
        state.enhancedCaptureEnabled = false
        publishSettingsChange()
        assertEquals("下降沿不得弹窗", 0, askedCount)
    }

    // ---------- R2：确认请求去重窗（O-66 引导链连发防双弹） ----------

    fun testR2去重窗内重复请求_只弹一次() {
        manager.consentAskGuardMs = AgentManager.DEFAULT_CONSENT_ASK_GUARD_MS
        manager.premainConsentGate = { true }
        providerReturning(EnhancedCaptureConsent.ACCEPTED)
        val first = manager.requestConsentOnce()
        assertEquals(EnhancedCaptureConsent.ACCEPTED, first)
        val second = manager.requestConsentOnce()
        assertNull("去重窗内的第二次请求应被吞掉（O-66 引导链连发形态）", second)
        assertEquals("两次请求只应弹一次", 1, askedCount)
    }

    fun testR2去重窗关闭_连续请求都放行() {
        manager.consentAskGuardMs = 0
        manager.premainConsentGate = { true }
        providerReturning(EnhancedCaptureConsent.ACCEPTED)
        assertNotNull(manager.requestConsentOnce())
        assertNotNull(manager.requestConsentOnce())
        assertEquals(2, askedCount)
    }

    fun testR2上升沿与O66引导连发_全局只弹一次() {
        val state = PluginSettings.getInstance().state
        manager.consentAskGuardMs = AgentManager.DEFAULT_CONSENT_ASK_GUARD_MS
        manager.premainConsentGate = { true }
        providerReturning(EnhancedCaptureConsent.ACCEPTED)
        // O-66 引导链完整形态：置位开关 → 发布 SETTINGS_CHANGE（触发上升沿确认）→ 紧接 requestReauthorization
        armRisingEdgeBaseline()
        state.enhancedCaptureEnabled = true
        publishSettingsChange()
        manager.requestReauthorization()
        flushEdt()
        assertEquals("上升沿与引导流毫秒级连发只应弹一个框", 1, askedCount)
        assertTrue(state.enhancedCaptureConfirmed)
    }

    private fun flushEdt() {
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
    }

    // ---------- R2-A2：订阅后补扫接线守卫 ----------

    /**
     * 订阅后补扫接线守卫：[RunConfigurationProcessListener] 必须调 backfillRunningProcesses 且
     * 实现须枚举 RCM 描述符。扫的是剥注释后的源码代码事实（方法声明自身会把符号留在常量池，
     * 字节码守卫探不到「调用点摘除」，故用 SettingsTruthTest 同款源码扫描惯例）。
     */
    fun testR2补扫接线守卫_subscribe后必须backfill() {
        val src = java.io.File("src/main/kotlin/cn/song/mybatisplus/visualizer/log/RunConfigurationProcessListener.kt")
            .readText()
        val noComments = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(src) { m ->
            m.value.replace(Regex("[^\n]"), " ")
        }.lineSequence().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }
        assertTrue(
            "订阅成功后必须调 backfillRunningProcesses（T-FirstUsePremainConsent R2-A2 接线，摘掉必红）",
            noComments.contains("backfillRunningProcesses(project)"),
        )
        assertTrue(
            "补扫实现应枚举 RCM getAllDescriptors",
            noComments.contains("getAllDescriptors"),
        )
    }
}
