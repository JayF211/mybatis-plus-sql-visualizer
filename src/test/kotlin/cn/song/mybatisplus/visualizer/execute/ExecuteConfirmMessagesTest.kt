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

    /**
     * O-64/T-RollbackIdentityParse（P19 正例）：identity 包装回滚产物的确认文案回到既有形态，
     * 不再报「无法解析语句结构——请人工确认影响范围」（keyword 取执行链实传值 `SET`，
     * `SqlTypeClassifier.getFirstKeyword` 对产物首语句的返回）。
     */
    @Test
    fun `identity包装产物_确认文案回既有形态_不再报无法解析`() {
        val product = "SET IDENTITY_INSERT [user] ON;\n" +
            "INSERT INTO [user] ([id], [name]) VALUES (1, 'Tom');\n" +
            "SET IDENTITY_INSERT [user] OFF;"
        assertEquals(legacy("SET"), ExecuteConfirmMessages.build(product, "SET"))
    }

    @Test
    fun `查询语句_既有文案`() {
        // 查询不走确认流，但构建函数对无发现输入恒走既有文案
        assertEquals(legacy("SELECT"), ExecuteConfirmMessages.build("SELECT * FROM t", "SELECT"))
    }

    // ---------- R52：方言能力自检（第三参 dialect） ----------

    @Test
    fun `R52_dialect为null_与旧版逐字节一致`() {
        val sql = "DELETE FROM t WHERE id = 1 LIMIT 10"
        assertEquals(legacy("DELETE"), ExecuteConfirmMessages.build(sql, "DELETE", null))
    }

    @Test
    fun `R52_方言支持该能力_零缺口走既有文案`() {
        val sql = "DELETE FROM t WHERE id = 1 LIMIT 10"
        assertEquals(
            "MySQL 支持 DELETE LIMIT，不应出现能力提示",
            legacy("DELETE"),
            ExecuteConfirmMessages.build(sql, "DELETE", cn.song.mybatisplus.visualizer.sql.MySqlDialect),
        )
    }

    @Test
    fun `R52_方言不支持_确认文案含能力缺口行`() {
        val sql = "DELETE FROM t WHERE id = 1 LIMIT 10"
        val message = ExecuteConfirmMessages.build(sql, "DELETE", cn.song.mybatisplus.visualizer.sql.OracleDialect)
        assertEquals(
            MpSqlVisualizerBundle.message(
                "execute.confirm.precheck.summary",
                "DELETE",
                "• " + MpSqlVisualizerBundle.message("execute.confirm.capability.deleteLimit", "Oracle"),
            ),
            message,
        )
    }

    @Test
    fun `R52_无界写与能力缺口并存_两类发现都在清单`() {
        // 无 WHERE（无界写）+ Oracle 不支持 DELETE LIMIT（能力缺口）
        val message = ExecuteConfirmMessages.build(
            "DELETE FROM t LIMIT 10",
            "DELETE",
            cn.song.mybatisplus.visualizer.sql.OracleDialect,
        )
        assertTrue(message, message.contains(MpSqlVisualizerBundle.message("execute.confirm.unbounded.missingWhere")))
        assertTrue(
            message,
            message.contains(MpSqlVisualizerBundle.message("execute.confirm.capability.deleteLimit", "Oracle")),
        )
    }

    /**
     * R46×R52 同链联动断言：R46 的 Schema 发现行（additionalFindings）与 R52 的方言能力缺口
     * 由**同一个确认消息**承载——两类发现同进一份清单，且抬头为通用预检摘要（不冒用无界写抬头）。
     */
    @Test
    fun `R46与R52同链_两类发现同进一份确认文案`() {
        val schemaLine = "• " + MpSqlVisualizerBundle.message(
            "schema.check.unknownColumnSuggestion", "user_nam", "t_user", "user_name",
        )
        val message = ExecuteConfirmMessages.build(
            "DELETE FROM t_user WHERE user_nam = 'x' LIMIT 10",
            "DELETE",
            cn.song.mybatisplus.visualizer.sql.OracleDialect,
            listOf(schemaLine),
        )
        assertTrue("R46 发现应在文案内", message.contains(schemaLine))
        assertTrue(
            "R52 能力缺口应在同一文案内",
            message.contains(MpSqlVisualizerBundle.message("execute.confirm.capability.deleteLimit", "Oracle")),
        )
        // 抬头用 precheck.summary 的**前缀**（占位符前的固定文案）判定——{1} 是发现清单本体
        val precheckHeader = MpSqlVisualizerBundle
            .message("execute.confirm.precheck.summary", "DELETE", "PLACEHOLDER")
            .substringBefore("PLACEHOLDER")
        assertTrue("两类发现共存时应走通用预检抬头", message.startsWith(precheckHeader))
    }
}
