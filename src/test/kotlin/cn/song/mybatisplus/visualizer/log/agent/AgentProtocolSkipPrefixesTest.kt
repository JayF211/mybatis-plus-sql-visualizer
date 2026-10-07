package cn.song.mybatisplus.visualizer.log.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-SourceJumpFunnel 协议扩展单测：CONFIG body 追加 skipPrefixes（列尾追加形态——
 * 旧 agent 的 contains 极简解析只认 disabled/desensitize 键，未知键天然忽略，双向兼容锁定）。
 * T-StackHoverUi：body 列尾再恒带 `"stackFrames":<int>`（本文件各精确断言已含该尾键）。
 */
class AgentProtocolSkipPrefixesTest {

    @Test
    fun test空前缀列表_退化为既有两键body() {
        assertEquals(
            """{"disabled":false,"desensitize":true,"stackFrames":24}""",
            AgentProtocol.configBody(false, true, emptyList()),
        )
    }

    @Test
    fun test带前缀_追加skipPrefixes键且布尔键序不变() {
        val body = AgentProtocol.configBody(false, true, listOf("com.corp.engine.", " com.corp2. "))
        assertTrue(body.startsWith("""{"disabled":false,"desensitize":true"""))
        assertTrue(body.contains("\"skipPrefixes\":[\"com.corp.engine.\",\"com.corp2.\"]"))
    }

    @Test
    fun test前缀含引号与反斜杠_最小转义() {
        val body = AgentProtocol.configBody(true, false, listOf("a\"b\\c"))
        assertTrue(body.contains("\"skipPrefixes\":[\"a\\\"b\\\\c\"]"))
    }

    // ---------- T-ToolSiteBizFrame：bizJarPrefixes 追加键 ----------

    @Test
    fun test反向名单_列尾追加且空列表省略键() {
        // 空反向名单 → 退化为 skipPrefixes-only 形态（列尾恒带 stackFrames）
        assertEquals(
            """{"disabled":false,"desensitize":true,"skipPrefixes":["com.corp.engine."],"stackFrames":24}""",
            AgentProtocol.configBody(false, true, listOf("com.corp.engine."), emptyList()),
        )
        // 两键齐发：skipPrefixes 在前、bizJarPrefixes 列尾追加，布尔键序不变
        val body = AgentProtocol.configBody(false, true, listOf("com.corp.engine."), listOf("com.corp.shared.util."))
        assertEquals(
            """{"disabled":false,"desensitize":true,"skipPrefixes":["com.corp.engine."],"""
                + """"bizJarPrefixes":["com.corp.shared.util."],"stackFrames":24}""",
            body,
        )
        // 仅反向名单：skipPrefixes 空省略键，agent 侧缺键=清空语义
        assertEquals(
            """{"disabled":true,"desensitize":false,"bizJarPrefixes":["com.corp.shared.util."],"stackFrames":24}""",
            AgentProtocol.configBody(true, false, emptyList(), listOf("com.corp.shared.util.")),
        )
    }

    // ---------- T-StackHoverUi：stackFrames 恒带键 ----------

    @Test
    fun test帧数键_显式传参透传() {
        val body = AgentProtocol.configBody(false, true, emptyList(), emptyList(), 48)
        assertEquals("""{"disabled":false,"desensitize":true,"stackFrames":48}""", body)
    }
}
