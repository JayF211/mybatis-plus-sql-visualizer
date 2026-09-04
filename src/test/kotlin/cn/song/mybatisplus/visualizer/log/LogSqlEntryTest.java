package cn.song.mybatisplus.visualizer.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * [LogSqlEntry] 回渲为 MyBatis 日志文本（`==>  Preparing:` + `==> Parameters:`），与
 * [MyBatisLogParser] 的 mixedPrefix 宽容匹配互逆——本测试锁定该渲染契约，
 * 防止「发送到日志转 SQL」链路错位。无 IntelliJ 依赖，纯 JUnit。
 */
public class LogSqlEntryTest {

    @Test
    public void testToLogText_无参数_仅Preparing行() throws Exception {
        LogSqlEntry entry = new LogSqlEntry("SELECT 1", "SELECT 1", List.of());
        String text = LogSqlModelsKt.toLogText(entry);
        assertTrue(text.startsWith("==>  Preparing: SELECT 1"));
        assertFalse("无参数不应包含 Parameters 行", text.contains("Parameters:"));
    }

    @Test
    public void testToLogText_含参数_渲染类型标注() throws Exception {
        LogSqlEntry entry = new LogSqlEntry(
                "select * from t where id=? and name=?",
                "select * from t where id=? and name=?",
                List.of(new LogSqlParam("1", "Long"), new LogSqlParam("abc", "String")));
        String text = LogSqlModelsKt.toLogText(entry);
        assertTrue(text.contains("==>  Preparing: select * from t where id=? and name=?"));
        assertTrue(text.contains("==> Parameters: 1(Long), abc(String)"));
    }

    @Test
    public void testToLogText_null参数_渲染为null() throws Exception {
        LogSqlEntry entry = new LogSqlEntry(
                "select ?",
                "select ?",
                List.of(new LogSqlParam(null, null)));
        String text = LogSqlModelsKt.toLogText(entry);
        assertTrue(text.contains("==> Parameters: null"));
    }

    @Test
    public void testToLogText_结构为Preparing加Parameters两行() throws Exception {
        LogSqlEntry entry = new LogSqlEntry(
                "insert into t(a) values(?)",
                "insert into t(a) values(?)",
                List.of(new LogSqlParam("9", "Integer")));
        String text = LogSqlModelsKt.toLogText(entry);
        List<String> lines = Arrays.asList(text.split("\n"));
        assertEquals("应恰为 Preparing + Parameters 两行", 2, lines.size());
        assertTrue(lines.get(0).startsWith("==>  Preparing:"));
        assertTrue(lines.get(1).startsWith("==> Parameters:"));
    }
}
