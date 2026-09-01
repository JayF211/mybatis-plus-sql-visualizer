package cn.song.mybatisplus.visualizer.share.format;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * Markdown 格式化器单元测试（F-8 Phase A）。
 */
public class MarkdownFormatterTest {

    @Test
    public void format_普通SQL_生成代码块加元信息() {
        String sql = "SELECT id, name FROM user WHERE name = 'zhangsan'";
        String md = MarkdownFormatter.format(sql, "user", "MySQL", "UserService.java:42");
        assertTrue(md.contains("```sql"));
        assertTrue(md.contains(sql));
        assertTrue(md.contains("```"));
        assertTrue(md.contains("user"));
        assertTrue(md.contains("MySQL"));
        assertTrue(md.contains("UserService.java:42"));
    }

    @Test
    public void format_无参数SQL_省略参数对照() {
        String sql = "SELECT id FROM user";
        String md = MarkdownFormatter.format(sql, List.of(), Map.of(), "user", "MySQL", null);
        assertTrue(md.contains("```sql"));
        assertTrue(md.contains(sql));
        // 无参数时不应出现参数对照表
        assertTrue(!md.contains("-- 参数对照"));
    }

    @Test
    public void format_有参数SQL_包含参数对照() {
        String sql = "SELECT id FROM user WHERE name = ? AND age = ?";
        String md = MarkdownFormatter.format(sql, List.of("name", "age"),
                Map.of("name", "zhangsan", "age", "25"), "user", "MySQL", null);
        assertTrue(md.contains("```sql"));
        // 参数对照
        assertTrue(md.contains("zhangsan"));
        assertTrue(md.contains("25"));
    }

    @Test
    public void format_SQL含特殊字符_原样保留() {
        String sql = "SELECT id FROM user WHERE name = 'O''Brien' -- comment";
        String md = MarkdownFormatter.format(sql, "user", "MySQL", null);
        assertTrue(md.contains("O''Brien"));
    }

    @Test
    public void format_空source和dialect_省略对应元信息() {
        String sql = "SELECT id FROM user";
        String md = MarkdownFormatter.format(sql, "user", null, null);
        assertTrue(md.contains("```sql"));
        assertTrue(md.contains(sql));
    }

    @Test
    public void formatAsTable_生成Markdown表格() {
        String sql = "SELECT id FROM user WHERE name = ?";
        String md = MarkdownFormatter.formatAsTable(sql, List.of("name"),
                Map.of("name", "zhangsan"), "user", "MySQL", "UserService.java:42");
        // 表格应有 | 分隔符
        assertTrue(md.contains("|"));
        assertTrue(md.contains("---"));
        assertTrue(md.contains("SQL"));
        assertTrue(md.contains("参数"));
    }

    @Test
    public void formatAsTable_多行SQL_换行替换为br() {
        String sql = "SELECT\n    t.id,\n    t.name\nFROM user";
        String md = MarkdownFormatter.formatAsTable(sql, List.of(), Map.of(), "user", "MySQL", null);
        // 换行应替换为 <br>，不应保留原 \n
        assertTrue(md.contains("<br>"));
        assertTrue(!md.contains("\n    t.id"));
        // 表格行数：表头1 + 分隔符1 + 数据行1 = 3 行（不含末尾换行）
        String[] lines = md.split("\n");
        assertEquals(3, lines.length);
    }

    @Test
    public void formatAsTable_SQL含管道符_转义为竖线() {
        String sql = "SELECT id FROM user WHERE a = 1 | b = 2";
        String md = MarkdownFormatter.formatAsTable(sql, List.of(), Map.of(), "user", "MySQL", null);
        // | 应被转义为 \|
        assertTrue(md.contains("\\|"));
    }

    @Test
    public void formatAsTable_参数含管道符_转义为竖线() {
        String sql = "SELECT id FROM user WHERE name = ?";
        String md = MarkdownFormatter.formatAsTable(sql, List.of("name"),
                Map.of("name", "a|b"), "user", "MySQL", null);
        assertTrue(md.contains("a\\|b"));
    }

    @Test
    public void formatAsTable_多行SQL_表格结构完整() {
        String sql = "SELECT\n    t.id,\n    t.name\nFROM `user` t\nWHERE t.id = 1";
        String md = MarkdownFormatter.formatAsTable(sql, List.of(), Map.of(), "user", "MySQL",
                "UserService.java:42");
        String[] lines = md.split("\n");
        // 3 行结构
        assertEquals(3, lines.length);
        // 每行都以 | 开头和结尾
        for (String line : lines) {
            assertTrue(line.trim().startsWith("|"));
            assertTrue(line.trim().endsWith("|"));
        }
        // 分隔符行
        assertTrue(lines[1].contains("---"));
    }

    @Test
    public void formatAsTable_六列表头_来源SQL参数表方言时间() {
        String sql = "SELECT id FROM user";
        String md = MarkdownFormatter.formatAsTable(sql, List.of(), Map.of(), "user", "MySQL",
                "UserService.java:42");
        String header = md.split("\n")[0];
        // 6 列顺序：来源 | SQL | 参数 | 表 | 方言 | 时间
        assertTrue(header.contains("来源"));
        assertTrue(header.contains("SQL"));
        assertTrue(header.contains("参数"));
        assertTrue(header.contains("表"));
        assertTrue(header.contains("方言"));
        assertTrue(header.contains("时间"));
        // 数据行中各值独立成列，来源列在第一列
        String dataRow = md.split("\n")[2];
        assertTrue(dataRow.contains("UserService.java:42"));
        assertTrue(dataRow.contains("user"));
        assertTrue(dataRow.contains("MySQL"));
        // 来源列在行首（UserService.java:42 是第一个数据单元格）
        assertTrue(dataRow.trim().startsWith("| UserService.java:42"));
    }

    @Test
    public void formatAsTable_无来源_显示横杠() {
        String sql = "SELECT id FROM user";
        String md = MarkdownFormatter.formatAsTable(sql, List.of(), Map.of(), "user", "MySQL", null);
        String dataRow = md.split("\n")[2];
        // 无来源时第一列显示 -
        assertTrue(dataRow.trim().startsWith("| -"));
    }
}
