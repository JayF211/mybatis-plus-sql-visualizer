package cn.song.mybatisplus.visualizer.ui;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

import java.util.Map;

/**
 * P-05：[ParamImportParser] 纯逻辑单测（JSON / CSV / key=value 三路径 + 自动识别 + 边界）。
 *
 * 纯逻辑、无 IDE 依赖，轻量 JUnit 4 测试即可。
 */
public class ParamImportParserTest {

    // ------------------------------------------------------------------
    // JSON 路径
    // ------------------------------------------------------------------

    @Test
    public void testParseJsonFlat() throws Exception {
        // Postman 导出形态：扁平 object，String + Number 值
        Map<String, String> result = ParamImportParser.INSTANCE.parse("{\"name\":\"张三\",\"age\":28}");
        assertEquals(Map.of("name", "张三", "age", "28"), result);
    }

    @Test
    public void testParseJsonNestedObjectSkipped() throws Exception {
        // 嵌套对象 → 跳过该 key（不递归展平）
        Map<String, String> result = ParamImportParser.INSTANCE.parse("{\"user\":{\"name\":\"张三\"}}");
        assertEquals(Map.of(), result);
    }

    @Test
    public void testParseJsonArraySkipped() throws Exception {
        // 数组值 → 跳过该 key
        Map<String, String> result = ParamImportParser.INSTANCE.parse("{\"ids\":[1,2,3]}");
        assertEquals(Map.of(), result);
    }

    @Test
    public void testParseJsonWithBooleanAndNumber() throws Exception {
        // 混合类型：String + Boolean + Number
        Map<String, String> result = ParamImportParser.INSTANCE.parse("{\"name\":\"test\",\"active\":true,\"count\":100}");
        assertEquals(Map.of("name", "test", "active", "true", "count", "100"), result);
    }

    @Test
    public void testParseJsonInsomniaFormat() throws Exception {
        // Insomnia 导出形态：带缩进换行
        Map<String, String> result = ParamImportParser.INSTANCE.parse("""
        {
          "status": 1,
          "keyword": "hello"
        }
        """.stripIndent());
        assertEquals(Map.of("status", "1", "keyword", "hello"), result);
    }

    @Test
    public void testParseJsonArrayTopReturnsEmpty() throws Exception {
        // 顶层数组（[ 开头）→ 无顶层 key → 返回空 map
        Map<String, String> result = ParamImportParser.INSTANCE.parse("[1,2,3]");
        assertEquals(Map.of(), result);
    }

    // ------------------------------------------------------------------
    // CSV 路径
    // ------------------------------------------------------------------

    @Test
    public void testParseCsvStandard() throws Exception {
        // DBeaver 导出形态：首行 header + 第二行值
        Map<String, String> result = ParamImportParser.INSTANCE.parse("name,age\n张三,28");
        assertEquals(Map.of("name", "张三", "age", "28"), result);
    }

    @Test
    public void testParseCsvValueWithCommaQuoted() throws Exception {
        // RFC 4180 最小兼容：引号包裹含逗号值（三引号字符串内 \n 为字面量，须用真实换行）
        Map<String, String> result = ParamImportParser.INSTANCE.parse("""
        "name","full name"
        "张三","Li, Si"
        """.stripIndent());
        assertEquals(Map.of("name", "张三", "full name", "Li, Si"), result);
    }

    @Test
    public void testParseCsvSingleHeaderRowReturnsEmpty() throws Exception {
        // 仅首行无第二行 → 返回空 map
        Map<String, String> result = ParamImportParser.INSTANCE.parse("name,age");
        assertEquals(Map.of(), result);
    }

    // ------------------------------------------------------------------
    // key=value 路径
    // ------------------------------------------------------------------

    @Test
    public void testParseKeyValueStandard() throws Exception {
        Map<String, String> result = ParamImportParser.INSTANCE.parse("name=张三\nage=28");
        assertEquals(Map.of("name", "张三", "age", "28"), result);
    }

    @Test
    public void testParseKeyValueUrlQueryString() throws Exception {
        // URL query string：`&` 视为分隔符
        Map<String, String> result = ParamImportParser.INSTANCE.parse("name=张三&age=28");
        assertEquals(Map.of("name", "张三", "age", "28"), result);
    }

    @Test
    public void testParseKeyValueMixedSeparators() throws Exception {
        // 混合分隔符：换行 + &
        Map<String, String> result = ParamImportParser.INSTANCE.parse("name=张三\nage=28&status=1");
        assertEquals(Map.of("name", "张三", "age", "28", "status", "1"), result);
    }

    @Test
    public void testParseKeyValueIgnoresLinesWithoutEquals() throws Exception {
        // 无 = 的行丢弃
        Map<String, String> result = ParamImportParser.INSTANCE.parse("invalid line");
        assertEquals(Map.of(), result);
    }

    @Test
    public void testParseKeyValueFirstEqualsSplits() throws Exception {
        // 值含 = → 第一个 = 分割
        Map<String, String> result = ParamImportParser.INSTANCE.parse("url=http://a.com?x=1");
        assertEquals(Map.of("url", "http://a.com?x=1"), result);
    }

    @Test
    public void testParseKeyValueTrimsWhitespace() throws Exception {
        // 键 / 值两侧空白 trim
        Map<String, String> result = ParamImportParser.INSTANCE.parse("  name  =  张三  ");
        assertEquals(Map.of("name", "张三"), result);
    }

    // ------------------------------------------------------------------
    // 自动识别
    // ------------------------------------------------------------------

    @Test
    public void testAutoDetectJson() throws Exception {
        // { 开头 → parseJson
        Map<String, String> result = ParamImportParser.INSTANCE.parse("{\"a\":1}");
        assertEquals(Map.of("a", "1"), result);
    }

    @Test
    public void testAutoDetectCsv() throws Exception {
        // 含换行 + 首行含 , → parseCsv
        Map<String, String> result = ParamImportParser.INSTANCE.parse("a,b\n1,2");
        assertEquals(Map.of("a", "1", "b", "2"), result);
    }

    @Test
    public void testAutoDetectKeyValue() throws Exception {
        // 单行 key=value → parseKeyValue
        Map<String, String> result = ParamImportParser.INSTANCE.parse("a=1");
        assertEquals(Map.of("a", "1"), result);
    }

    // ------------------------------------------------------------------
    // 边界
    // ------------------------------------------------------------------

    @Test
    public void testEmptyInput() throws Exception {
        assertEquals(Map.of(), ParamImportParser.INSTANCE.parse(""));
    }

    @Test
    public void testBlankInput() throws Exception {
        assertEquals(Map.of(), ParamImportParser.INSTANCE.parse("   \n  "));
    }

    @Test
    public void testEmptyValue() throws Exception {
        // 值为空字符串
        Map<String, String> result = ParamImportParser.INSTANCE.parse("name=");
        assertEquals(Map.of("name", ""), result);
    }

    @Test
    public void testJsonNullValueSkipped() throws Exception {
        // JSON null 值 → 跳过该 key
        Map<String, String> result = ParamImportParser.INSTANCE.parse("{\"name\":null,\"age\":10}");
        assertEquals(Map.of("age", "10"), result);
    }
}
