package cn.song.mybatisplus.visualizer.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * 自动保存历史 SQL 写入器单元测试（F-43，纯 JUnit，无平台依赖）。
 */
public class SqlLogAutoSaverTest {

    private static LogSqlEntry entry(String sql) {
        return new LogSqlEntry(
            sql,
            sql,
            List.<LogSqlParam>of(),
            1724567890000L // 固定时间戳方便断言
        );
    }

    private static String tempDir() throws Exception {
        File dir = Files.createTempDirectory("f43-test-").toFile();
        dir.deleteOnExit();
        return dir.getAbsolutePath();
    }

    @Test
    public void 写入单条生成正确格式() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String dir = tempDir();
        saver.configure("", "test-$yyyy-MM-dd$.sql", dir);
        saver.write(entry("SELECT 1"));
        File file = saver.currentFile();
        assertNotNull("File should be created", file);
        // 读取文件内容前不 close（close 会置 null）
        String content = Files.readString(file.toPath());
        // F-44-2：新格式 -- source: xxx | time: xxx | timestamp --（标准 SQL 单行注释）
        assertTrue("Should contain source header: " + content, content.contains("source: unknown"));
        assertTrue("Should contain duration: " + content, content.contains("time: —"));
        assertTrue("Should contain timestamp: " + content,
            content.matches("(?s).*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}.*"));
        assertTrue("Should contain SQL: " + content, content.contains("SELECT 1"));
        saver.close();
    }

    @Test
    public void 写入头使用双横线注释() throws Exception {
        // F-44-2：注释头部必须为 `--` 起结（标准 SQL 单行注释），不再用 `---`
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String dir = tempDir();
        saver.configure("", "test.sql", dir);
        saver.write(entry("SELECT 1"));
        File file = saver.currentFile();
        String content = Files.readString(file.toPath());
        // 起结行 -- source: ... -- （中间可能有内容，end 必须是 ` --`，不能再用 ` ---`）
        String firstLine = content.contains("\n") ? content.substring(0, content.indexOf('\n')) : content;
        assertTrue("首行应以 `--` 起始: " + firstLine, firstLine.startsWith("-- source:"));
        assertTrue("首行应以 ` --` 结尾: " + firstLine, firstLine.endsWith(" --"));
        assertFalse("首行不应再有 `---`: " + firstLine, firstLine.startsWith("---"));
        assertFalse("首行不应再以 `---` 结尾: " + firstLine, firstLine.endsWith("---"));
        saver.close();
    }

    @Test
    public void 多条追加不覆盖() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String dir = tempDir();
        saver.configure("", "test.sql", dir);
        saver.write(entry("SELECT 1"));
        saver.write(entry("SELECT 2"));
        saver.write(entry("SELECT 3"));
        File file = saver.currentFile();
        assertNotNull(file);
        String content = Files.readString(file.toPath());
        // F-44-2：新格式以 "-- source:" 开头（标准 SQL 单行注释，两个 `-`）
        int count = content.split("-- source:", -1).length - 1;
        assertEquals(3, count);
        saver.close();
    }

    @Test
    public void 文件名模板解析() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        saver.configure("", "sql-history-$yyyy-MM-dd$.sql", "/tmp");
        String resolved = saver.resolveFileName();
        // resolveFileName 使用当前时间，验证格式匹配即可
        assertTrue("Expected pattern like sql-history-YYYY-MM-DD.sql, got: " + resolved,
            resolved.matches("sql-history-\\d{4}-\\d{2}-\\d{2}\\.sql"));
        // 验证各占位符都被替换（不应再包含 $）
        assertFalse("All placeholders should be replaced: " + resolved, resolved.contains("$"));
    }

    @Test
    public void 文件名模板解析_冒号替换为横线() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        saver.configure("", "log-$HH:mm:ss$.sql", "/tmp");
        String resolved = saver.resolveFileName();
        assertFalse("':' should be replaced with '-': " + resolved, resolved.contains(":"));
        assertTrue("Expected pattern like log-HH-mm-ss.sql, got: " + resolved,
            resolved.matches("log-\\d{2}-\\d{2}-\\d{2}\\.sql"));
    }

    @Test
    public void 文件名模板解析_未知占位符原样保留() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        saver.configure("", "log-$unknown$-$yyyy$.sql", "/tmp");
        String resolved = saver.resolveFileName();
        assertTrue("Unknown placeholder should be preserved: " + resolved,
            resolved.startsWith("log-$unknown$-"));
    }

    @Test
    public void 默认目录解析() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        saver.configure("", "test.sql", "/home/user/myproject");
        String resolved = saver.resolveFileName();
        assertEquals("test.sql", resolved);
    }

    @Test
    public void 自定义路径() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String customDir = tempDir();
        saver.configure(customDir, "custom.sql", "/other/path");
        saver.write(entry("SELECT 1"));
        File file = saver.currentFile();
        assertNotNull(file);
        assertEquals(customDir, file.getParentFile().getAbsolutePath());
        assertEquals("custom.sql", file.getName());
        saver.close();
    }

    @Test
    public void 无效路径不抛异常() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        // 使用包含空字符的路径（Windows/macOS 均不允许路径含 \0）
        saver.configure("invalid\u0000path", "test.sql", null);
        // write 不应抛出异常
        saver.write(entry("SELECT 1"));
        // currentFile 应该为 null（因为文件无法创建）
        assertNull(saver.currentFile());
        saver.close();
    }

    @Test
    public void close后写入安全() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String dir = tempDir();
        saver.configure("", "test.sql", dir);
        saver.write(entry("SELECT 1"));
        saver.close();
        // close 后再 write，不应抛异常（懒打开新文件）
        saver.write(entry("SELECT 2"));
        saver.close();
    }

    @Test
    public void configure关闭旧文件重置状态() throws Exception {
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        String dir1 = tempDir();
        String dir2 = tempDir();
        saver.configure(dir1, "file1.sql", null);
        saver.write(entry("SELECT 1"));
        assertNotNull(saver.currentFile());
        // 切换目录
        saver.configure(dir2, "file2.sql", null);
        // 写入后应在 dir2 创建文件
        saver.write(entry("SELECT 2"));
        File file = saver.currentFile();
        assertNotNull(file);
        assertEquals(dir2, file.getParentFile().getAbsolutePath());
        assertEquals("file2.sql", file.getName());
        saver.close();
    }

    // ---------- T-AutoSaverSOE：无限递归回归（真机 2026-09-04 00:09 StackOverflowError） ----------

    /** 解析本地时间字符串为 epoch 毫秒（时钟注入用）。 */
    private static long timeOf(String dateTime) throws Exception {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(dateTime).getTime();
    }

    @Test
    public void 跨天切换文件不再无限递归() throws Exception {
        // 回归：旧实现在文件名变化分支递归重写，但 currentResolvedName 永不更新，
        // 跨天后首次写入即 StackOverflowError
        final long[] now = {timeOf("2026-09-04 23:59:50")};
        SqlLogAutoSaver saver = new SqlLogAutoSaver(() -> now[0], 100L * 1024 * 1024);
        String dir = tempDir();
        saver.configure(dir, "sql-history-$yyyy-MM-dd$.sql", null);
        saver.write(entry("SELECT 1"));
        File day1 = saver.currentFile();
        now[0] = timeOf("2026-09-05 00:00:10");
        saver.write(entry("SELECT 2"));
        File day2 = saver.currentFile();
        assertNotNull(day1);
        assertNotNull(day2);
        org.junit.Assert.assertNotEquals("跨天后应切换文件: " + day1 + " -> " + day2,
                day1.getName(), day2.getName());
        assertEquals("sql-history-2026-09-05.sql", day2.getName());
        assertEquals(1, Files.readString(day1.toPath()).split("-- source:", -1).length - 1);
        assertEquals(1, Files.readString(day2.toPath()).split("-- source:", -1).length - 1);
        saver.close();
    }

    @Test
    public void 秒级模板连续写入不再无限递归() throws Exception {
        // 回归：模板含 $ss$ 等细粒度占位符时，旧实现第二次写入即 StackOverflowError
        final long[] now = {timeOf("2026-09-04 10:00:00")};
        SqlLogAutoSaver saver = new SqlLogAutoSaver(() -> now[0], 100L * 1024 * 1024);
        String dir = tempDir();
        saver.configure(dir, "log-$HH-mm-ss$.sql", null);
        for (int i = 0; i < 3; i++) {
            saver.write(entry("SELECT " + i));
            now[0] += 2000;
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        for (File f : new File(dir).listFiles()) {
            names.add(f.getName());
        }
        java.util.Collections.sort(names);
        assertEquals(java.util.List.of("log-10-00-00.sql", "log-10-00-02.sql", "log-10-00-04.sql"), names);
        saver.close();
    }

    @Test
    public void 单文件超限滚动序号递增() throws Exception {
        // 回归：旧实现滚动分支条件恒真且递归无出口，达阈值即 StackOverflowError
        SqlLogAutoSaver saver = new SqlLogAutoSaver(System::currentTimeMillis, 100);
        String dir = tempDir();
        saver.configure(dir, "test.sql", null);
        for (int i = 0; i < 3; i++) {
            saver.write(entry("SELECT " + i));
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        for (File f : new File(dir).listFiles()) {
            names.add(f.getName());
        }
        java.util.Collections.sort(names);
        assertEquals(java.util.List.of("test.2.sql", "test.sql"), names);
        assertEquals(2, Files.readString(new File(dir, "test.sql").toPath()).split("-- source:", -1).length - 1);
        assertEquals(1, Files.readString(new File(dir, "test.2.sql").toPath()).split("-- source:", -1).length - 1);
        saver.close();
    }

    @Test
    public void 跨天后滚动序号复位() throws Exception {
        final long[] now = {timeOf("2026-09-04 10:00:00")};
        SqlLogAutoSaver saver = new SqlLogAutoSaver(() -> now[0], 100);
        String dir = tempDir();
        saver.configure(dir, "sql-history-$yyyy-MM-dd$.sql", null);
        for (int i = 0; i < 3; i++) {
            saver.write(entry("SELECT " + i));
        }
        assertTrue(new File(dir, "sql-history-2026-09-04.2.sql").exists());
        now[0] = timeOf("2026-09-05 10:00:00");
        saver.write(entry("SELECT 9"));
        assertEquals("sql-history-2026-09-05.sql", saver.currentFile().getName());
        saver.close();
    }
}
