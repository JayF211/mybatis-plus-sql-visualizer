package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-EnhancedCaptureDefaultOn：premain 注入只看增强捕获总开关。
 *
 * 历史：本类原名 `AgentPremainConsentTest`，覆盖 T-FirstUsePremainConsent 的确认/冷却/去重三态。
 * 2026-10-19 注入确认流整体移除后，仅保留「开关 → premain 产出」与既有的 attach 兜底通知、
 * 补扫接线守卫；确认相关用例随 `EnhancedCaptureConfirmDialog` 一起删除。
 */
class AgentPremainGateTest : BasePlatformTestCase() {

    private lateinit var manager: AgentManager

    override fun setUp() {
        super.setUp()
        manager = AgentManager.getInstance(project)
        manager.clockMs = { System.currentTimeMillis() }
        manager.attachFallbackNotifiedThisSession.set(false)
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        // 旧 XML 里可能残留 false；新语义下不得影响注入。
        state.enhancedCaptureConfirmed = false
        state.enhancedCaptureRejectedAtMs = 0L
    }

    override fun tearDown() {
        try {
            manager.shutdownAll()
            val state = PluginSettings.getInstance().state
            state.enhancedCaptureEnabled = false
            state.enhancedCaptureConfirmed = true
            state.enhancedCaptureRejectedAtMs = 0L
            manager.attachFallbackNotifier = null
        } finally {
            super.tearDown()
        }
    }

    // ---------- premain 闸门 ----------

    fun test开关打开_旧未确认态也产出premainVM选项() {
        val option = manager.preparePremainVmOption()
        assertNotNull("开关打开即授权，confirmed=false 不得再拦 premain", option)
        assertTrue(option!!.startsWith("-javaagent:"))
        manager.shutdownAll() // 通道随用例收口，不留挂起端口给下个用例
    }

    fun test开关关闭_不产出premainVM选项() {
        PluginSettings.getInstance().state.enhancedCaptureEnabled = false
        assertNull(manager.preparePremainVmOption())
    }

    /**
     * 接线字节码守卫：扩展必须走 preparePremainVmOption，且不得再引用已删除的前置确认入口。
     * 摘掉调用 → 常量池不再含 preparePremainVmOption → 本用例必红。
     */
    fun test扩展接线字节码守卫_只走premain准备且不再前置确认() {
        val classBytes = PremainRunConfigurationExtension::class.java
            .getResourceAsStream("PremainRunConfigurationExtension.class")!!.readBytes()
        val constantPool = String(classBytes, Charsets.ISO_8859_1)
        assertTrue(
            "扩展注入入口应走 preparePremainVmOption（防接线漂移）",
            constantPool.contains("preparePremainVmOption"),
        )
        assertFalse(
            "确认流已移除，扩展不得再引用 ensureConfirmedBeforePremain",
            constantPool.contains("ensureConfirmedBeforePremain"),
        )
    }

    // ---------- S2：attach 兜底能力边界通知（保留） ----------

    fun testattach兜底通知_每会话至多一次_运行结束不复位() {
        var notified = 0
        manager.attachFallbackNotifier = { notified++ }
        manager.notifyAttachFallbackOnce()
        manager.notifyAttachFallbackOnce()
        manager.notifyAttachFallbackOnce()
        assertEquals("同一会话内至多通知一次", 1, notified)
        manager.onRunEnded()
        manager.notifyAttachFallbackOnce()
        assertEquals("运行结束不再复位——避免每次 Run / 重启项目重复弹同一条告知", 1, notified)
    }

    /**
     * 反向验证：去重标记是唯一闸门——清除后同条件再触发即重复弹出。
     * 若摘掉 [AgentManager.notifyAttachFallbackOnce] 的 CAS 守卫，上一条用例会读到 4（重复弹出）。
     */
    fun testattach兜底通知_去重标记清除后重复弹出_反向验证() {
        var notified = 0
        manager.attachFallbackNotifier = { notified++ }
        manager.notifyAttachFallbackOnce()
        assertEquals(1, notified)
        manager.attachFallbackNotifiedThisSession.set(false)
        manager.notifyAttachFallbackOnce()
        assertEquals("清标记后重复弹出——证明去重条件确在起作用", 2, notified)
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

    // ---------- R3：订阅后补扫接线守卫（保留） ----------

    /**
     * S2 接线源码守卫：补扫判据必须是 JVM 进程判定（BaseOSProcessHandler），不得再用
     * getRunProfile 探测（243 无此方法），且必须有无命中也输出的汇总诊断日志。
     */
    fun testR3补扫判据与诊断源码守卫() {
        val src = stripKtComments(
            java.io.File("src/main/kotlin/cn/song/mybatisplus/visualizer/log/RunConfigurationProcessListener.kt").readText(),
        )
        assertTrue(
            "补扫主配置判据应为 JVM 进程判定（BaseOSProcessHandler 系）",
            src.contains("BaseOSProcessHandler"),
        )
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
        assertTrue(
            "补扫必须有无命中也输出的汇总诊断日志",
            src.contains("\"\$logTag: 在跑进程扫描完成——描述符 \${descriptors.size} 个，补发 \$backfilled\""),
        )
        assertTrue(
            "backfill 场景应以 backfill 标签走同一扫描入口（诊断可区分场景）",
            src.contains("scanAndAttachRunningProcesses(project, logTag = \"backfill\")"),
        )
    }

    /**
     * 订阅后补扫接线守卫：[RunConfigurationProcessListener] 必须调 backfillRunningProcesses 且
     * 实现须枚举 RCM 描述符。扫的是剥注释后的源码代码事实（方法声明自身会把符号留在常量池，
     * 字节码守卫探不到「调用点摘除」，故用 SettingsTruthTest 同款源码扫描惯例）。
     */
    fun testR2补扫接线守卫_subscribe后必须backfill() {
        val src = stripKtComments(
            java.io.File("src/main/kotlin/cn/song/mybatisplus/visualizer/log/RunConfigurationProcessListener.kt").readText(),
        )
        assertTrue(
            "订阅成功后必须调 backfillRunningProcesses（T-FirstUsePremainConsent R2-A2 接线，摘掉必红）",
            src.contains("backfillRunningProcesses(project)"),
        )
        assertTrue(
            "补扫实现应枚举 RCM getAllDescriptors",
            src.contains("getAllDescriptors"),
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
}
