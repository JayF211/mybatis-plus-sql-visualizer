package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史文件参数载荷编解码测试（T-HistParam F1）：round-trip 保真 + 全部形态异常降级 null。
 */
class SqlLogParamCodecTest {

    @Test
    fun `round-trip 基本形态`() {
        val params = listOf(
            LogSqlParam("3254231110abcd40e2095ba1", "String"),
            LogSqlParam("10", "Long"),
            LogSqlParam(null, "VARCHAR"),
            LogSqlParam("untyped", null),
        )
        val payload = SqlLogParamCodec.encode("SELECT * FROM t WHERE a = ? AND b = ?", params)
        val (rawSql, decoded) = SqlLogParamCodec.decode(payload)!!
        assertEquals("SELECT * FROM t WHERE a = ? AND b = ?", rawSql)
        assertEquals(params, decoded)
    }

    @Test
    fun `值含分隔符与换行等特殊字符无损`() {
        // Base64 载荷必须吞下任意文本：竖线/逗号/注释符/换行/引号转义/unicode
        val tricky = "a|b, c -- d) (''x'')\n第二行\t制表 ünïcode 中文?值"
        val params = listOf(LogSqlParam(tricky, "String"))
        val (_, decoded) = SqlLogParamCodec.decode(SqlLogParamCodec.encode("SELECT '$tricky'", params))!!
        assertEquals(listOf(LogSqlParam(tricky, "String")), decoded)
    }

    @Test
    fun `空参数列表 round-trip`() {
        val payload = SqlLogParamCodec.encode("SELECT 1", emptyList())
        val (rawSql, decoded) = SqlLogParamCodec.decode(payload)!!
        assertEquals("SELECT 1", rawSql)
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `非法 Base64 返回 null`() {
        assertNull(SqlLogParamCodec.decode("not base64 !!"))
    }

    @Test
    fun `JSON 结构不符返回 null`() {
        // 合法 Base64 但内容不是对象 / 缺字段 / 参数元素结构错
        val notObject = java.util.Base64.getEncoder().encodeToString("\"just a string\"".toByteArray())
        assertNull(SqlLogParamCodec.decode(notObject))

        val missingRaw = java.util.Base64.getEncoder().encodeToString("{\"p\":[]}".toByteArray())
        assertNull(SqlLogParamCodec.decode(missingRaw))

        val blankRaw = java.util.Base64.getEncoder().encodeToString("{\"r\":\"  \",\"p\":[]}".toByteArray())
        assertNull(SqlLogParamCodec.decode(blankRaw))

        val badParamShape = java.util.Base64.getEncoder()
            .encodeToString("{\"r\":\"SELECT 1\",\"p\":[\"not-an-array\"]}".toByteArray())
        assertNull(SqlLogParamCodec.decode(badParamShape))
    }

    @Test
    fun `载荷不含 Base64 字符集之外的字节`() {
        // 单行注释行契约：载荷不得含空白/竖线/换行（否则破坏头部解析与 countInFile 判据）
        val payload = SqlLogParamCodec.encode(
            "SELECT ?",
            listOf(LogSqlParam("a|b, c -- d\ne", "String")),
        )
        assertTrue("载荷应匹配 Base64 字符集: $payload", payload.matches(Regex("[A-Za-z0-9+/=]+")))
        assertNotNull(SqlLogParamCodec.decode(payload))
    }

    // ---------- M56 T-M56-00：扩展字段（m/e/q/u） ----------

    @Test
    fun `encodeEntry 扩展字段 round-trip 不丢`() {
        val entry = LogSqlEntry(
            rawSql = "UPDATE t SET a = ?",
            restoredSql = "UPDATE t SET a = 1",
            params = emptyList(),
            mapperStatement = "com.x.mapper.TMapper.updateA",
            errorText = "SQLException: Data too long",
            requestTag = "req-9",
            updateCount = 3L,
        )
        val decoded = SqlLogParamCodec.decode(SqlLogParamCodec.encodeEntry(entry))!!
        assertEquals("UPDATE t SET a = ?", decoded.rawSql)
        assertTrue(decoded.params.isEmpty())
        assertEquals("com.x.mapper.TMapper.updateA", decoded.mapperStatement)
        assertEquals("SQLException: Data too long", decoded.errorText)
        assertEquals("req-9", decoded.requestTag)
        assertEquals(3L, decoded.updateCount)
    }

    @Test
    fun `无扩展字段载荷_解码扩展字段为null`() {
        // 旧 payload（仅 r/p 键）与旧历史文件兼容：扩展字段解码 null 不报错
        val legacy = java.util.Base64.getEncoder()
            .encodeToString("""{"r":"SELECT 1","p":[]}""".toByteArray(Charsets.UTF_8))
        val decoded = SqlLogParamCodec.decode(legacy)!!
        assertEquals("SELECT 1", decoded.rawSql)
        assertNull(decoded.mapperStatement)
        assertNull(decoded.errorText)
        assertNull(decoded.requestTag)
        assertNull(decoded.updateCount)
    }

    @Test
    fun `扩展字段仅非默认落键`() {
        // 无扩展字段的条目：JSON 不得出现 m/e/q/u 键（payload 精简，旧版本插件兼容面最小）
        val payload = SqlLogParamCodec.encode("SELECT 1", emptyList())
        val json = String(java.util.Base64.getDecoder().decode(payload), Charsets.UTF_8)
        assertTrue("不应含扩展键: $json", !json.contains("\"m\"") && !json.contains("\"e\"") &&
            !json.contains("\"q\"") && !json.contains("\"u\""))
    }

    @Test
    fun `扩展键形态异常_降级null不丢条目`() {
        val legacy = java.util.Base64.getEncoder()
            .encodeToString("""{"r":"SELECT 1","p":[],"u":"not-a-number"}""".toByteArray(Charsets.UTF_8))
        val decoded = SqlLogParamCodec.decode(legacy)!!
        assertEquals("SELECT 1", decoded.rawSql)
        assertNull(decoded.updateCount)
    }
}
