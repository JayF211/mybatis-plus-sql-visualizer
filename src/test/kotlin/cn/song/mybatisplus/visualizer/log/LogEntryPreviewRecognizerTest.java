package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.sql.ParamHint;
import org.junit.Assert.assertEquals;
import org.junit.Assert.assertFalse;
import org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 日志条目 → 预览参数识别单元测试（T-M42）：`?` 数 == 参数数 ⇔ 匹配识别；
 * 参数行来源按序 `param1..N`、值预填日志参数值、类型 → 引号提示；其余回退现状。
 */
public class LogEntryPreviewRecognizerTest {

    private LogSqlEntry entry(String rawSql, List<LogSqlParam> params, String restoredSql) {
        return new LogSqlEntry(rawSql, restoredSql.isEmpty() ? rawSql : restoredSql, params);
    }

    private static List<Boolean> quotes(RecognizedLogPreview recognized) {
        return recognized.paramHints().stream().map(ParamHint::getQuote).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // 匹配识别
    // ------------------------------------------------------------------

    @Test
    public void 占位符数与参数数一致时识别成功() {
        RecognizedLogPreview recognized = LogEntryPreviewRecognizerKt.toRecognizedPreview(entry(
                "SELECT * FROM t WHERE username = ? AND id = ?",
                List.of(new LogSqlParam("admin", "String"), new LogSqlParam("10", "Long"))));
        assertTrue("应识别成功", recognized.recognized());
        assertEquals("参数来源按 `?` 出现顺序", List.of("param1", "param2"), recognized.unresolvedParams());
        assertEquals("值按序预填", Map.of("param1", "admin", "param2", "10"), recognized.initialValues());
        assertEquals("sql 应为 rawSql", "SELECT * FROM t WHERE username = ? AND id = ?", recognized.sql());
        assertEquals("compactSql 应为 rawSql", "SELECT * FROM t WHERE username = ? AND id = ?", recognized.compactSql());
    }

    @Test
    public void 类型映射为引号提示() {
        RecognizedLogPreview recognized = LogEntryPreviewRecognizerKt.toRecognizedPreview(entry(
                "SELECT * FROM t WHERE a = ? AND b = ? AND c = ?",
                List.of(
                        new LogSqlParam("abc", "String"),
                        new LogSqlParam("10", "Long"),
                        new LogSqlParam("true", "Boolean"))));
        assertEquals(
                "String 引号 / 数值·布尔原样",
                List.of(true, false, false),
                quotes(recognized));
    }

    @Test
    public void 无类型标注提示为_null_走面板启发式() {
        RecognizedLogPreview recognized = LogEntryPreviewRecognizerKt.toRecognizedPreview(entry(
                "SELECT * FROM t WHERE a = ?",
                List.of(new LogSqlParam("abc", null))));
        assertEquals(List.of((Boolean) null), quotes(recognized));
    }

    @Test
    public void 时间等未知类型标注保守引号() {
        RecognizedLogPreview recognized = LogEntryPreviewRecognizerKt.toRecognizedPreview(entry(
                "SELECT * FROM t WHERE a = ? AND b = ?",
                List.of(
                        new LogSqlParam("2026-08-16 12:00:00.0", "Timestamp"),
                        new LogSqlParam("ACTIVE", "StatusEnum"))));
        assertEquals(List.of(true, true), quotes(recognized));
    }

    // ------------------------------------------------------------------
    // 回退（未识别）
    // ------------------------------------------------------------------

    @Test
    public void 参数为空时回退() {
        RecognizedLogPreview recognized = LogEntryPreviewRecognizerKt.toRecognizedPreview(entry(
                "SELECT * FROM t WHERE a = ?",
                List.of()));
        assertFalse("参数为空应回退", recognized.recognized());
        assertTrue(recognized.unresolvedParams().isEmpty());
        assertTrue(recognized.initialValues().isEmpty());
        assertTrue(recognized.paramHints().isEmpty());
    }

    @Test
    public void 参数多于占位符时回退() {
        RecognizedLogPreview recognized = LogEntryPreviewRecognizerKt.toRecognizedPreview(entry(
                "SELECT * FROM t WHERE a = ?",
                List.of(new LogSqlParam("1", "Long"), new LogSqlParam("2", "Long"))));
        assertFalse("参数多于占位符应回退", recognized.recognized());
    }

    @Test
    public void 参数少于占位符时回退() {
        RecognizedLogPreview recognized = LogEntryPreviewRecognizerKt.toRecognizedPreview(entry(
                "SELECT * FROM t WHERE a = ? AND b = ?",
                List.of(new LogSqlParam("1", "Long"))));
        assertFalse("参数少于占位符应回退", recognized.recognized());
    }

    @Test
    public void 无占位符无参数纯静态_SQL_回退() {
        RecognizedLogPreview recognized = LogEntryPreviewRecognizerKt.toRecognizedPreview(entry(
                "SELECT 1 FROM t",
                List.of()));
        assertFalse("纯静态 SQL 应回退", recognized.recognized());
    }

    // ------------------------------------------------------------------
    // 边界
    // ------------------------------------------------------------------

    @Test
    public void null_参数值预填空串() {
        RecognizedLogPreview recognized = LogEntryPreviewRecognizerKt.toRecognizedPreview(entry(
                "SELECT * FROM t WHERE a = ?",
                List.of(new LogSqlParam(null, null))));
        assertTrue(recognized.recognized());
        assertEquals(Map.of("param1", ""), recognized.initialValues());
        assertEquals(List.of((Boolean) null), quotes(recognized));
    }

    @Test
    public void 字符串字面量内问号计数与还原语义一致() {
        // rawSql 中 `'what?'` 的 `?` 同语义计数（LogSqlRestorer 裸 indexOf 替换）——
        // 3 个 `?` 配 3 个参数 ⇔ 还原无残留，识别成功
        RecognizedLogPreview recognized = LogEntryPreviewRecognizerKt.toRecognizedPreview(entry(
                "SELECT * FROM t WHERE note = 'what?' AND a = ? AND b = ?",
                List.of(new LogSqlParam("x?", "String"), new LogSqlParam("1", "Long"), new LogSqlParam("2", "Long"))));
        assertTrue("字面量内 ? 与占位符同语义计数", recognized.recognized());
        assertEquals(List.of("param1", "param2", "param3"), recognized.unresolvedParams());
        assertEquals(
                Map.of("param1", "x?", "param2", "1", "param3", "2"),
                recognized.initialValues());
    }

    @Test
    public void IN_列表多占位符按序识别() {
        RecognizedLogPreview recognized = LogEntryPreviewRecognizerKt.toRecognizedPreview(entry(
                "SELECT * FROM t WHERE id IN (?, ?, ?)",
                List.of(new LogSqlParam("1", "Long"), new LogSqlParam("2", "Long"), new LogSqlParam("3", "Long"))));
        assertTrue(recognized.recognized());
        assertEquals(List.of("param1", "param2", "param3"), recognized.unresolvedParams());
        assertEquals(
                Map.of("param1", "1", "param2", "2", "param3", "3"),
                recognized.initialValues());
        assertTrue(recognized.paramHints().stream().allMatch(p -> p.getQuote() == Boolean.FALSE));
    }
}
