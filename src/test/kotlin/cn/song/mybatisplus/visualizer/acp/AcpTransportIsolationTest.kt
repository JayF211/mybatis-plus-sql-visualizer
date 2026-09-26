package cn.song.mybatisplus.visualizer.acp

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

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

        // T-MarketCompatProxy（20260923）：provider 实现类（implements ml-llm internal 接口）已删除，
        // 改为动态代理 + 探测式注册（AcpTransportRegistrar）。隔离断言同步为两层：
        // ① 注册器在沙盒内可解析（插件 jar/描述文件加载正常）；
        // ② 注册器字节码零 ml-llm / platform.acp 类型引用（单包形态下消 Marketplace「未解析类」
        //    读数的机制本体——类引用全部经 Class.forName 字符串，缺 ml-llm 环境下永不链接）。
        val registrarClass = runCatching {
            Class.forName("cn.song.mybatisplus.visualizer.acp.AcpTransportRegistrar")
        }.getOrNull()
        assertNotNull(
            "本插件的传输注册器在沙盒内不可解析——插件 jar/描述文件加载异常",
            registrarClass,
        )
        // location 可为 null（沙盒内类经共享 loader 装载，无独立 codeSource）——此时跳过字节级
        // 断言：Class.forName 通过已证明类可加载；字节级守卫另有源码扫描守卫（AcpTransportScopeSourceScanTest）
        registrarClass!!.protectionDomain.codeSource?.location?.toURI()?.let { uri ->
            val dir = File(uri).takeIf { it.isDirectory } ?: return@let
            val classFile = File(dir, "cn/song/mybatisplus/visualizer/acp/AcpTransportRegistrar.class")
            if (classFile.isFile) {
                // ISO_8859_1 把字节无损映射到 char，contains 即字节序列查找（不引依赖）
                val text = String(classFile.readBytes(), Charsets.ISO_8859_1)
                val leaked = listOf(
                    "com/intellij/ml/llm",
                    "com/intellij/platform/acp",
                    "com/intellij/mcpserver",
                ).filter { text.contains(it) }
                assertTrue(
                    "注册器字节码出现 ml-llm/acp 平台类型引用：$leaked——" +
                        "动态代理形态的「旧版零未解析类读数」依赖字节码零引用，破坏即回归",
                    leaked.isEmpty(),
                )
            }
            // classFile 不在目录形态（如 jar 内）时跳过字节级断言：Class.forName 通过已证明加载正常
        }
    }


    private companion object {
        /** ml-llm 真实类（非本项目桩类型），用于探测插件是否在场。 */
        const val ML_LLM_MARKER_CLASS = "com.intellij.ml.llm.agents.acp.registry.AcpBundledAgentUtils"
    }
}
