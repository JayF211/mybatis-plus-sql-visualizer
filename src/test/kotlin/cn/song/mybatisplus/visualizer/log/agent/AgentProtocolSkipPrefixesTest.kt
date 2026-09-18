package cn.song.mybatisplus.visualizer.log.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-SourceJumpFunnel 协议扩展单测：CONFIG body 追加 skipPrefixes（列尾追加形态——
 * 旧 agent 的 contains 极简解析只认 disabled/desensitize 键，未知键天然忽略，双向兼容锁定）。
 */
class AgentProtocolSkipPrefixesTest {

    @Test
    fun test空前缀列表_退化为既有两键body() {
        assertEquals(
            """{"disabled":false,"desensitize":true}""",
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
}
