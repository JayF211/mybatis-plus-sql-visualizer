package cn.song.mybatisplus.visualizer.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SQL 日志文件读取器单元测试（F-44，纯 JUnit，无平台依赖）。
 */
public class SqlLogFileReaderTest {

    private static boolean isSqlFile(File f) {
        String name = f.getName();
        int idx = name.lastIndexOf('.');
        return idx >= 0 && "sql".equals(name.substring(idx + 1));
    }

    @Test
    public void 解析新版格式() throws Exception {
        String content = "--- source: src/main/java/com/example/mapper/UserMapper.java:42 | time: 5ms | 2026-08-25 10:00:00.123 ---\n"
                + "SELECT * FROM user WHERE id = 1\n\n";
        List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.parseContent(content);
        assertEquals(1, entries.size());
        LogSqlEntry entry = entries.get(0);
        assertEquals("SELECT * FROM user WHERE id = 1", entry.restoredSql());
        assertEquals("src/main/java/com/example/mapper/UserMapper.java", entry.sourceFile());
        assertEquals(42, entry.sourceLine());
        assertEquals(Long.valueOf(5L), entry.executionTimeMs());
        // 时区无关：仅验证 timestamp > 0
        assertTrue("Timestamp should be positive: " + entry.timestamp(), entry.timestamp() > 0);
    }

    @Test
    public void 解析新版格式_unknown_source() throws Exception {
        String content = "--- source: unknown | time: — | 2026-08-25 10:00:00.123 ---\nSELECT 1\n\n";
        List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.parseContent(content);
        assertEquals(1, entries.size());
        LogSqlEntry entry = entries.get(0);
        assertNull(entry.sourceFile());
        assertEquals(0, entry.sourceLine());
        assertNull(entry.executionTimeMs());
    }

    @Test
    public void 解析旧版格式() throws Exception {
        String content = "-- 2026-08-25 10:00:00\nSELECT * FROM user\n\n";
        List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.parseContent(content);
        assertEquals(1, entries.size());
        LogSqlEntry entry = entries.get(0);
        assertEquals("SELECT * FROM user", entry.restoredSql());
        assertNull(entry.sourceFile());
        assertEquals(0, entry.sourceLine());
    }

    @Test
    public void 解析混合格式() throws Exception {
        String content = "-- source: UserMapper.java:10 | time: 3ms | 2026-08-25 10:00:00.000 --\n"
                + "SELECT 1\n\n"
                + "-- 2026-08-25 09:00:00\n"
                + "SELECT 2\n\n"
                + "--- source: OrderMapper.java:20 | time: 10ms | 2026-08-25 11:00:00.000 ---\n"
                + "SELECT 3\n\n";
        List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.parseContent(content);
        assertEquals(3, entries.size());
        // 按文件中出现顺序：新版 → 旧版 → 新版
        assertEquals("SELECT 1", entries.get(0).restoredSql());
        assertEquals("UserMapper.java", entries.get(0).sourceFile());
        assertEquals("SELECT 2", entries.get(1).restoredSql());
        assertNull(entries.get(1).sourceFile());
        assertEquals("SELECT 3", entries.get(2).restoredSql());
        assertEquals("OrderMapper.java", entries.get(2).sourceFile());
    }

    @Test
    public void 解析多条新版格式() throws Exception {
        String content = "-- source: A.java:1 | time: 1ms | 2026-08-25 10:00:00.000 --\n"
                + "SELECT 1\n\n"
                + "-- source: B.java:2 | time: 2ms | 2026-08-25 10:00:01.000 --\n"
                + "SELECT 2\n\n"
                + "-- source: C.java:3 | time: 3ms | 2026-08-25 10:00:02.000 --\n"
                + "SELECT 3\n\n";
        List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.parseContent(content);
        assertEquals(3, entries.size());
        assertEquals("SELECT 1", entries.get(0).restoredSql());
        assertEquals("SELECT 2", entries.get(1).restoredSql());
        assertEquals("SELECT 3", entries.get(2).restoredSql());
    }

