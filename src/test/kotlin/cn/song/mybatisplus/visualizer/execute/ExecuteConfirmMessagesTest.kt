package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行确认消息构建测试（T-UnboundedWriteGuard / T-UW-02/04）：
 *  - 无危险发现 → 既有文案逐字节不变（确认流零变化红线）；
 *  - 有危险发现 → 摘要 + 按类别去重清单（每类一行）；
 *  - 仅子查询（提示型）→ 既有文案（不算危险）。
 * 普通 JUnit——bundle 自管理（测试环境兜底中文，与默认值一致）。
 */
class ExecuteConfirmMessagesTest {

    private fun legacy(keyword: String) = MpSqlVisualizerBundle.message("execute.confirm.dml.message", keyword)

    @Test
    fun `有界WHERE_既有文案零变化`() {
        assertEquals(legacy("DELETE"), ExecuteConfirmMessages.build("DELETE FROM t WHERE id = 1", "DELETE"))
        assertEquals(legacy("UPDATE"), ExecuteConfirmMessages.build("UPDATE t SET a = 1 WHERE id = 2", "UPDATE"))
    }

    @Test
    fun `仅子查询_不算危险走既有文案`() {
        assertEquals(
            legacy("DELETE"),
            ExecuteConfirmMessages.build("DELETE FROM t WHERE id IN (SELECT id FROM u)", "DELETE"),
        )
    }

    @Test
    fun `无WHERE_消息列出发现`() {
        val message = ExecuteConfirmMessages.build("DELETE FROM t", "DELETE")
        val expected = MpSqlVisualizerBundle.message(
            "execute.confirm.unbounded.summary", "DELETE",
            "• " + MpSqlVisualizerBundle.message("execute.confirm.unbounded.missingWhere"),
        )
        assertEquals(expected, message)
    }

    @Test
    fun `多发现_类别去重每类一行`() {
        // 无 WHERE 与 LIKE 纯通配同句（OR 形态聚合恒真 + LIKE 通配 + 子查询）
        val message = ExecuteConfirmMessages.build(
            "DELETE FROM t WHERE name LIKE '%' OR 1 = 1",
            "DELETE",
        )
        assertTrue(message, message.contains(MpSqlVisualizerBundle.message("execute.confirm.unbounded.orTautology")))
        assertTrue(message, message.contains(MpSqlVisualizerBundle.message("execute.confirm.unbounded.likeWildcard")))
    }

    @Test
    fun `解析失败_列出人工确认提示`() {
        val message = ExecuteConfirmMessages.build("DELETE FROM t WHERE", "DELETE")
        assertTrue(message, message.contains(MpSqlVisualizerBundle.message("execute.confirm.unbounded.parseFailed")))
    }

    @Test
    fun `查询语句_既有文案`() {
        // 查询不走确认流，但构建函数对无发现输入恒走既有文案
        assertEquals(legacy("SELECT"), ExecuteConfirmMessages.build("SELECT * FROM t", "SELECT"))
    }
}
