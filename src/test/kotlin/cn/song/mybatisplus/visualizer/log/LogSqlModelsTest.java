package cn.song.mybatisplus.visualizer.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

import java.util.List;

/**
 * 日志条目 → 日志文本渲染（T-M38）：`toLogText` 输出与 [MyBatisLogParser] 宽容匹配互逆
 * （round-trip——渲染文本重新解析可得等价条目），供「发送到日志转 SQL」联动复用。
 */
public class LogSqlModelsTest {

    @Test
    public void 无参数条目只渲染_Preparing_行() throws Exception {
        LogSqlEntry entry = new LogSqlEntry("SELECT 1", "SELECT 1", List.of());
        assertEquals("==>  Preparing: SELECT 1", LogSqlModelsKt.toLogText(entry));
    }

    @Test
    public void 带类型标注参数渲染为_value_括号_type_形态() throws Exception {
        LogSqlEntry entry = new LogSqlEntry(
                "SELECT * FROM t WHERE id = ? AND name = ?",
                "SELECT * FROM t WHERE id = 1 AND name = '张三'",
                List.of(new LogSqlParam("1", "Long"), new LogSqlParam("张三", "String")));
        assertEquals(
                "==>  Preparing: SELECT * FROM t WHERE id = ? AND name = ?\n==> Parameters: 1(Long), 张三(String)",
                LogSqlModelsKt.toLogText(entry));
    }

    @Test
    public void null_参数渲染为_null_字面量() throws Exception {
        LogSqlEntry entry = new LogSqlEntry(
                "SELECT * FROM t WHERE a = ?",
                "SELECT * FROM t WHERE a = NULL",
                List.of(new LogSqlParam(null, null)));
        assertEquals("==>  Preparing: SELECT * FROM t WHERE a = ?\n==> Parameters: null", LogSqlModelsKt.toLogText(entry));
    }

    @Test
    public void 无类型标注参数直出值() throws Exception {
        LogSqlEntry entry = new LogSqlEntry(
                "SELECT * FROM t WHERE a = ?",
                "SELECT * FROM t WHERE a = 'x'",
                List.of(new LogSqlParam("x", null)));
        assertEquals("==>  Preparing: SELECT * FROM t WHERE a = ?\n==> Parameters: x", LogSqlModelsKt.toLogText(entry));
    }

    @Test
    public void 渲染文本可被解析器重新解析为等价条目() throws Exception {
        LogSqlEntry entry = new LogSqlEntry(
                "SELECT * FROM t WHERE id = ? AND name = ? AND age > ?",
                "SELECT * FROM t WHERE id = 1 AND name = 'a, b' AND age > 3",
                List.of(new LogSqlParam("1", "Long"), new LogSqlParam("a, b", "String"), new LogSqlParam("3", "Integer")));
        MyBatisLogParser parser = new MyBatisLogParser();
        for (String line : LogSqlModelsKt.toLogText(entry).split("\n")) {
            parser.feed(line);
        }
        parser.closePending();
        LogSqlEntry parsed = parser.takeEntries().get(0);
        assertEquals(entry.rawSql(), parsed.rawSql());
        assertEquals(entry.params(), parsed.params());
        assertEquals(entry.restoredSql(), parsed.restoredSql());
        assertNull("round-trip 后不应残留未闭合条目", parser.closePending());
    }

    // ---------- T-LogRowNumber：捕获序号字段 ----------

    @Test
    public void sequenceNumber默认为0表示无行号() throws Exception {
        LogSqlEntry entry = new LogSqlEntry("SELECT 1", "SELECT 1", List.of());
        assertEquals(0L, entry.sequenceNumber());
    }

    @Test
    public void sequenceNumber显式赋值与copy语义() throws Exception {
        // Java record 无 Kotlin copy()——withSequenceNumber 即「copy 覆盖序号」的等价物
        LogSqlEntry entry = new LogSqlEntry("SELECT 1", "SELECT 1", List.of()).withSequenceNumber(7L);
        assertEquals(7L, entry.sequenceNumber());
        assertEquals(8L, entry.withSequenceNumber(8L).sequenceNumber());
        // copy 只覆盖序号，其余字段保留
        assertEquals("SELECT 1", entry.withSequenceNumber(8L).restoredSql());
        assertEquals(7L, entry.sequenceNumber());
    }

    // ---------- T-PS4-04：normalizeSqlWhitespace 空白折叠正则提常量（行为等价防回潮） ----------

    @Test
    public void 空白规范化_trim加连续空白折叠行为钉死() throws Exception {
        // 常量为文件级 private，按行为等价钉住语义：trim + \s+ 折叠单空格；
        // 回退「replace(Regex(...)) 现场编译」行为不变测试仍绿（性能项），模式被改坏必红。
        assertEquals("a b", LogSqlModelsKt.normalizeSqlWhitespace("  a \t\n b  "));
        assertEquals("a b c", LogSqlModelsKt.normalizeSqlWhitespace("a\tb\nc"));
        assertEquals("SELECT * FROM t WHERE id = 1", LogSqlModelsKt.normalizeSqlWhitespace("SELECT * FROM t WHERE id = 1"));
        // 大小写与分号不动（COUNT 前缀判定、达梦带引号标识符大小写依赖）
        assertEquals("SELECT count(*) FROM T;", LogSqlModelsKt.normalizeSqlWhitespace("SELECT count(*) FROM T;"));
    }
}
