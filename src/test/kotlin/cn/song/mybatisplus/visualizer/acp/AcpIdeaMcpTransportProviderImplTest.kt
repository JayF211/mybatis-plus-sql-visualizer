package cn.song.mybatisplus.visualizer.acp

import com.intellij.ml.llm.agents.acp.api.AcpAgentId
import com.intellij.ml.llm.agents.acp.client.AcpIdeaMcpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-AcpMcpTransport 传输取值契约测试（纯逻辑，无平台依赖）。
 *
 * 钉死的是**策略 (a)**（见 [AcpIdeaMcpTransportProviderImpl] KDoc 的取舍论证）：只有
 * `acp.registry.claude-acp` 返回 SSE，其余一律返回 null（不表态，让位给官方
 * AcpCopilotIdeaMcpTransportProvider 与 IDE 默认 STDIO）。
 *
 * 为什么值得测：`getTransport` 返回 null 与返回 SSE 对**其他 agent** 是两种不同后果——
 * 前者零影响，后者会把该 agent 从 STDIO 切到尚未真机实证的 SSE 通道（R3）。策略一旦被
 * 无声改成 (b)，本测试即红。
 *
 * AcpAgentId 在测试里用桩接口的假实现（真类是 sealed，桩刻意不 sealed——理由见桩文件头注释）；
 * 本测试不触达平台，故不构成跨版本签名断言。
 */
class AcpIdeaMcpTransportProviderImplTest {

    /** 假 agent id：fullId 按真类语义拼接（`AcpRegistryAgentId.getFullId()` = "acp.registry." + rawId）。 */
    private class FakeAgentId(override val rawId: String) : AcpAgentId {
        override val fullId: String = "acp.registry.$rawId"
    }

    private val provider = AcpIdeaMcpTransportProviderImpl()

    @Test
    fun `claude-acp 取 SSE`() {
        assertEquals(
            "目标 agent 必须取 SSE（绕过上游 stdio 分支的 type 判断缺陷）",
            AcpIdeaMcpTransport.SSE,
            provider.getTransport(FakeAgentId("claude-acp")),
        )
    }

    @Test
    fun `其余 agent 一律不表态`() {
        // Copilot 尤其重要：官方 provider 对它是 SSE，我们不表态才让官方实现继续生效
        for (raw in listOf("copilot", "gemini", "junie", "codex", "claude", "")) {
            assertNull(
                "非目标 agent $raw 必须返回 null（不表态）——返回 SSE 会把它从 STDIO 切到未实证的 SSE 通道",
                provider.getTransport(FakeAgentId(raw)),
            )
        }
    }

    @Test
    fun `非注册表前缀的同名 id 不表态`() {
        // 自定义 agent 的 fullId 前缀是 "acp."（AcpCustomAgentId），不是 "acp.registry."——
        // 判定必须落在 fullId 上，不能退化成 rawId 后缀匹配
        val custom = object : AcpAgentId {
            override val rawId: String = "claude-acp"
            override val fullId: String = "acp.claude-acp"
        }
        assertNull("自定义 agent 即使 rawId 同名也不得命中", provider.getTransport(custom))
    }

    @Test
    fun `CLAUDE_ACP_ID 字面量与 ml-llm 常量一致`() {
        // 权威取值：AcpBundledAgentUtils.CLAUDE_ACP_ID（ml-llm jar 常量池）+ AcpAgentId.REGISTRY_PREFIX。
        // 本断言把内联字面量钉住，改错即红（跨版本漂移须重新反编译取证）。
        assertEquals("acp.registry.claude-acp", AcpIdeaMcpTransportProviderImpl.CLAUDE_ACP_ID)
    }
}
