package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字面量参数恢复测试（T-HistParam F4）：单引号字符串字面量 → `?` 占位符化，
 * 空白跳过/注释不识别/上限熔断/运行期逐字符往返。
 */
class LiteralParamRecoveryTest {

    // ------------------------------------------------------------------
    // 识别与改写
    // ------------------------------------------------------------------

    @Test
    fun `用户反馈形态_单字面量恢复`() {
        val sql = "SELECT * FROM tableA WHERE (\"id\" = '3254231110abcd40e2095ba1') ;"
        val recovery = recoverLiteralParams(sql)!!
        assertEquals("字面量原位改写为 ?", "SELECT * FROM tableA WHERE (\"id\" = ?) ;", recovery.placeholderSql)
        assertEquals(listOf("param1"), recovery.unresolvedParams)
        assertEquals(mapOf("param1" to "3254231110abcd40e2095ba1"), recovery.initialValues)
        assertTrue("字符串字面量恒引号提示", recovery.paramHints.all { it.quote == true })
        assertTrue(verifyLiteralRoundTrip(sql, recovery))
    }

    @Test
    fun `多字面量按序恢复`() {
        val sql = "UPDATE t SET status = 'PAID' WHERE id = '7' AND note = 'ok'"
        val recovery = recoverLiteralParams(sql)!!
        assertEquals("UPDATE t SET status = ? WHERE id = ? AND note = ?", recovery.placeholderSql)
        assertEquals(
            mapOf("param1" to "PAID", "param2" to "7", "param3" to "ok"),
            recovery.initialValues,
        )
        assertTrue(verifyLiteralRoundTrip(sql, recovery))
    }

    @Test
    fun `转义引号字面量还原单引号`() {
        val sql = "SELECT * FROM t WHERE note = 'it''s ok' AND a = 'x'"
        val recovery = recoverLiteralParams(sql)!!
        assertEquals("SELECT * FROM t WHERE note = ? AND a = ?", recovery.placeholderSql)
        assertEquals("it's ok", recovery.initialValues["param1"])
        assertTrue(verifyLiteralRoundTrip(sql, recovery))
    }

    @Test
    fun `字面量内问号不干扰恢复`() {
        // 字面量里的 `?` 随字面量整体成为占位符，不产生错位
        val sql = "SELECT * FROM t WHERE note = 'what?'"
        val recovery = recoverLiteralParams(sql)!!
        assertEquals("SELECT * FROM t WHERE note = ?", recovery.placeholderSql)
        assertEquals("what?", recovery.initialValues["param1"])
        assertTrue(verifyLiteralRoundTrip(sql, recovery))
    }

    @Test
    fun `双引号标识符不误伤`() {
        val sql = "SELECT \"id\", \"name\" FROM \"tableA\" WHERE \"id\" = 'v'"
        val recovery = recoverLiteralParams(sql)!!
        assertEquals("SELECT \"id\", \"name\" FROM \"tableA\" WHERE \"id\" = ?", recovery.placeholderSql)
        assertTrue(verifyLiteralRoundTrip(sql, recovery))
    }

    @Test
    fun `空白值字面量跳过不改写`() {
        // '' 与 ' ' 渲染不回原样（面板空值渲染为 ?），必须保留原字面量
        val sql = "SELECT * FROM t WHERE a = '' AND b = ' ' AND c = 'real'"
        val recovery = recoverLiteralParams(sql)!!
        assertEquals("SELECT * FROM t WHERE a = '' AND b = ' ' AND c = ?", recovery.placeholderSql)
        assertEquals(1, recovery.unresolvedParams.size)
        assertTrue(verifyLiteralRoundTrip(sql, recovery))
    }

    @Test
    fun `注释内字面量不识别`() {
        val sql = "-- 注释 'x'\nSELECT /* 'y' */ * FROM t WHERE a = 'z'"
        val recovery = recoverLiteralParams(sql)!!
        assertEquals("-- 注释 'x'\nSELECT /* 'y' */ * FROM t WHERE a = ?", recovery.placeholderSql)
        assertEquals(1, recovery.unresolvedParams.size)
        assertTrue(verifyLiteralRoundTrip(sql, recovery))
    }

    // ------------------------------------------------------------------
    // 熔断与放弃
    // ------------------------------------------------------------------

    @Test
    fun `无字面量返回 null`() {
        assertNull(recoverLiteralParams("SELECT 1 FROM t WHERE id = 7"))
        assertNull(recoverLiteralParams(""))
    }

