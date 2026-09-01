package cn.song.mybatisplus.visualizer.log;

import org.junit.Assert.assertEquals;
import org.junit.Assert.assertNull;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 自动保存 → 历史读取 回环测试（F-44-2/F-44-3 真实场景）：
 *
 * 模拟真实链路——日志面板捕获 SQL（带源码位置与耗时）→ buffer 淘汰触发 [SqlLogAutoSaver.write]
 * 写入 `.idea/sql-visualizer/sql-history/` → 用户打开历史面板经 [SqlLogFileReader] 读回。
 * 断言写读两端字段完全一致（SQL 文本、源码位置、耗时、时间戳、条数），防止格式回归。
 */
public class SqlLogHistoryRoundTripTest {

    /** 构造带完整元数据的真实条目（对齐 FilterProxyHandler 组装的 entry）。 */
    private static LogSqlEntry capturedEntry() {
        return capturedEntry("SELECT * FROM user WHERE id = 1",
                "src/main/java/com/example/mapper/UserMapper.java", 42, 5L, 1724567890000L);
    }

    private static LogSqlEntry capturedEntry(String sql, String sourceFile, int sourceLine,
                                             Long executionTimeMs, long timestamp) {
        return new LogSqlEntry(sql, sql, List.of(), timestamp, sourceFile, sourceLine, executionTimeMs);
    }

    private static File tempDir() throws Exception {
        File dir = Files.createTempDirectory("roundtrip-").toFile();
        dir.deleteOnExit();
        return dir;
    }