    @Test
    public void 空内容返回空列表() throws Exception {
        assertTrue(SqlLogFileReader.INSTANCE.parseContent("").isEmpty());
        assertTrue(SqlLogFileReader.INSTANCE.parseContent("   \n\n   ").isEmpty());
    }

    @Test
    public void 损坏格式返回空列表() throws Exception {
        String content = "这不是有效的格式\nSELECT 1\n";
        assertTrue(SqlLogFileReader.INSTANCE.parseContent(content).isEmpty());
    }

    @Test
    public void 读取文件并排序() throws Exception {
        File dir = Files.createTempDirectory("f44-reader-test-").toFile();
        dir.deleteOnExit();
        File file = new File(dir, "test.sql");
        Files.writeString(file.toPath(),
                "-- source: A.java:1 | time: 1ms | 2026-08-25 10:00:02.000 ---\n"
                        + "SELECT 3\n\n"
                        + "-- source: B.java:2 | time: 2ms | 2026-08-25 10:00:00.000 ---\n"
                        + "SELECT 1\n\n"
                        + "-- source: C.java:3 | time: 3ms | 2026-08-25 10:00:01.000 ---\n"
                        + "SELECT 2\n\n");
        List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.readFromFile(file).stream()
                .sorted(Comparator.comparingLong(LogSqlEntry::timestamp))
                .collect(Collectors.toList());
        assertEquals(3, entries.size());
        // 按时间戳排序
        assertEquals("SELECT 1", entries.get(0).restoredSql());
        assertEquals("SELECT 2", entries.get(1).restoredSql());
        assertEquals("SELECT 3", entries.get(2).restoredSql());
    }

    @Test
    public void 读取目录() throws Exception {
        File dir = Files.createTempDirectory("f44-reader-dir-").toFile();
        dir.deleteOnExit();
        Files.writeString(new File(dir, "a.sql").toPath(),
                "--- source: A.java:1 | time: 1ms | 2026-08-25 10:00:00.000 ---\nSELECT 1\n\n");
        Files.writeString(new File(dir, "b.sql").toPath(),
                "--- source: B.java:2 | time: 2ms | 2026-08-25 10:00:01.000 ---\nSELECT 2\n\n");
        // 非 .sql 文件应被忽略
        Files.writeString(new File(dir, "ignore.txt").toPath(), "ignore me");

        List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.readAll(dir.getAbsolutePath());
        assertEquals(2, entries.size());
    }

    @Test
    public void 解析双横线新版格式() throws Exception {
        // F-44-2：默认写作 `-- source: ... --`（两个 `-` 起结），Reader 必须正确解析
        String content = "-- source: src/main/java/UserMapper.java:42 | time: 5ms | 2026-08-31 17:30:00.123 --\n"
                + "SELECT * FROM user WHERE id = 1\n\n";
        List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.parseContent(content);
        assertEquals(1, entries.size());
        LogSqlEntry entry = entries.get(0);
        assertEquals("SELECT * FROM user WHERE id = 1", entry.restoredSql());
        assertEquals("src/main/java/UserMapper.java", entry.sourceFile());
        assertEquals(42, entry.sourceLine());
        assertEquals(Long.valueOf(5L), entry.executionTimeMs());
    }

    @Test
    public void 兼容三横线历史新版格式() throws Exception {
        // F-44-2 已停止生产三横线，但历史文件仍可能存在——Reader 必须向后兼容
        String content = "--- source: A.java:1 | time: 3ms | 2026-08-31 17:30:00.000 ---\nSELECT 1\n\n";
        List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.parseContent(content);
        assertEquals(1, entries.size());
        assertEquals("SELECT 1", entries.get(0).restoredSql());
        assertEquals("A.java", entries.get(0).sourceFile());
        assertEquals(Long.valueOf(3L), entries.get(0).executionTimeMs());
    }