    @Test
    fun `超上限整体放弃`() {
        val values = (1..(MAX_RECOVERED_PARAMS + 1)).joinToString(", ") { "'v$it'" }
        val sql = "SELECT * FROM t WHERE id IN ($values)"
        assertNull("超过 $MAX_RECOVERED_PARAMS 个字面量应放弃", recoverLiteralParams(sql))
        // 恰好在上限内：正常恢复
        val valuesAtCap = (1..MAX_RECOVERED_PARAMS).joinToString(", ") { "'v$it'" }
        val recovery = recoverLiteralParams("SELECT * FROM t WHERE id IN ($valuesAtCap)")
        assertEquals(MAX_RECOVERED_PARAMS, recovery?.unresolvedParams?.size)
    }

    @Test
    fun `注释内问号致回填错位风险时放弃`() {
        // 预览面板回填按 `?` 字符朴素替换——改写后的 SQL 若残留非参数 `?`（注释内）直接放弃
        val sql = "SELECT * FROM t -- what? \n WHERE a = 'x'"
        assertNull(recoverLiteralParams(sql))
    }

    @Test
    fun `反斜杠转义方言盲区_尾反斜杠歧义片段跳过`() {
        // MySQL `'don\'t'`：扫描器只认 `''` 转义，会错切出尾反斜杠片段——
        // 引号渲染恰能重组原文、往返熔断拦不住，但畸形值成为参数后改值即产出坏 SQL，
        // 故尾反斜杠片段一律跳过；仅剩歧义片段时整体放弃
        assertNull(recoverLiteralParams("SELECT * FROM t WHERE a = 'don\\'t'"))
    }

    @Test
    fun `混合场景_歧义字面量跳过_其余照常恢复且往返一致`() {
        val sql = "SELECT * FROM t WHERE a = 'don\\'t' AND b = 'ok'"
        val recovery = recoverLiteralParams(sql)
        if (recovery != null) {
            assertFalse("歧义片段不得成为参数", recovery.initialValues.values.any { it.endsWith("\\") })
            assertTrue("逐字符往返必须一致", verifyLiteralRoundTrip(sql, recovery))
        }
    }

    // ------------------------------------------------------------------
    // toPreviewResult 接线（F4 → 统一识别通道）
    // ------------------------------------------------------------------

    @Test
    fun `toPreviewResult 字面量条目走恢复通道`() {
        val entry = LogSqlEntry(
            rawSql = "SELECT * FROM tableA WHERE (\"id\" = '3251') ;",
            restoredSql = "SELECT * FROM tableA WHERE (\"id\" = '3251') ;",
            params = emptyList(),
        )
        val result = entry.toPreviewResult(formatSql = false, literalRecovery = true)
        assertEquals("SQL 模型为占位符化形态", "SELECT * FROM tableA WHERE (\"id\" = ?) ;", result.sql)
        assertEquals(listOf("param1"), result.unresolvedParams)
        assertEquals(mapOf("param1" to "3251"), result.initialParamValues)
        assertEquals(listOf(true), result.paramHints.map { it.quote })
        assertNull("恢复成功无警告", result.warning)
    }

    @Test
    fun `toPreviewResult 开关关闭或熔断失败回退现状`() {
        val rawSql = "SELECT * FROM t WHERE a = 'x'"
        val entry = LogSqlEntry(rawSql = rawSql, restoredSql = rawSql, params = emptyList())
        val off = entry.toPreviewResult(formatSql = false, literalRecovery = false)
        assertTrue("开关关闭应回退现状", off.unresolvedParams.isEmpty())
        assertNull("零 ? 回退无警告", off.warning)
        assertEquals("回退为 restoredSql + 终结符", "$rawSql;", off.sql)
    }

    @Test
    fun `toPreviewResult 数量不匹配不走字面量混合恢复`() {
        // 残留 ? 场景维持现状警告分流（unresolvedWarning），字面量恢复不介入
        val entry = LogSqlEntry(
            rawSql = "SELECT * FROM t WHERE a = ? AND b = ?",
            restoredSql = "SELECT * FROM t WHERE a = 1 AND b = ?",
            params = listOf(LogSqlParam("1", "Long")),
        )
        val result = entry.toPreviewResult(formatSql = false, literalRecovery = true)
        assertTrue(result.unresolvedParams.isEmpty())
        assertEquals(
            "数量不匹配警告",
            cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("log.preview.unresolvedWarning"),
            result.warning,
        )
    }
}
