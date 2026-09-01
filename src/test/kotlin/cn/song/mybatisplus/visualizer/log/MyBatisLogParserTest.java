package cn.song.mybatisplus.visualizer.log;

import org.junit.Assert.assertEquals;
import org.junit.Assert.assertNull;
import org.junit.Test;

import java.util.List;

/**
 * 日志行解析与参数配对单元测试（T-M31-01）。
 */
public class MyBatisLogParserTest {

    private MyBatisLogParser parser() {
        return new MyBatisLogParser();
    }

    private List<LogSqlEntry> feedAll(MyBatisLogParser p, String... lines) {
        for (String line : lines) {
            p.feed(line);
        }
        p.closePending();
        return p.takeEntries();
    }

    @Test
    public void MP_前缀格式配对_Preparing_与_Parameters() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "==>  Preparing: SELECT id, name FROM user WHERE id = ? AND status = ?",
                "==> Parameters: 1(Long), active(String)");
        assertEquals(1, entries.size());
        assertEquals("SELECT id, name FROM user WHERE id = 1 AND status = 'active'", entries.get(0).restoredSql());
        assertEquals(2, entries.get(0).params().size());
        assertEquals("1", entries.get(0).params().get(0).value());
        assertEquals("Long", entries.get(0).params().get(0).type());
    }

    @Test
    public void MyBatis_原生格式无前缀配对() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Preparing: DELETE FROM t WHERE id = ?",
                "Parameters: 7(Integer)");
        assertEquals(1, entries.size());
        assertEquals("DELETE FROM t WHERE id = 7", entries.get(0).restoredSql());
    }

    @Test
    public void 带日志框架前缀的行按关键字定位() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "2026-08-15 10:00:00.123  INFO 1234 --- [main] c.s.m.Log : ==>  Preparing: SELECT * FROM user WHERE id = ?",
                "2026-08-15 10:00:00.124  INFO 1234 --- [main] c.s.m.Log : ==> Parameters: 42(Long)");
        assertEquals(1, entries.size());
        assertEquals("SELECT * FROM user WHERE id = 42", entries.get(0).restoredSql());
    }

    @Test
    public void 多条语句连续配对() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "==>  Preparing: SELECT * FROM a WHERE x = ?",
                "==> Parameters: 1(Integer)",
                "==>  Preparing: SELECT * FROM b WHERE y = ?",
                "==> Parameters: 2(Integer)");
        assertEquals(2, entries.size());
        assertEquals("SELECT * FROM a WHERE x = 1", entries.get(0).restoredSql());
        assertEquals("SELECT * FROM b WHERE y = 2", entries.get(1).restoredSql());
    }

    @Test
    public void 无参语句直接闭合不替换() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "==>  Preparing: SELECT COUNT(*) FROM user",
                "==> Total: 1",
                "<== Columns: COUNT(*)");
        assertEquals(1, entries.size());
        assertEquals("SELECT COUNT(*) FROM user", entries.get(0).restoredSql());
        assertEquals(List.of(), entries.get(0).params());
    }

    @Test
    public void 噪声行忽略() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "2026-08-15 10:00:00.100  INFO --- [main] c.s.m.Application : started in 2s",
                "==>  Preparing: SELECT * FROM t WHERE id = ?",
                "==> Parameters: 3(Long)",
                "<==    Columns: id, name",
                "<==      Total: 1");
        assertEquals(1, entries.size());
    }

    @Test
    public void 无参_Preparing_由下一条_Preparing_闭合() {
        MyBatisLogParser p = parser();
        p.feed("==>  Preparing: SELECT 1"); // 无参语句
        p.feed("==>  Preparing: SELECT * FROM t WHERE id = ?"); // 新语句闭合上一条
        p.feed("==> Parameters: 5(Long)"); // 属于第二条
        p.closePending();
        List<LogSqlEntry> entries = p.takeEntries();
        assertEquals(2, entries.size());
        assertEquals("SELECT 1", entries.get(0).restoredSql());
        assertEquals("SELECT * FROM t WHERE id = 5", entries.get(1).restoredSql());
    }

    @Test
    public void 值内逗号与括号的字符串参数() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "==>  Preparing: SELECT * FROM t WHERE name IN (?, ?)",
                "==> Parameters: a, b(String), c(x)(String)");
        assertEquals(1, entries.size());
        assertEquals("SELECT * FROM t WHERE name IN ('a, b', 'c(x)')", entries.get(0).restoredSql());
    }

    @Test
    public void null_字面量位于参数列表中间() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "==>  Preparing: SELECT * FROM t WHERE a = ? AND b = ? AND c = ?",
                "==> Parameters: 1(Long), null, abc(String)");
        assertEquals("SELECT * FROM t WHERE a = 1 AND b = NULL AND c = 'abc'", entries.get(0).restoredSql());
    }

    @Test
    public void 无类型标注参数回落启发式() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "==>  Preparing: SELECT * FROM t WHERE a = ? AND b = ?",
                "==> Parameters: 1, abc");
        assertEquals(1, entries.size());
        assertEquals("SELECT * FROM t WHERE a = 1 AND b = 'abc'", entries.get(0).restoredSql());
    }

    @Test
    public void 占位符少于参数时忽略多余参数() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "==>  Preparing: SELECT * FROM t WHERE id = ?",
                "==> Parameters: 1(Long), 2(Long)");
        assertEquals("SELECT * FROM t WHERE id = 1", entries.get(0).restoredSql());
    }

    @Test
    public void 占位符多于参数时保留问号() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "==>  Preparing: SELECT * FROM t WHERE a = ? AND b = ?",
                "==> Parameters: 1(Long)");
        assertEquals("SELECT * FROM t WHERE a = 1 AND b = ?", entries.get(0).restoredSql());
    }

    @Test
    public void 参数值包含单引号时转义() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "==>  Preparing: SELECT * FROM t WHERE name = ?",
                "==> Parameters: O'Brien(String)");
        assertEquals("SELECT * FROM t WHERE name = 'O''Brien'", entries.get(0).restoredSql());
    }

    @Test
    public void takeEntries_取走后缓冲区清空() {
        MyBatisLogParser p = parser();
        p.feed("==>  Preparing: SELECT 1");
        p.closePending();
        assertEquals(1, p.takeEntries().size());
        assertNull(p.closePending()); // 无挂起条目
        assertEquals(0, p.takeEntries().size());
    }

    // ---------- T-M31.14 可配置前缀 / 混合模式 / 最大长度 ----------

    @Test
    public void 自定义前缀解析() {
        MyBatisLogParser p = new MyBatisLogParser(new LogSqlRestorer(), "==> SQL:", "==> ARGS:", true, 20000);
        p.feed("==> SQL: SELECT * FROM t WHERE id = ?");
        p.feed("==> ARGS: 5(Long)");
        p.closePending();
        List<LogSqlEntry> entries = p.takeEntries();
        assertEquals(1, entries.size());
        assertEquals("SELECT * FROM t WHERE id = 5", entries.get(0).restoredSql());
    }

    @Test
    public void 混合模式关闭时仅行首精确匹配() {
        MyBatisLogParser p = new MyBatisLogParser(new LogSqlRestorer(), "Preparing:", "Parameters:", false, 20000);
        p.feed("2026-08-15 INFO ==>  Preparing: SELECT 1"); // 带日志前缀 → 不匹配（非行首）
        p.feed("Preparing: SELECT 2"); // 行首匹配
        p.closePending();
        List<LogSqlEntry> entries = p.takeEntries();
        assertEquals(1, entries.size());
        assertEquals("SELECT 2", entries.get(0).restoredSql());
    }

    @Test
    public void 混合模式开启时带日志前缀仍匹配() {
        MyBatisLogParser p = new MyBatisLogParser(new LogSqlRestorer(), "Preparing:", "Parameters:", true, 20000);
        p.feed("2026-08-15 INFO ==>  Preparing: SELECT 1");
        p.closePending();
        List<LogSqlEntry> entries = p.takeEntries();
        assertEquals(1, entries.size());
        assertEquals("SELECT 1", entries.get(0).restoredSql());
    }

    @Test
    public void 超长_SQL_截断() {
        MyBatisLogParser p = new MyBatisLogParser(new LogSqlRestorer(), "Preparing:", "Parameters:", true, 20);
        p.feed("Preparing: SELECT * FROM very_long_table_name WHERE id = 1");
        p.closePending();
        List<LogSqlEntry> entries = p.takeEntries();
        assertEquals(20, entries.get(0).rawSql().length());
    }

    // ---------- T-M31.xx 前缀与 SQL 分行兼容 ----------

    @Test
    public void MP_前缀分行_Preparing_独占一行加_SQL_在下一行() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "==>  Preparing:",
                "SELECT * FROM user WHERE id = ?",
                "==> Parameters: 1(Long)");
        assertEquals(1, entries.size());
        assertEquals("SELECT * FROM user WHERE id = 1", entries.get(0).restoredSql());
        assertEquals(1, entries.get(0).params().size());
        assertEquals("1", entries.get(0).params().get(0).value());
        assertEquals("Long", entries.get(0).params().get(0).type());
    }

    @Test
    public void 原生前缀分行() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "Preparing:",
                "DELETE FROM t WHERE id = ?",
                "Parameters: 7(Integer)");
        assertEquals(1, entries.size());
        assertEquals("DELETE FROM t WHERE id = 7", entries.get(0).restoredSql());
    }

    @Test
    public void 带日志框架前缀分行() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "2026-08-15 10:00:00.123 INFO --- [main] c.s.m.Log : ==>  Preparing:",
                "SELECT * FROM user WHERE id = ?",
                "2026-08-15 10:00:00.124 INFO --- [main] c.s.m.Log : ==> Parameters: 42(Long)");
        assertEquals(1, entries.size());
        assertEquals("SELECT * FROM user WHERE id = 42", entries.get(0).restoredSql());
    }

    @Test
    public void 前缀与_SQL_间有空白行() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "==>  Preparing:",
                "",
                "SELECT * FROM t WHERE id = ?",
                "==> Parameters: 3(Long)");
        assertEquals(1, entries.size());
        assertEquals("SELECT * FROM t WHERE id = 3", entries.get(0).restoredSql());
    }

    @Test
    public void 无参语句分行() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "==>  Preparing:",
                "SELECT COUNT(*) FROM user",
                "<== Total: 1");
        assertEquals(1, entries.size());
        assertEquals("SELECT COUNT(*) FROM user", entries.get(0).restoredSql());
        assertEquals(List.of(), entries.get(0).params());
    }

    @Test
    public void 多条语句每条_Preparing_分行() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "==>  Preparing:",
                "SELECT * FROM a WHERE x = ?",
                "==> Parameters: 1(Integer)",
                "==>  Preparing:",
                "SELECT * FROM b WHERE y = ?",
                "==> Parameters: 2(Integer)");
        assertEquals(2, entries.size());
        assertEquals("SELECT * FROM a WHERE x = 1", entries.get(0).restoredSql());
        assertEquals("SELECT * FROM b WHERE y = 2", entries.get(1).restoredSql());
    }

    @Test
    public void Parameters_也分行极端形态() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "==>  Preparing:",
                "SELECT * FROM t WHERE id = ?",
                "==> Parameters:",
                "5(Long)");
        assertEquals(1, entries.size());
        assertEquals("SELECT * FROM t WHERE id = 5", entries.get(0).restoredSql());
    }

    @Test
    public void 分行混合噪声行正确识别() {
        List<LogSqlEntry> entries = feedAll(
                parser(),
                "2026-08-15 INFO started",
                "==>  Preparing:",
                "SELECT * FROM user WHERE id = ?",
                "==> Parameters: 9(Long)",
                "<== Total: 1");
        assertEquals(1, entries.size());
        assertEquals("SELECT * FROM user WHERE id = 9", entries.get(0).restoredSql());
    }

    @Test
    public void 严格模式下前缀分行仍生效() {
        MyBatisLogParser p = new MyBatisLogParser(new LogSqlRestorer(), "Preparing:", "Parameters:", false, 20000);
        p.feed("Preparing:");
        p.feed("SELECT * FROM t WHERE id = ?");
        p.feed("Parameters: 3(Long)");
        p.closePending();
        List<LogSqlEntry> entries = p.takeEntries();
        assertEquals(1, entries.size());
        assertEquals("SELECT * FROM t WHERE id = 3", entries.get(0).restoredSql());
    }
}
