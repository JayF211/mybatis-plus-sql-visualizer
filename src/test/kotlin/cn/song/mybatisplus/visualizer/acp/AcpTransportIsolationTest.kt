package cn.song.mybatisplus.visualizer.acp

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-AcpMcpTransport 可选依赖隔离的 headless 实证（验收标准 4 的可测部分）。
 *
 * 可测的口径只有一条：**AI Assistant（com.intellij.ml.llm）不在场的环境里，本插件照常加载**。
 * 本测试跑在构建基准平台 IU 2024.3.7.1（243）的测试沙盒里——该平台与 CI 的三个 verify 目标
 * 一样都不含 ml-llm（AI Assistant 是 Marketplace 独立插件，非发行版捆绑）。于是：
 *  - 前提断言：ml-llm 标记类不可解析（用 `Class.forName` 探测，与 ExecuteSqlSupport.isAvailable
 *    同款手法——绕开 PluginId API 在 2025.3~2026.2 的包迁移）；
 *  - 结论断言：本插件自己的类仍可解析（插件 jar 与描述文件在沙盒内正常加载）。plugin.xml 解析
 *    一旦失败整个插件缺席，本测试类（BasePlatformTestCase 子类）根本跑不起来——能跑即证
 *    「新增的 `<depends optional="true" config-file="acp-transport-optional.xml">` 与主文件共存无碍」。
 *
 * **不可测的部分**（禁止用本用例冒充）：ml-llm 在场时 provider 是否真的被注册、传输是否真的切到
 * SSE、agent 侧能否连上 SSE 通道——前者要装 AI Assistant 的沙盒，后者要 runIde + ACP 面板真机
 * 走查（见计划 §五/§六 R3）。
 *
 * 探测用的 ml-llm 标记类刻意选 **AcpBundledAgentUtils**：本项目桩源集只桩了
 * AcpAgentId/AcpIdeaMcpTransport/AcpIdeaMcpTransportProvider 三个类型，该类不在其中，
 * 故测试 classpath 上的桩不会把它伪装成「存在」。
 */
class AcpTransportIsolationTest : BasePlatformTestCase() {

    fun testMlLlmAbsentWhilePluginStillLoads() {
        val mlLlmPresent = runCatching { Class.forName(ML_LLM_MARKER_CLASS) }.isSuccess
        assertFalse(
            "基线 243 测试沙盒出现了 AI Assistant（ml-llm）——本用例的前提是它缺席；" +
                "若平台侧开始捆绑 ml-llm，须重新评估 acp-transport-optional.xml 的加载面与验收口径",
            mlLlmPresent,
        )

        val providerClass = runCatching {
            Class.forName("cn.song.mybatisplus.visualizer.acp.AcpIdeaMcpTransportProviderImpl")
        }.getOrNull()
        assertNotNull(
            "本插件的 provider 实现类在沙盒内不可解析——插件 jar/描述文件加载异常",
            providerClass,
        )
        // 类实现了 EP 声明的接口（此处由桩提供同一 FQN），且类可加载 ⇒ 签名与 EP 声明一致
        val implemented = providerClass!!.interfaces.map { it.name }
        assertTrue(
            "provider 未实现 EP 声明的接口：$implemented（acp-transport-optional.xml 的 EP 注册会失败）",
            implemented.contains("com.intellij.ml.llm.agents.acp.client.AcpIdeaMcpTransportProvider"),
        )
    }

    private companion object {
        /** ml-llm 真实类（非本项目桩类型），用于探测插件是否在场。 */
        const val ML_LLM_MARKER_CLASS = "com.intellij.ml.llm.agents.acp.registry.AcpBundledAgentUtils"
    }
}
