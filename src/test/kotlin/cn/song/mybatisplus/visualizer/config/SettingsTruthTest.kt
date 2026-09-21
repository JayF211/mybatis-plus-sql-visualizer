package cn.song.mybatisplus.visualizer.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-SettingsTruth P1/P2 守卫测试（纯逻辑，无平台依赖，进 testFast 快测桶）：
 *  - State 不再含 autoInjectSqlSourceInterceptor 字段（写而不读的假开关防回潮——反射断言字段不存在）
 *  - 双语 bundle tooltip 均含「不保存状态 / does not persist」声明（properties 文本断言）
 *
 * P2 的 statusSummary 语义态由 T-CaptureTruth T-CT-01 拆为纯函数 [AgentManagerStatusText] 后
 * 改由运行期测试覆盖（`log/agent/AgentStatusTextTest`，五态全分支），本文件只留**接线**类源码断言。
 *
 * **T-CaptureTruth T-CT-01 附带修复**：本类三个用例此前**从未被执行**——既无 `@Test` 注解也不继承
 * JUnit3 `TestCase`，任何 runner 都不会发现它们（全仓 test-results XML 中零出现为证），
 * 属「守卫存在但从未运行」的假守卫；而其中「statusSummary 无会话应返回 null」这条断言恰好把
 * T-CaptureTruth 的缺陷（开着但未授权 → 状态行空白）钉成了「期望行为」。现补 `@Test` 使守卫真正生效。
 */
class SettingsTruthTest {

    // ---------- P1 ----------

    @Test
    fun testP1_state无autoInjectSqlSourceInterceptor字段() {
        val declared = PluginSettings.State::class.java.declaredFields.map { it.name }
        assertFalse(
            "State 不得再声明 autoInjectSqlSourceInterceptor（纯动作开关不持久化）",
            declared.any { it.equals("autoInjectSqlSourceInterceptor", ignoreCase = true) },
        )
    }

    @Test
    fun testP1_tooltip双语均声明不保存状态() {
        val zh = javaClass.getResourceAsStream("/messages/MpSqlVisualizerBundle_zh.properties")
            ?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
                lines.firstOrNull { it.startsWith("settings.log.autoInjectInterceptor.tip=") }
            }
        val en = javaClass.getResourceAsStream("/messages/MpSqlVisualizerBundle.properties")
            ?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
                lines.firstOrNull { it.startsWith("settings.log.autoInjectInterceptor.tip=") }
            }
        assertTrue("zh tooltip 应存在", zh != null)
        assertTrue("en tooltip 应存在", en != null)
        assertTrue("zh tooltip 应声明「本开关不保存状态」", zh!!.contains("不保存状态"))
        assertTrue("en tooltip 应声明 does not persist", en!!.contains("does not persist"))
    }

    // ---------- P2 / T-CaptureTruth T-CT-01/02：接线源码守卫（语义走 AgentStatusTextTest） ----------

    private fun mainSrc(rel: String) = java.io.File("src/main/kotlin/$rel").readText()

    /** 剥 // 行注释与块注释——守卫扫的是代码事实，注释里写「不得如何」不该被判违规。 */
    private fun stripComments(src: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(src) { m ->
            m.value.replace(Regex("[^\n]"), " ")
        }
        return noBlock.split('\n').joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }
    }

    @Test
    fun testP2_状态行接线与五态键源码守卫() {
        val src = mainSrc("cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt")
        // statusSummary 只取数，文案交纯函数（纯函数五态语义由 AgentStatusTextTest 运行期覆盖）
        assertTrue("AgentManager 应声明 statusSummary", src.contains("internal fun statusSummary()"))
        assertTrue("statusSummary 应委托纯函数 agentStatusText", src.contains("agentStatusText("))
        assertTrue("应有状态行输入快照 AgentStatusSnapshot", src.contains("internal data class AgentStatusSnapshot("))
        // 五态文案键齐备（不许再出现「无会话恒 null」的收敛）
        listOf(
            "settings.enhancedCapture.status.attached",
            "settings.enhancedCapture.status.fellBack",
            "settings.enhancedCapture.status.authorizedWaiting",
            "settings.enhancedCapture.status.unauthorizedCooldown",
            "settings.enhancedCapture.status.unauthorizedPending",
            // T-AgentHeartbeat T-05：心跳两因（停摆 / 首条心跳未到）必须各有独立键
            "settings.enhancedCapture.status.heartbeatLost",
            "settings.enhancedCapture.status.firstHeartbeatPending",
        ).forEach { key ->
            assertTrue("agentStatusText 应引用状态行键 $key", src.contains("\"$key\""))
        }
        // T-CT-02：重新授权入口存在且清零冷却
        assertTrue("应有 requestReauthorization 入口", src.contains("fun requestReauthorization()"))
        // Topic 声明形态（仿 PluginSettings.SETTINGS_CHANGE_TOPIC）
        assertTrue("AGENT_STATUS_TOPIC 应为 Topic.create", src.contains("AGENT_STATUS_TOPIC: Topic<AgentStatusListener> = Topic.create("))
    }

    @Test
    fun testP2_状态变更广播点覆盖同意流三态() {
        val src = mainSrc("cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt")
        // 广播点：定义 1 + attach 成功 1 + sweepOffline 1 + shutdownAll 1 + 确认流三态 3 + 重新授权 1 = 8
        // T-M56Phase2-Premain（合流后 +1）：premain 回连建会话成功亦广播一次状态行（启动即捕获生效）
        val count = Regex("publishStatusChanged\\(\\)").findAll(src).count()
        assertTrue("publishStatusChanged 应有 9 处（定义 1 + 调用 8，实际 $count）", count == 9)
    }

    @Test
    fun testP2_面板接线与不得自注册Disposer() {
        val panelSrc = stripComments(mainSrc("cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt"))
        assertTrue("面板应订阅 AGENT_STATUS_TOPIC", panelSrc.contains("AgentManager.AGENT_STATUS_TOPIC"))
        assertTrue("面板应有 disposeResources", panelSrc.contains("internal fun disposeResources()"))
        assertTrue("面板 reset 应拉取状态行", panelSrc.contains("refreshEnhancedStatusLabel()"))
        // T-CaptureTruth T-CT-05：面板不得把自身注册进 Disposer 树——父节点未注册会落 ROOT_DISPOSABLE
        // 子节点而 Configurable 链路无 dispose 面板的动作，IDE 退出即报 Memory leak（沙盒实测 6 例）
        assertFalse(
            "面板不得 Disposer.register(this@PluginSettingsPanel, …)（ROOT 子节点无释放路径 → 泄漏）",
            panelSrc.contains("Disposer.register(this@PluginSettingsPanel"),
        )
        assertTrue("面板应有重新授权按钮接线", panelSrc.contains("requestReauthorization()"))
        val secSrc = mainSrc("cn/song/mybatisplus/visualizer/config/SectionConfigurable.kt")
        assertTrue("SectionConfigurable.disposeUIResources 应调 panel.disposeResources()",
            Regex("disposeUIResources[\\s\\S]{0,200}panel\\.disposeResources\\(\\)").containsMatchIn(secSrc))
        // P1 面板三处绑定已删
        assertFalse("面板不得再写 autoInjectSqlSourceInterceptor", panelSrc.contains("autoInjectSqlSourceInterceptor"))
    }
}
