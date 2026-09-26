package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-McpGateDynamicFilter（O-68）注册结构守卫（纯逻辑，testFast 桶）。钉住三条结构不变量：
 *
 *  1. plugin.xml 对 `com.intellij.mcpServer` 只允许**一条** optional depends——第二条会被
 *     verifier 判 "duplicated dependency" 结构缺陷（2026-09-23 verifyGate 实测），且双片段
 *     加载形态不可靠；过滤注册必须走 mcp-optional.xml 内的探测式 McpGateFilterRegistrar；
 *  2. mcp-optional.xml 内**不得**直接 XML 声明 `mcpToolFilterProvider`——253 线无此 EP，
 *     未知 EP 会拒载整文件连坐十个 toolset（idea-2025.3.5 mcpserver.jar 本地实测）；
 *  3. 探测式注册的 registrar 与桥类在册（改名漂移会让注册静默失效）。
 */
class McpGateFilterRegistrationGuardTest {

    private val pluginXml = File("src/main/resources/META-INF/plugin.xml")
    private val mcpOptional = File("src/main/resources/META-INF/mcp-optional.xml")

    @Test
    fun `mcpServer依赖只允许一条optional声明`() {
        val text = pluginXml.readText()
        val count = Regex(">com\\.intellij\\.mcpServer<").findAll(text).count()
        assertEquals("com.intellij.mcpServer depends 声明必须恰好一条（第二条 = duplicated dependency 结构缺陷）", 1, count)
        assertFalse("独立过滤片段文件应已移除（改走探测式注册）", File("src/main/resources/META-INF/mcp-filter-optional.xml").exists())
    }

    @Test
    fun `过滤EP不得XML直宣且注册器必须在册`() {
        val text = mcpOptional.readText()
        assertFalse("mcpToolFilterProvider 直宣会在 253 拒载整文件连坐十个 toolset", text.contains("mcpToolFilterProvider"))
        assertTrue("探测式注册器在册", text.contains("McpGateFilterRegistrar"))
    }

    @Test
    fun `探测与桥两件在册防改名漂移`() {
        assertTrue(
            McpGateFilterEpRegistrar::class.java.name.endsWith("McpGateFilterEpRegistrar"),
        )
        // k2j 适配：master 的 McpGateFilterRegistrar.kt（class + object 同文件）在 Java 侧拆为
        // McpGateFilterRegistrar.java（ProjectActivity 入口）与 McpGateFilterEpRegistrar.java
        // （探测+注册本体）两件——Class.forName 探针锚点随拆分下沉到 Ep 文件（FILTER_IFACE_FQN 常量）。
        val registrarSrc = File("src/main/java/cn/song/mybatisplus/visualizer/mcp/McpGateFilterEpRegistrar.java").readText()
        assertTrue("Class.forName 探针必须在册", registrarSrc.contains("com.intellij.mcpserver.McpToolFilterProvider"))
        val bridgeSrc = File("src/main/java/cn/song/mybatisplus/visualizer/mcp/McpGateFilterEpBridge.java").readText()
        assertTrue("EP 名 ldc 依据必须在册", bridgeSrc.contains("com.intellij.mcpServer.mcpToolFilterProvider"))
    }
}
