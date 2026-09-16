package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.SQLWarning

/**
 * T-NoticeChannel 驱动 notices 消息通道单测（纯逻辑层）：
 * warnings 取尽与保序、clearWarnings 必调、驱动异常降级不连坐、notices 节渲染、
 * 空 notices 输出逐字节与现状一致（防回归硬门禁）。
 *
 * 「执行失败路径 notices 丢弃」由结构保证：RawResult（含 notices）只在 executeQuery 成功返回
 * 时存在，executeExplain 失败直接 throwExpected——无载体即无泄漏，不另设用例。
 */
class McpNoticeChannelTest {

    /** 采集面构造辅助（显式类型，避免中缀 to 在 vararg 场景的型推断歧义）。 */
    private fun surface(all: () -> List<*>?, clear: () -> Unit): Pair<() -> List<*>?, () -> Unit> = all to clear

    // ------------------------------------------------------------------
    // warnings 取尽内核（drainWarnings）
    // ------------------------------------------------------------------

    @Test
    fun `取尽_语句级与连接级都收且按面保序`() {
        val stmtWarnings = listOf(SQLWarning("relation already exists, skipping"), SQLWarning("analyze hint"))
        val connWarnings = listOf(SQLWarning("session timezone notice"))
        val stmtClearCalls = mutableListOf<Boolean>()
        val connClearCalls = mutableListOf<Boolean>()
        val notices = drainWarnings(
            surface({ stmtWarnings }, { stmtClearCalls.add(true) }),
            surface({ connWarnings }, { connClearCalls.add(true) }),
        )
        assertEquals(
            listOf("relation already exists, skipping", "analyze hint", "session timezone notice"),
            notices,
        )
        assertEquals("语句级 clearWarnings 必调", listOf(true), stmtClearCalls)
        assertEquals("连接级 clearWarnings 必调", listOf(true), connClearCalls)
    }

    @Test
    fun `getAllWarnings抛异常_该面降级为空_不连坐另一面`() {
        val connWarnings = listOf(SQLWarning("c1"))
        val notices = drainWarnings(
            surface({ throw UnsupportedOperationException("driver refuses warnings") }, { }),
            surface({ connWarnings }, { }),
        )
        assertEquals("个别驱动抛 UnsupportedOperationException 只影响自身采集面", listOf("c1"), notices)
        assertTrue("全部面失败 = 空 notices", drainWarnings(surface({ throw IllegalStateException() }, { })).isEmpty())
    }

    @Test
    fun `clearWarnings抛异常_不丢已取到的消息`() {
        val stmtWarnings = listOf(SQLWarning("kept"))
        val notices = drainWarnings(
            surface({ stmtWarnings }, { throw IllegalStateException("clear failed") }),
        )
        assertEquals(listOf("kept"), notices)
    }

    @Test
    fun `空消息与空白消息_过滤不占行`() {
        val notices = drainWarnings(
            surface({
                listOf(
                    SQLWarning("real"),
                    SQLWarning(""),
                    SQLWarning("   "),
                    SQLWarning(null as String?),
                    null,
                    "plain-string-entry",
                )
            }, { }),
        )
        assertEquals(listOf("real", "plain-string-entry"), notices)
    }

    // ------------------------------------------------------------------
    // notices 节渲染（formatNotices）
    // ------------------------------------------------------------------

    @Test
    fun `notices节_每条一行`() {
        assertEquals(
            "\n\nnotices:\n- n1\n- n2",
            McpExplainFormatter.formatNotices(listOf("n1", "n2")),
        )
    }

    @Test
    fun `notices节_单条内换行压平不占行`() {
        assertEquals(
            "\n\nnotices:\n- line1 line2",
            McpExplainFormatter.formatNotices(listOf("line1\nline2")),
        )
    }

    @Test
    fun `空notices_格式化为空串`() {
        assertEquals("", McpExplainFormatter.formatNotices(emptyList()))
    }

    // ------------------------------------------------------------------
    // 防回归硬门禁：空 notices 时输出逐字节与现状一致
    // ------------------------------------------------------------------

    @Test
    fun `空notices_输出逐字节与现状一致`() {
        val raw = McpExplainFormatter.RawResult(
            headers = listOf("id", "plan"),
            rows = listOf(listOf("1", "SIMPLE")),
        )
        // 默认构造（无 notices 字段形态）与显式空清单构造逐字节一致
        assertEquals(McpExplainFormatter.format(raw), McpExplainFormatter.format(raw.copy(notices = emptyList())))
        // 工具拼接形态（ExplainTool：format + 方言行 + notices 节）：空 notices 零追加
        val legacyOutput = McpExplainFormatter.format(raw) + "\n\ndialect: PostgreSQL"
        val currentOutput = legacyOutput + McpExplainFormatter.formatNotices(raw.notices)
        assertEquals(legacyOutput, currentOutput)
    }
}
