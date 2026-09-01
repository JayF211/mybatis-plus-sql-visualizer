package cn.song.mybatisplus.visualizer.share.format;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * HTML 报告格式化器单元测试（F-8 Phase B）。
 */
public class HtmlReportFormatterTest {

    @Test
    public void format_生成完整HTML结构() {
        String sql = "SELECT id FROM user";
        String html = HtmlReportFormatter.INSTANCE.format(sql, List.of(), Map.of(), "user", "MySQL");
        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("</html>"));
        // SQL 被 span 包裹，验证去除标签后包含原文
        String stripped = html.replaceAll("<[^>]*>", "");
        assertTrue(stripped.contains(sql));
        assertTrue(stripped.contains("MySQL"));
        assertTrue(stripped.contains("user"));
    }

    @Test
    public void format_关键字高亮标签存在() {
        String html = HtmlReportFormatter.INSTANCE.format("SELECT id FROM user WHERE name = 'tom'",
                List.of(), Map.of(), "user", "MySQL");
        // 关键字 SELECT / FROM / WHERE 应有 kw 类
        assertTrue(html.contains("class=\"kw\""));
        // 字符串 'tom' 应有 str 类
        assertTrue(html.contains("class=\"str\""));
        // 表名 user 应有 tbl 类
        assertTrue(html.contains("class=\"tbl\""));
    }

    @Test
    public void format_参数对照表_有参数时生成() {
        String html = HtmlReportFormatter.INSTANCE.format(
                "SELECT id FROM user WHERE name = ? AND age = ?",
                List.of("name", "age"), Map.of("name", "tom", "age", "25"), "user", null);
        assertTrue(html.contains("Parameters"));
        assertTrue(html.contains("name"));
        assertTrue(html.contains("tom"));
        assertTrue(html.contains("age"));
        assertTrue(html.contains("25"));
    }

    @Test
    public void format_参数对照表_无参数时省略() {
        String html = HtmlReportFormatter.INSTANCE.format("SELECT id FROM user",
                List.of(), Map.of(), "user", null);
        assertFalse(html.contains("Parameters"));
    }

    @Test
    public void format_HTML特殊字符_正确转义() {
        String html = HtmlReportFormatter.INSTANCE.format("SELECT id FROM user WHERE name = '<script>'",
                List.of(), Map.of(), "user", null);
        // < 应转义为 &lt;
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    public void format_null表名和dialect_省略对应元信息() {
        String sql = "SELECT id FROM user";
        String html = HtmlReportFormatter.INSTANCE.format(sql, List.of(), Map.of(), null, null);
        assertTrue(html.contains("<!DOCTYPE html>"));
        String stripped = html.replaceAll("<[^>]*>", "");
        assertTrue(stripped.contains(sql));
    }

    @Test
    public void format_注释高亮_cmt类存在() {
        String html = HtmlReportFormatter.INSTANCE.format("SELECT id FROM user -- get all",
                List.of(), Map.of(), "user", null);
        assertTrue(html.contains("class=\"cmt\""));
    }
}
