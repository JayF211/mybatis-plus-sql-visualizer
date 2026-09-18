package cn.song.mybatisplus.visualizer.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * T-SettingsTruth P1 守卫测试（纯逻辑，无平台依赖，进 testFast 快测桶）：
 *  - State 不再含 autoInjectSqlSourceInterceptor 字段（写而不读的假开关防回潮——反射断言字段不存在）
 *  - 双语 bundle tooltip 均含「不保存状态 / does not persist」声明（properties 文本断言）
 *
 * P2 的 statusSummary 三态依赖 AgentManager（项目级服务 + messageBus + 项目上下文），
 * 需平台测试环境（BasePlatformTestCase，全量 test 桶）；三态中的「从未挂载 null」与
 * 「中断回落」文案契约以源码级断言覆盖（见 settingsTruth_sourceLevelGuards）。
 */
class SettingsTruthTest {

    // ---------- P1 ----------

    fun testP1_state无autoInjectSqlSourceInterceptor字段() {
        val declared = PluginSettings.State::class.java.declaredFields.map { it.name }
        assertFalse(
            "State 不得再声明 autoInjectSqlSourceInterceptor（纯动作开关不持久化）",
            declared.any { it.equals("autoInjectSqlSourceInterceptor", ignoreCase = true) },
        )
    }

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

    // ---------- P2（源码级守卫；statusSummary 语义态走全量 test 平台桶） ----------

    fun testP2_statusSummary三态源码守卫() {
        val src = java.io.File("src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt")
            .readText()
        // statusSummary 函数体存在且三态文案齐全
        val fnStart = src.indexOf("internal fun statusSummary")
        assertTrue("AgentManager 应声明 statusSummary", fnStart >= 0)
        val fnBody = src.substring(fnStart, src.indexOf("}", src.indexOf("return null", fnStart)) + 1)
        assertTrue("statusSummary 应含 pid 文案", fnBody.contains("已挂载 "))
        assertTrue("statusSummary 应含 pid= 拼装", fnBody.contains("pid="))
        assertTrue("statusSummary 应含中断回落文案", fnBody.contains("连接中断，已回落控制台捕获"))
        assertTrue("statusSummary 无会话应返回 null", fnBody.contains("return null"))
        // 三处广播点：attach 成功 / sweepOffline 摘除 / shutdownAll
        val count = Regex("publishStatusChanged\\(\\)").findAll(src).count()
        // 定义 1 次 + 三个调用点
        assertTrue("publishStatusChanged 应恰有 3 个调用点（实际 $count-1）", count == 4)
        // Topic 声明形态（仿 PluginSettings.SETTINGS_CHANGE_TOPIC）
        assertTrue("AGENT_STATUS_TOPIC 应为 Topic.create", src.contains("AGENT_STATUS_TOPIC: Topic<AgentStatusListener> = Topic.create("))
        // 面板侧接线
        val panelSrc = java.io.File("src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt")
            .readText()
        assertTrue("面板应订阅 AGENT_STATUS_TOPIC", panelSrc.contains("AgentManager.AGENT_STATUS_TOPIC"))
        assertTrue("面板应有 disposeResources", panelSrc.contains("internal fun disposeResources()"))
        assertTrue("面板 reset 应拉取状态行", panelSrc.contains("refreshEnhancedStatusLabel()"))
        val secSrc = java.io.File("src/main/kotlin/cn/song/mybatisplus/visualizer/config/SectionConfigurable.kt")
            .readText()
        assertTrue("SectionConfigurable.disposeUIResources 应调 panel.disposeResources()",
            Regex("disposeUIResources[\\s\\S]{0,200}panel\\.disposeResources\\(\\)").containsMatchIn(secSrc))
        // P1 面板三处绑定已删
        assertFalse("面板不得再写 autoInjectSqlSourceInterceptor", panelSrc.contains("autoInjectSqlSourceInterceptor"))
    }
}
