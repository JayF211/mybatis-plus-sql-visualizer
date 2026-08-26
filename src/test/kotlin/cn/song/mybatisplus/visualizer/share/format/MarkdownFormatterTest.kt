package cn.song.mybatisplus.visualizer.share.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 格式化器单元测试（F-8 Phase A）。
 */
class MarkdownFormatterTest {

    @Test
    fun `format_普通SQL_生成代码块加元信息`() {
        val sql = "SELECT id, name FROM user WHERE name = 'zhangsan'"
        val md = MarkdownFormatter.format(
            sql = sql,
            tableName = "user",
            dialect = "MySQL",
            source = "UserService.java:42",
        )
        assertTrue(md.contains("```sql"))
        assertTrue(md.contains(sql))
        assertTrue(md.contains("```"))
        assertTrue(md.contains("user"))
        assertTrue(md.contains("MySQL"))
        assertTrue(md.contains("UserService.java:42"))
    }

    @Test
    fun `format_无参数SQL_省略参数对照`() {
        val sql = "SELECT id FROM user"
        val md = MarkdownFormatter.format(
            sql = sql,
            sources = emptyList(),
            paramValues = emptyMap(),
            tableName = "user",
            dialect = "MySQL",
        )
        assertTrue(md.contains("```sql"))
        assertTrue(md.contains(sql))
        // 无参数时不应出现参数对照表
        assertTrue(!md.contains("-- 参数对照"))
    }

    @Test
    fun `format_有参数SQL_包含参数对照`() {
        val sql = "SELECT id FROM user WHERE name = ? AND age = ?"
        val md = MarkdownFormatter.format(
            sql = sql,
            sources = listOf("name", "age"),
            paramValues = mapOf("name" to "zhangsan", "age" to "25"),
            tableName = "user",
            dialect = "MySQL",
        )
        assertTrue(md.contains("```sql"))
        // 参数对照
        assertTrue(md.contains("zhangsan"))
        assertTrue(md.contains("25"))
    }

    @Test
    fun `format_SQL含特殊字符_原样保留`() {
        val sql = "SELECT id FROM user WHERE name = 'O''Brien' -- comment"
        val md = MarkdownFormatter.format(
            sql = sql,
            tableName = "user",
            dialect = "MySQL",
        )
        assertTrue(md.contains("O''Brien"))
    }

    @Test
    fun `format_空source和dialect_省略对应元信息`() {
        val sql = "SELECT id FROM user"
        val md = MarkdownFormatter.format(
            sql = sql,
            tableName = "user",
            dialect = null,
            source = null,
        )
        assertTrue(md.contains("```sql"))
        assertTrue(md.contains(sql))
    }

    @Test
    fun `formatAsTable_生成Markdown表格`() {
        val sql = "SELECT id FROM user WHERE name = ?"
        val md = MarkdownFormatter.formatAsTable(
            sql = sql,
            sources = listOf("name"),
            paramValues = mapOf("name" to "zhangsan"),
            tableName = "user",
            dialect = "MySQL",
            source = "UserService.java:42",
        )
        // 表格应有 | 分隔符
        assertTrue(md.contains("|"))
        assertTrue(md.contains("---"))
        assertTrue(md.contains("SQL"))
        assertTrue(md.contains("参数"))
    }

    @Test
    fun `formatAsTable_多行SQL_换行替换为br`() {
        val sql = "SELECT\n    t.id,\n    t.name\nFROM user"
        val md = MarkdownFormatter.formatAsTable(
            sql = sql,
            tableName = "user",
            dialect = "MySQL",
        )
        // 换行应替换为 <br>，不应保留原 \n
        assertTrue(md.contains("<br>"))
        assertTrue(!md.contains("\n    t.id"))  // 原始缩进换行不应存在
        // 表格行数：表头1 + 分隔符1 + 数据行1 = 3 行（不含末尾换行）
        val lines = md.split("\n")
        assertEquals(3, lines.size)
    }

    @Test
    fun `formatAsTable_SQL含管道符_转义为竖线`() {
        val sql = "SELECT id FROM user WHERE a = 1 | b = 2"
        val md = MarkdownFormatter.formatAsTable(
            sql = sql,
            tableName = "user",
            dialect = "MySQL",
        )
        // | 应被转义为 \|
        assertTrue(md.contains("\\|"))
    }

    @Test
    fun `formatAsTable_参数含管道符_转义为竖线`() {
        val sql = "SELECT id FROM user WHERE name = ?"
        val md = MarkdownFormatter.formatAsTable(
            sql = sql,
            sources = listOf("name"),
            paramValues = mapOf("name" to "a|b"),
            tableName = "user",
            dialect = "MySQL",
        )
        assertTrue(md.contains("a\\|b"))
    }

    @Test
    fun `formatAsTable_多行SQL_表格结构完整`() {
        val sql = "SELECT\n    t.id,\n    t.name\nFROM `user` t\nWHERE t.id = 1"
        val md = MarkdownFormatter.formatAsTable(
            sql = sql,
            sources = emptyList(),
            paramValues = emptyMap(),
            tableName = "user",
            dialect = "MySQL",
            source = "UserService.java:42",
        )
        val lines = md.split("\n")
        // 3 行结构
        assertEquals(3, lines.size)
        // 每行都以 | 开头和结尾
        lines.forEach {
            assertTrue(it.trim().startsWith("|"))
            assertTrue(it.trim().endsWith("|"))
        }
        // 分隔符行
        assertTrue(lines[1].contains("---"))
    }

    @Test
    fun `formatAsTable_六列表头_来源SQL参数表方言时间`() {
        val sql = "SELECT id FROM user"
        val md = MarkdownFormatter.formatAsTable(
            sql = sql,
            sources = emptyList(),
            paramValues = emptyMap(),
            tableName = "user",
            dialect = "MySQL",
            source = "UserService.java:42",
        )
        val header = md.split("\n").first()
        // 6 列顺序：来源 | SQL | 参数 | 表 | 方言 | 时间
        assertTrue(header.contains("来源"))
        assertTrue(header.contains("SQL"))
        assertTrue(header.contains("参数"))
        assertTrue(header.contains("表"))
        assertTrue(header.contains("方言"))
        assertTrue(header.contains("时间"))
        // 数据行中各值独立成列，来源列在第一列
        val dataRow = md.split("\n")[2]
        assertTrue(dataRow.contains("UserService.java:42"))
        assertTrue(dataRow.contains("user"))
        assertTrue(dataRow.contains("MySQL"))
        // 来源列在行首（UserService.java:42 是第一个数据单元格）
        assertTrue(dataRow.trim().startsWith("| UserService.java:42"))
    }

    @Test
    fun `formatAsTable_无来源_显示横杠`() {
        val sql = "SELECT id FROM user"
        val md = MarkdownFormatter.formatAsTable(
            sql = sql,
            sources = emptyList(),
            paramValues = emptyMap(),
            tableName = "user",
            dialect = "MySQL",
            source = null,
        )
        val dataRow = md.split("\n")[2]
        // 无来源时第一列显示 -
        assertTrue(dataRow.trim().startsWith("| -"))
    }
}