    @Test
    public void 解析混合格式_F44_2与F43共存() throws Exception {
        // 验证新版本启用的双横线格式能与旧版时间戳格式共存
        String content = "-- source: UserMapper.java:10 | time: 3ms | 2026-08-31 17:30:00.000 --\n"
                + "SELECT 1\n\n"
                + "-- 2026-08-31 09:00:00\n"
                + "SELECT 2\n\n"
                + "-- source: OrderMapper.java:20 | time: 10ms | 2026-08-31 17:30:01.000 --\n"
                + "SELECT 3\n\n";
        List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.parseContent(content);
        assertEquals(3, entries.size());
        assertEquals("UserMapper.java", entries.get(0).sourceFile());
        assertEquals(Long.valueOf(3L), entries.get(0).executionTimeMs());
        assertNull(entries.get(1).sourceFile());
        assertEquals("OrderMapper.java", entries.get(2).sourceFile());
        assertEquals(Long.valueOf(10L), entries.get(2).executionTimeMs());
    }

    @Test
    public void 解析无源码行号时只返回文件() throws Exception {
        String content = "--- source: UserMapper.java | time: 5ms | 2026-08-25 10:00:00.123 ---\nSELECT 1\n\n";
        List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.parseContent(content);
        assertEquals(1, entries.size());
        assertEquals("UserMapper.java", entries.get(0).sourceFile());
        assertEquals(0, entries.get(0).sourceLine());
    }

    // ---------- F-44-3：countInFile 轻量条数统计（一级文件列表数据源） ----------

    @Test
    public void countInFile_统计新版双横线格式() throws Exception {
        File dir = Files.createTempDirectory("f443-count-").toFile();
        dir.deleteOnExit();
        File file = new File(dir, "a.sql");
        Files.writeString(file.toPath(),
                "-- source: A.java:1 | time: 1ms | 2026-08-31 17:30:00.000 --\n"
                        + "SELECT 1\n\n"
                        + "-- source: B.java:2 | time: 2ms | 2026-08-31 17:30:01.000 --\n"
                        + "SELECT 2\n\n"
                        + "-- source: C.java:3 | time: 3ms | 2026-08-31 17:30:02.000 --\n"
                        + "SELECT 3\n\n");
        assertEquals(3, SqlLogFileReader.INSTANCE.countInFile(file));
        // 与 readFromFile 完整解析一致
        assertEquals(3, SqlLogFileReader.INSTANCE.readFromFile(file).size());
    }

    @Test
    public void countInFile_兼容三横线与旧版格式混合() throws Exception {
        File dir = Files.createTempDirectory("f443-count-mix-").toFile();
        dir.deleteOnExit();
        File file = new File(dir, "mix.sql");
        Files.writeString(file.toPath(),
                "--- source: A.java:1 | time: 3ms | 2026-08-31 17:30:00.000 ---\n"
                        + "SELECT 1\n\n"
                        + "-- 2026-08-25 09:00:00\n"
                        + "SELECT 2\n\n"
                        + "-- source: B.java:2 | time: 5ms | 2026-08-31 17:30:01.000 --\n"
                        + "SELECT 3\n\n");
        assertEquals(3, SqlLogFileReader.INSTANCE.countInFile(file));
        // 与完整解析结果一致
        assertEquals(3, SqlLogFileReader.INSTANCE.readFromFile(file).size());
    }

    @Test
    public void countInFile_空文件与不存在文件返回0() throws Exception {
        File dir = Files.createTempDirectory("f443-count-empty-").toFile();
        dir.deleteOnExit();
        File file = new File(dir, "empty.sql");
        Files.writeString(file.toPath(), "");
        assertEquals(0, SqlLogFileReader.INSTANCE.countInFile(file));
        assertEquals(0, SqlLogFileReader.INSTANCE.countInFile(new File(dir, "missing.sql")));
    }

