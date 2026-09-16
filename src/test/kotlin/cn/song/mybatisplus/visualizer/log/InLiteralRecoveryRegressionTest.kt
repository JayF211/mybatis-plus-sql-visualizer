package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IN 字面量 SQL 预览参数未识别回归集（T-InParamRec）。
 *
 * 用户实录场景：上游代码把值内联进 SQL 文本（非 PreparedStatement 绑定参数），
 * 控制台只有 SQL 行、无任何 `Setting SQL statement parameter value` 参数行——
 * 条目 `params=[]`、`rawSql` 零 `?`。此前 `LogEntryPreviewOpener`（源码跳转反查入口）
 * 的独立识别/回退分支漏接 `literalRecovery` 字面量恢复 → 预览左栏「无未解析参数」、
 * 值不可编辑；根修 = 该入口收编 `toPreviewResult` 统一识别通道（与日志 tab / 历史 tab 同源）。
 *
 * 用例 SQL 取自用户实录（多行、SkyWalking TID 前缀、IN 列表含空串与 GUID）。
 */
class InLiteralRecoveryRegressionTest {

    private fun feedJdbc(vararg lines: String): List<LogSqlEntry> {
        val p = JdbcTemplateLogParser()
        lines.forEach { p.feed(it) }
        p.closePending()
        return p.takeEntries()
    }

    /** 用户实录行（TID 前缀 + 线程名双段，SQL 跨 7 行）。 */
    private val sqlLines = arrayOf(
        "2026-09-16 16:37:13.635 [TID: 3EE7818C40414B6E9D7C34E7C7797A4F] [http-nio-0.0.0.0-6608-exec-4] DEBUG org.springframework.jdbc.core.JdbcTemplate Line:643 - Executing prepared SQL statement [SELECT",
        "            paramA",
        "            FROM",
        "            tableA",
        "            WHERE deleted = 0",
        "                            AND paramB in",
        "                (''",
        "                         ,'7a6bf5087f314683a49e363713b19f29'",
        "                )",
        "]",
        "2026-09-16 16:37:13.649 [TID: 3EE7818C40414B6E9D7C34E7C7797A4F] [http-nio-0.0.0.0-6608-exec-4] DEBUG org.springframework.jdbc.core.JdbcTemplate Line:958 - Executing prepared SQL update",
    )

    @Test
    fun `实录IN字面量SQL零占位符零绑定行`() {
        val entries = feedJdbc(*sqlLines)
        // 第二行 `Executing prepared SQL update` 是无 [SQL] 文本的头行，不产生条目（既有语义）
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals(SqlSource.JDBC, entry.source())
        assertEquals(0, entry.params().size)
        assertTrue("实录 SQL 本就无 ? 占位符", LogEntryPreviewRecognizerKt.countPlaceholders(entry.rawSql()) == 0L)
        assertTrue("SQL 应完整捕获 IN 列表", entry.rawSql().contains("7a6bf5087f314683a49e363713b19f29"))
    }

    @Test
    fun `统一识别通道字面量恢复产出参数行`() {
        val entries = feedJdbc(*sqlLines)
        val result = LogEntryPreviewRecognizerKt.toPreviewResult(entries[0], false, true)
        assertTrue("字面量恢复应命中（字符串 + 比较语境数值）", result.unresolvedParams.isNotEmpty())
        assertTrue("GUID 值应进入参数预填", result.initialParamValues.containsValue("7a6bf5087f314683a49e363713b19f29"))
        assertTrue("占位符化 SQL 应含 ?", result.sql.contains("?"))
        assertNull("识别成功不应带警告", result.warning)
    }

    @Test
    fun `开关关闭维持现状回退`() {
        val entries = feedJdbc(*sqlLines)
        val result = LogEntryPreviewRecognizerKt.toPreviewResult(entries[0], false, false)
        assertTrue(result.unresolvedParams.isEmpty())
        assertNull("零 ? 零参数回退无警告", result.warning)
    }

    @Test
    fun `往返校验——占位符按提示回填还原原文`() {
        val entries = feedJdbc(*sqlLines)
        val result = LogEntryPreviewRecognizerKt.toPreviewResult(entries[0], false, true)
        // 与 LogEntryPreviewOpener 消费侧同构：按 paramHints 回填渲染应逐字符还原 restoredSql 口径原文
        val restored = buildString {
            var paramIndex = 0
            result.sql.forEach { c ->
                if (c == '?') {
                    val value = result.initialParamValues[result.unresolvedParams[paramIndex]]!!
                    val quote = result.paramHints[paramIndex].quote
                    if (quote == true) append('\'').append(value.replace("'", "''")).append('\'')
                    else append(value.trim())
                    paramIndex++
                } else {
                    append(c)
                }
            }
        }
        assertTrue("往返应还原含原值的 SQL", restored.replace(" ", "").contains("deleted=0"))
        assertTrue(restored.replace(" ", "").contains("'7a6bf5087f314683a49e363713b19f29'"))
    }
}
