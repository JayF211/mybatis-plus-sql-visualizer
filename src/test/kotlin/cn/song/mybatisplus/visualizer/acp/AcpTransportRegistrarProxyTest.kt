package cn.song.mybatisplus.visualizer.acp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * T-AcpMcpTransport 传输取值契约测试（T-MarketCompatProxy 后形态）。
 *
 * 钉死的是**策略 (a)**：只有 `acp.registry.claude-acp` 返回 SSE，其余一律返回 null（不表态，
 * 让位给官方 AcpCopilotIdeaMcpTransportProvider 与 IDE 默认 STDIO）。为什么值得测：getTransport
 * 返回 null 与返回 SSE 对**其他 agent** 是两种不同后果——前者零影响，后者会把该 agent 从 STDIO
 * 切到尚未真机实证的 SSE 通道（R3）。策略一旦被无声改成 (b)，本测试即红。
 *
 * T-MarketCompatProxy 后实现形态是**动态代理**（[AcpTransportEpRegistrar] 全反射注册，字节码
 * 零 ml-llm 类型引用），分发体抽成 [transportHandler]（SSE 常量提供者参数化）：本测试注入自有
 * 标记对象即可断言全分支，无需 ml-llm 类在测试 classpath 上；同时覆盖「形参静态类型无关性」
 * （代理按方法名分发，原双形态重载的兼容性由 handler 天然承载）。不触达平台，不构成跨版本
 * 签名断言（制品级断言在 AcpSignatureCrossVersionTest）。
 */
class AcpTransportRegistrarProxyTest {

    /** 假 agent：handler 反射调 getFullId()，鸭子形态即可。 */
    private class FakeAgent(val fullIdValue: String) {
        fun getFullId(): String = fullIdValue
    }

    /** SSE 标记对象（注入 handler 的 sseProvider 返回值；真形态是平台枚举常量）。 */
    private object SseMarker

    private fun handler(): InvocationHandler = AcpTransportRegistrarKt.transportHandler { SseMarker }

    private fun transportOf(agent: FakeAgent): Any? {
        val m = FakeAgentTransport::class.java.methods.first { it.name == "getTransport" }
        return handler().invoke(ANY_PROXY, m, arrayOf(agent))
    }

    /** handler 按方法名分发，Method 只需同名——本文件私有接口承载签名。 */
    private interface FakeAgentTransport {
        fun getTransport(agent: FakeAgent): Any?
    }

    private companion object {
        val ANY_PROXY: Any = Proxy.newProxyInstance(
            AcpTransportRegistrarProxyTest::class.java.classLoader,
            arrayOf(FakeAgentTransport::class.java),
        ) { _, _, _ -> null }
    }

    @Test
    fun `claude-acp 取 SSE`() {
        assertEquals(
            "目标 agent 必须取 SSE（绕过上游 stdio 分支的 type 判断缺陷）",
            SseMarker,
            transportOf(FakeAgent("acp.registry.claude-acp")),
        )
    }

    @Test
    fun `其余 agent 一律不表态`() {
        for (raw in listOf("copilot", "gemini", "junie", "codex", "claude", "")) {
            assertNull(
                "非目标 agent $raw 必须返回 null（不表态）——返回 SSE 会把它从 STDIO 切到未实证的 SSE 通道",
                transportOf(FakeAgent(raw)),
            )
        }
    }

    @Test
    fun `非注册表前缀的同名 id 不表态`() {
        // 自定义 agent 的 fullId 前缀是 "acp."（AcpCustomAgentId），不是 "acp.registry."——
        // 判定必须落在 fullId 上，不能退化成 rawId 后缀匹配
        assertNull(
            "自定义 agent 即使 rawId 同名也不得命中",
            transportOf(FakeAgent("acp.claude-acp")),
        )
    }

    @Test
    fun `CLAUDE_ACP_ID 字面量与 ml-llm 常量一致`() {
        // 权威取值：AcpBundledAgentUtils.CLAUDE_ACP_ID（ml-llm jar 常量池）+ AcpAgentId.REGISTRY_PREFIX。
        // 本断言把内联字面量钉住，改错即红（跨版本漂移须重新反编译取证）。
        assertEquals("acp.registry.claude-acp", AcpTransportEpRegistrar.CLAUDE_ACP_ID)
    }
}