    // ------------------------------------------------------------------
    // 格式 v2：参数持久化行（T-HistParam）
    // ------------------------------------------------------------------

    @Test
    public void v2带参数行读回_rawSql与params还原_restoredSql为正文() throws Exception {
        String payload = SqlLogParamCodec.encode(
                "SELECT * FROM t WHERE a = ? AND b = ?",
                java.util.List.of(new LogSqlParam("x", "String"), new LogSqlParam(null, "BIGINT")));
        File dir = Files.createTempDirectory("histparam-v2-").toFile();
        dir.deleteOnExit();
        File file = new File(dir, "v2.sql");
        Files.writeString(file.toPath(),
                "-- source: A.java:1 | time: 1ms | 2026-09-11 09:00:00.000 --\n" +
                        "-- pd: " + payload + " --\n" +
                        "SELECT * FROM t WHERE a = 'x' AND b = NULL\n\n");
        java.util.List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.readFromFile(file);
        assertEquals(1, entries.size());
        assertEquals("rawSql 从载荷还原（含 ?）", "SELECT * FROM t WHERE a = ? AND b = ?", entries.get(0).rawSql());
        assertEquals("restoredSql 为正文（剥结尾分号）", "SELECT * FROM t WHERE a = 'x' AND b = NULL", entries.get(0).restoredSql());
        assertEquals("params 从载荷还原",
                java.util.List.of(new LogSqlParam("x", "String"), new LogSqlParam(null, "BIGINT")),
                entries.get(0).params());
        // 读回条目可直接走统一识别通道（与日志 tab 同参）
        assertTrue(LogEntryPreviewRecognizerKt.toRecognizedPreview(entries.get(0)).recognized());
    }

    @Test
    public void v2损坏参数行降级为正文注释行() throws Exception {
        File dir = Files.createTempDirectory("histparam-bad-").toFile();
        dir.deleteOnExit();
        File file = new File(dir, "bad.sql");
        Files.writeString(file.toPath(),
                "-- source: A.java:1 | time: 1ms | 2026-09-11 09:00:00.000 --\n" +
                        "-- pd: !!!not-base64!!! --\n" +
                        "SELECT 1\n\n");
        java.util.List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.readFromFile(file);
        assertEquals(1, entries.size());
        assertEquals("解码失败 pd 行留在正文（多一行注释无害）",
                "-- pd: !!!not-base64!!! --\nSELECT 1", entries.get(0).restoredSql());
        assertEquals("-- pd: !!!not-base64!!! --\nSELECT 1", entries.get(0).rawSql());
        assertTrue(entries.get(0).params().isEmpty());
    }

    @Test
    public void v2参数行不参与头部计数与块切分() throws Exception {
        String payload = SqlLogParamCodec.encode("SELECT ?", java.util.List.of(new LogSqlParam("1", "Long")));
        File dir = Files.createTempDirectory("histparam-count-").toFile();
        dir.deleteOnExit();
        File file = new File(dir, "mixed.sql");
        Files.writeString(file.toPath(),
                "-- source: A.java:1 | time: 1ms | 2026-09-11 09:00:00.000 --\n" +
                        "-- pd: " + payload + " --\n" +
                        "SELECT '1'\n\n" +
                        "-- 2026-09-11 08:00:00\n" +
                        "SELECT 2\n\n");
        // pd 行以 `-- ` 起始且无 `source:`——不排除会被旧头部判据误判，计数翻倍且块被劈开
        assertEquals("countInFile 不计 pd 行", 2, SqlLogFileReader.INSTANCE.countInFile(file));
        java.util.List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.readFromFile(file);
        assertEquals("pd 行不劈块：v2 条目 + F-43 条目", 2, entries.size());
        assertEquals("SELECT '1'", entries.get(0).restoredSql());
        assertEquals("SELECT 2", entries.get(1).restoredSql());
    }
}
