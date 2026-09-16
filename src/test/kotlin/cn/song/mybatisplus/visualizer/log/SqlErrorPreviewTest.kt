package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-M55-02：执行报错全文进预览警示槽测试——[LogSqlEntry.toPreviewResult] 在
 * [LogSqlEntry.errorText] 非空时并入 warning（meta 行红色 + 横幅 + 「复制错误」按钮的全文来源）；
 * 无错误条目行为零变化（warning 不被改写）。
 */
class SqlErrorPreviewTest {

    private val err = "org.springframework.dao.DuplicateKeyException: ### Error updating database.\n### SQL: INSERT INTO t_user(name) VALUES(?)"

    private fun entry(errorText: String? = null) = LogSqlEntry(
        rawSql = "INSERT INTO t_user(name) VALUES('Tom')",
        restoredSql = "INSERT INTO t_user(name) VALUES('Tom')",
        params = emptyList(),
        errorText = errorText,
    )

    @Test
    fun `errorText并入warning_全文保留`() {
        val result = entry(err).toPreviewResult(formatSql = false, literalRecovery = false)
        assertTrue("warning 应包含执行报错全文", result.warning!!.contains(err))
        assertTrue("warning 带执行报错前缀", result.warning.startsWith("执行报错：") || result.warning.startsWith("Execution error:"))
    }

    @Test
    fun `无错误_warning不被改写`() {
        val result = entry().toPreviewResult(formatSql = false, literalRecovery = false)
        assertNull("无错误条目零占位符场景无警示", result.warning)
    }

    @Test
    fun `错误文本为空串_等同无错误`() {
        val result = entry("  ").toPreviewResult(formatSql = false, literalRecovery = false)
        assertNull(result.warning)
    }

    @Test
    fun `既有未还原警示与报错并存_报错在前既有警示保留`() {
        // 数量不匹配（1 参 2 `?`）→ 内部 warning（参数未还原引导）；叠加 errorText
        val e = LogSqlEntry(
            rawSql = "SELECT * FROM t WHERE a = ? AND b = ?",
            restoredSql = "SELECT * FROM t WHERE a = 1 AND b = ?",
            params = listOf(LogSqlParam("1", "Long")),
            errorText = err,
        )
        val warning = e.toPreviewResult(formatSql = false, literalRecovery = false).warning!!
        assertTrue("报错在前（首要信息先读）", warning.startsWith("执行报错：") || warning.startsWith("Execution error:"))
        assertTrue("报错全文保留", warning.contains(err))
        assertTrue("既有警示文本仍在（拼接在报错之后）", warning.length > ("执行报错：" + err).length + 8)
    }
}
