package cn.song.mybatisplus.visualizer.share.format;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import cn.song.mybatisplus.visualizer.sql.ParamHint;

import java.util.List;
import java.util.Map;

/**
 * 可执行 SQL 格式化器单元测试（F-8 Phase C）。
 */
public class ExecutableSqlFormatterTest {

    @Test
    public void format_数值参数_不加引号() throws Exception {
        String sql = "SELECT id FROM tbl WHERE id = ? AND age > ?";
        String result = ExecutableSqlFormatter.format(sql, List.of("id", "age"),
                Map.of("id", "1001", "age", "18"),
                List.of(new ParamHint(Boolean.FALSE, null, false), new ParamHint(Boolean.FALSE, null, false)));
        assertEquals("SELECT id FROM tbl WHERE id = 1001 AND age > 18", result);
    }

    @Test
    public void format_字符串参数_加引号() throws Exception {
        String sql = "SELECT id FROM tbl WHERE name = ? AND city = ?";
        String result = ExecutableSqlFormatter.format(sql, List.of("name", "city"),
                Map.of("name", "John", "city", "Beijing"),
                List.of(new ParamHint(Boolean.TRUE, null, false), new ParamHint(Boolean.TRUE, null, false)));
        assertEquals("SELECT id FROM tbl WHERE name = 'John' AND city = 'Beijing'", result);
    }

    @Test
    public void format_空值保留占位符() throws Exception {
        String sql = "SELECT id FROM tbl WHERE id = ? AND name = ?";
        String result = ExecutableSqlFormatter.format(sql, List.of("id", "name"),
                Map.of("id", "1001"),
                List.of(new ParamHint(Boolean.FALSE, null, false), new ParamHint(Boolean.TRUE, null, false)));
        assertEquals("SELECT id FROM tbl WHERE id = 1001 AND name = ?", result);
    }

    @Test
    public void format_单引号转义防注入() throws Exception {
        String sql = "SELECT id FROM tbl WHERE name = ?";
        String result = ExecutableSqlFormatter.format(sql, List.of("name"),
                Map.of("name", "O'Brien"),
                List.of(new ParamHint(Boolean.TRUE, null, false)));
        assertEquals("SELECT id FROM tbl WHERE name = 'O''Brien'", result);
    }

    @Test
    public void format_无参数原样返回() throws Exception {
        String sql = "SELECT id FROM tbl";
        String result = ExecutableSqlFormatter.format(sql, List.of(), Map.of());
        assertEquals("SELECT id FROM tbl", result);
    }

    @Test
    public void formatAsMrComment_生成评论格式_含前缀和来源() throws Exception {
        String sql = "SELECT id FROM user WHERE name = 'tom'";
        String comment = ExecutableSqlFormatter.formatAsMrComment(sql, List.of(), Map.of(),
                "UserService.java:42", "[SQL Review]", List.of());
        assertTrue(comment.contains("[SQL Review]"));
        assertTrue(comment.contains("UserService.java:42"));
        assertTrue(comment.contains("```sql"));
        assertTrue(comment.contains(sql));
        assertTrue(comment.contains("```"));
    }

    @Test
    public void formatAsMrComment_无来源_省略来源行() throws Exception {
        String sql = "SELECT id FROM user WHERE name = 'tom'";
        String comment = ExecutableSqlFormatter.formatAsMrComment(sql, List.of(), Map.of(), null);
        assertTrue(comment.startsWith("[SQL Review]"));
        assertTrue(!comment.contains("[SQL Review] null"));
    }

    @Test
    public void formatAsMrComment_自定义前缀() throws Exception {
        String sql = "SELECT id FROM tbl";
        String comment = ExecutableSqlFormatter.formatAsMrComment(sql, List.of(), Map.of(), null,
                "[DB Review]", List.of());
        assertTrue(comment.startsWith("[DB Review]"));
    }
}