    private static String timestampStr(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ENGLISH).format(new Date(ms));
    }

    @Test
    public void 真实链路_捕获条目自动保存后读取回环_字段全一致() throws Exception {
        File dir = tempDir();
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        saver.configure(dir.getAbsolutePath(), "sql-history-$yyyy-MM-dd$.sql", null);
        // 真实捕获条目：带源码位置 + 5ms 耗时
        LogSqlEntry captured = capturedEntry(
                "SELECT id, username, password FROM user WHERE id = 1",
                "src/main/java/com/example/mapper/UserMapper.java",
                42,
                5L,
                1724567890000L);
        saver.write(captured);
        saver.close();

        File[] files = Objects.requireNonNull(dir.listFiles((d, n) -> n.endsWith(".sql")));
        assertEquals("应生成 1 个文件", 1, files.length);
        // 文件头部应为标准 SQL 注释 `--`（F-44-2 双横线，非 `---`）
        List<String> lines = Files.readAllLines(files[0].toPath());
        String firstLine = lines.get(0);
        assertEquals("-- source: src/main/java/com/example/mapper/UserMapper.java:42 | time: 5ms | "
                + timestampStr(captured.timestamp()) + " --", firstLine);

        // 用户打开历史面板读取
        List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.readFromFile(files[0]);
        assertEquals("条数一致", 1, entries.size());
        LogSqlEntry read = entries.get(0);
        assertEquals("SQL 文本一致", captured.restoredSql(), read.restoredSql());
        assertEquals("源码文件一致", captured.sourceFile(), read.sourceFile());
        assertEquals("源码行号一致", captured.sourceLine(), read.sourceLine());
        assertEquals("耗时一致", captured.executionTimeMs(), read.executionTimeMs());
        assertEquals("时间戳一致", captured.timestamp(), read.timestamp());
    }

    @Test
    public void 真实链路_多文件多条目_文件条数统计与读取一致() throws Exception {
        File dir = tempDir();
        SqlLogAutoSaver saver = new SqlLogAutoSaver();
        saver.configure(dir.getAbsolutePath(), "sql-history-$yyyy-MM-dd$.sql", null);
        // 模拟两天的历史：今天 3 条、昨天 2 条
        saver.write(capturedEntry("SELECT 1", "A.java", 1, 1L, 1724567890000L));
        saver.write(capturedEntry("SELECT 2", "A.java", 2, 2L, 1724567890001L));
        saver.write(capturedEntry("SELECT 3", "A.java", 3, 3L, 1724567890002L));
        saver.close();

        File[] files = Objects.requireNonNull(dir.listFiles((d, n) -> n.endsWith(".sql")));
        assertEquals("同一天应写入 1 个文件", 1, files.length);
        // 一级文件列表显示的条数统计（轻量 countInFile）
        assertEquals("countInFile 条数 = 3", 3, SqlLogFileReader.INSTANCE.countInFile(files[0]));
        // 二级读取的完整解析
        assertEquals("readFromFile 条数 = 3", 3, SqlLogFileReader.INSTANCE.readFromFile(files[0]).size());

        // 第二个文件（模拟另一天：固定文件名，避免与当天文件同名被覆盖——newBufferedWriter 默认 TRUNCATE）
        SqlLogAutoSaver saver2 = new SqlLogAutoSaver();
        saver2.configure(dir.getAbsolutePath(), "sql-history-2026-08-30.sql", null);
        saver2.write(capturedEntry("SELECT A", "B.java", 10, null, 1724567000000L));
        saver2.write(capturedEntry("SELECT B", "B.java", 11, null, 1724567000001L));
        saver2.close();
        assertEquals("两天共 2 个文件",
                2, Objects.requireNonNull(dir.listFiles((d, n) -> n.endsWith(".sql"))).length);

        // 无耗时条目：文件写 `—`，读回 null
        List<LogSqlEntry> all = SqlLogFileReader.INSTANCE.readAll(dir.getAbsolutePath());
        assertEquals("两文件共 5 条", 5, all.size());
        List<LogSqlEntry> noDuration = all.stream()
                .filter(e -> e.restoredSql().startsWith("SELECT A") || e.restoredSql().startsWith("SELECT B"))
                .collect(Collectors.toList());
        assertEquals(2, noDuration.size());
        noDuration.forEach(e -> assertNull("无耗时条目读回 null", e.executionTimeMs()));
    }

    @Test
    public void 真实链路_旧F43格式文件仍可读取() throws Exception {
        // 升级前遗留的 F-43 文件（无源码位置、无耗时），历史面板应能展示
        File dir = tempDir();
        File legacy = new File(dir, "sql-history-2026-08-20.sql");
        Files.writeString(legacy.toPath(),
                "-- 2026-08-20 10:00:00\nSELECT * FROM user WHERE id = 1\n\n"
                        + "-- 2026-08-20 10:00:01\nUPDATE user SET name = 'x' WHERE id = 2\n\n");
        List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.readAll(dir.getAbsolutePath());
        assertEquals(2, entries.size());
        assertEquals("SELECT * FROM user WHERE id = 1", entries.get(0).restoredSql());
        assertEquals("UPDATE user SET name = 'x' WHERE id = 2", entries.get(1).restoredSql());
        assertNull("旧格式无源码位置", entries.get(0).sourceFile());
        assertNull("旧格式无耗时", entries.get(0).executionTimeMs());
        assertEquals("旧格式条数统计一致", 2, SqlLogFileReader.INSTANCE.countInFile(legacy));
    }

    @Test
    public void 真实链路_混合格式单文件_新老格式条目共存() throws Exception {
        // 升级过渡期：同文件内既有 F-44-2 双横线、又有历史 F-43 时间戳头
        File dir = tempDir();
        File file = new File(dir, "sql-history-2026-08-31.sql");
        Files.writeString(file.toPath(),
                "-- source: UserMapper.java:10 | time: 3ms | 2026-08-31 17:30:00.000 --\n"
                        + "SELECT 1\n\n"
                        + "-- 2026-08-31 09:00:00\n"
                        + "SELECT 2\n\n"
                        + "-- source: OrderMapper.java:20 | time: 10ms | 2026-08-31 17:31:00.000 --\n"
                        + "SELECT 3\n\n");
        List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.readFromFile(file); // 保持文件内顺序（readAll 会按 timestamp 重排）
        assertEquals(3, entries.size());
        // 新格式：源码 + 耗时
        assertEquals("UserMapper.java", entries.get(0).sourceFile());
        assertEquals(3L, entries.get(0).executionTimeMs());
        // 旧格式：无源码无耗时
        assertNull(entries.get(1).sourceFile());
        // 新格式
        assertEquals("OrderMapper.java", entries.get(2).sourceFile());
        assertEquals(10L, entries.get(2).executionTimeMs());
        assertEquals("轻量计数与完整解析一致", 3, SqlLogFileReader.INSTANCE.countInFile(file));
    }

    @Test
    public void 真实链路_空历史目录_返回空列表() throws Exception {
        File dir = tempDir();
        assertEquals(0, SqlLogFileReader.INSTANCE.readAll(dir.getAbsolutePath()).size());
        assertEquals(0, SqlLogFileReader.INSTANCE.countInFile(new File(dir, "not-exists.sql")));
    }
}
