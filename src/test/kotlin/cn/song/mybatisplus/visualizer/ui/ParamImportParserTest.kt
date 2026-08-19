package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P-05：[ParamImportParser] 纯逻辑单测（JSON / CSV / key=value 三路径 + 自动识别 + 边界）。
 *
 * 纯逻辑、无 IDE 依赖，轻量 JUnit 4 测试即可。
 */
class ParamImportParserTest {

    // ------------------------------------------------------------------
    // JSON 路径
    // ------------------------------------------------------------------

    @Test
    fun testParseJsonFlat() {
        // Postman 导出形态：扁平 object，String + Number 值
        val result = ParamImportParser.parse("""{"name":"张三","age":28}""")
        assertEquals(mapOf("name" to "张三", "age" to "28"), result)
    }

    @Test
    fun testParseJsonNestedObjectSkipped() {
        // 嵌套对象 → 跳过该 key（不递归展平）
        val result = ParamImportParser.parse("""{"user":{"name":"张三"}}""")
        assertEquals(emptyMap<String, String>(), result)
    }

    @Test
    fun testParseJsonArraySkipped() {
        // 数组值 → 跳过该 key
        val result = ParamImportParser.parse("""{"ids":[1,2,3]}""")
        assertEquals(emptyMap<String, String>(), result)
    }

    @Test
    fun testParseJsonWithBooleanAndNumber() {
        // 混合类型：String + Boolean + Number
        val result = ParamImportParser.parse("""{"name":"test","active":true,"count":100}""")
        assertEquals(mapOf("name" to "test", "active" to "true", "count" to "100"), result)
    }

    @Test
    fun testParseJsonInsomniaFormat() {
        // Insomnia 导出形态：带缩进换行
        val result = ParamImportParser.parse("""
            {
              "status": 1,
              "keyword": "hello"
            }
        """.trimIndent())
        assertEquals(mapOf("status" to "1", "keyword" to "hello"), result)
    }

    @Test
    fun testParseJsonArrayTopReturnsEmpty() {
        // 顶层数组（[ 开头）→ 无顶层 key → 返回空 map
        val result = ParamImportParser.parse("""[1,2,3]""")
        assertEquals(emptyMap<String, String>(), result)
    }

    // ------------------------------------------------------------------
    // CSV 路径
    // ------------------------------------------------------------------

    @Test
    fun testParseCsvStandard() {
        // DBeaver 导出形态：首行 header + 第二行值
        val result = ParamImportParser.parse("name,age\n张三,28")
        assertEquals(mapOf("name" to "张三", "age" to "28"), result)
    }

    @Test
    fun testParseCsvValueWithCommaQuoted() {
        // RFC 4180 最小兼容：引号包裹含逗号值（三引号字符串内 \n 为字面量，须用真实换行）
        val result = ParamImportParser.parse("""
"name","full name"
"张三","Li, Si"
        """.trimIndent())
        assertEquals(mapOf("name" to "张三", "full name" to "Li, Si"), result)
    }

    @Test
    fun testParseCsvSingleHeaderRowReturnsEmpty() {
        // 仅首行无第二行 → 返回空 map
        val result = ParamImportParser.parse("name,age")
        assertEquals(emptyMap<String, String>(), result)
    }

    // ------------------------------------------------------------------
    // key=value 路径
    // ------------------------------------------------------------------

    @Test
    fun testParseKeyValueStandard() {
        val result = ParamImportParser.parse("name=张三\nage=28")
        assertEquals(mapOf("name" to "张三", "age" to "28"), result)
    }

    @Test
    fun testParseKeyValueUrlQueryString() {
        // URL query string：`&` 视为分隔符
        val result = ParamImportParser.parse("name=张三&age=28")
        assertEquals(mapOf("name" to "张三", "age" to "28"), result)
    }

    @Test
    fun testParseKeyValueMixedSeparators() {
        // 混合分隔符：换行 + &
        val result = ParamImportParser.parse("name=张三\nage=28&status=1")
        assertEquals(mapOf("name" to "张三", "age" to "28", "status" to "1"), result)
    }

    @Test
    fun testParseKeyValueIgnoresLinesWithoutEquals() {
        // 无 = 的行丢弃
        val result = ParamImportParser.parse("invalid line")
        assertEquals(emptyMap<String, String>(), result)
    }

    @Test
    fun testParseKeyValueFirstEqualsSplits() {
        // 值含 = → 第一个 = 分割
        val result = ParamImportParser.parse("url=http://a.com?x=1")
        assertEquals(mapOf("url" to "http://a.com?x=1"), result)
    }

    @Test
    fun testParseKeyValueTrimsWhitespace() {
        // 键 / 值两侧空白 trim
        val result = ParamImportParser.parse("  name  =  张三  ")
        assertEquals(mapOf("name" to "张三"), result)
    }

    // ------------------------------------------------------------------
    // 自动识别
    // ------------------------------------------------------------------

    @Test
    fun testAutoDetectJson() {
        // { 开头 → parseJson
        val result = ParamImportParser.parse("""{"a":1}""")
        assertEquals(mapOf("a" to "1"), result)
    }

    @Test
    fun testAutoDetectCsv() {
        // 含换行 + 首行含 , → parseCsv
        val result = ParamImportParser.parse("a,b\n1,2")
        assertEquals(mapOf("a" to "1", "b" to "2"), result)
    }

    @Test
    fun testAutoDetectKeyValue() {
        // 单行 key=value → parseKeyValue
        val result = ParamImportParser.parse("a=1")
        assertEquals(mapOf("a" to "1"), result)
    }

    // ------------------------------------------------------------------
    // 边界
    // ------------------------------------------------------------------

    @Test
    fun testEmptyInput() {
        assertEquals(emptyMap<String, String>(), ParamImportParser.parse(""))
    }

    @Test
    fun testBlankInput() {
        assertEquals(emptyMap<String, String>(), ParamImportParser.parse("   \n  "))
    }

    @Test
    fun testEmptyValue() {
        // 值为空字符串
        val result = ParamImportParser.parse("name=")
        assertEquals(mapOf("name" to ""), result)
    }

    @Test
    fun testJsonNullValueSkipped() {
        // JSON null 值 → 跳过该 key
        val result = ParamImportParser.parse("""{"name":null,"age":10}""")
        assertEquals(mapOf("age" to "10"), result)
    }
}
