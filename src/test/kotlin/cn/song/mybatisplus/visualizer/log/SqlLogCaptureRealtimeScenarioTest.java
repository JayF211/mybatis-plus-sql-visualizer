package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 真实控制台日志捕获链路测试（F-44-2 耗时接入 + 去重实时通知 的真实场景）：
 *
 * 模拟 Spring Boot 运行控制台输出——真实 MyBatis 日志文本逐行喂入 [MyBatisLogParser]，
 * 组装 LogSqlEntry 时复用 bridge 的耗时解析逻辑（[ExecutionTimeParser#parse] + entry 重建），
 * 再入 [SqlLogEntryBuffer] 验证：耗时字段写入、去重窗口内留首条、⨉N 徽章数据实时更新。
 */
public class SqlLogCaptureRealtimeScenarioTest {

    private MyBatisLogParser parser() {
        return new MyBatisLogParser();
    }

    /**
     * 模拟 SqlLogConsoleBridge.FilterProxyHandler 的耗时组装逻辑（与生产实现逐行一致）：
     * 从原始控制台行解析耗时 → 写入 entry.executionTimeMs。
     */
    private List<LogSqlEntry> assembleWithDuration(
            List<LogSqlEntry> entries,
            String rawLine,
            String customPrefix) {
        String prefix = (customPrefix != null && !customPrefix.isBlank()) ? customPrefix : null;
        Long time = ExecutionTimeParser.parse(rawLine, prefix);
        List<LogSqlEntry> result = new ArrayList<>();
        for (LogSqlEntry e : entries) {
            result.add(new LogSqlEntry(
                    e.rawSql(),
                    e.restoredSql(),
                    e.params(),
                    e.timestamp(),
                    e.summary(),
                    e.source(),
                    time != null ? time : e.executionTimeMs(),
                    e.sourceFile(),
                    e.sourceLine(),
                    0L));
        }
        return result;
    }

    private List<LogSqlEntry> feedAll(MyBatisLogParser p, String... lines) {
        for (String line : lines) {
            p.feed(line);
        }
        p.closePending();
        return p.takeEntries();
    }

    // ---------- 真实场景 1：登录接口多表查询（Preparing/Parameters 配对） ----------

    @Test
    public void 真实日志_登录查询_参数还原与耗时null() throws Exception {
        String[] lines = {
                "2026-08-31 17:30:00.001  INFO 1234 --- [nio-8080-exec-1] c.s.demo.mapper.UserMapper.selectOne : ==>  Preparing: SELECT id,username,password,create_time FROM user WHERE username = ? AND deleted = 0",
                "2026-08-31 17:30:00.002  INFO 1234 --- [nio-8080-exec-1] c.s.demo.mapper.UserMapper.selectOne : ==> Parameters: admin(String)",
        };
        // 真实控制台不含耗时行 → executionTimeMs = null（列表显示无耗时，排序按钮隐藏）
        List<LogSqlEntry> entries = assembleWithDuration(feedAll(parser(), lines), lines[lines.length - 1], null);
        assertEquals(1, entries.size());
        assertEquals(
                "SELECT id,username,password,create_time FROM user WHERE username = 'admin' AND deleted = 0",
                entries.get(0).restoredSql());
        assertNull("无耗时行 → null", entries.get(0).executionTimeMs());
    }

    @Test
    public void 真实日志_带p6spy耗时行的批量插入_耗时5ms() throws Exception {
        // p6spy 控制台：日志行含 elapsed 耗时 → 捕获时写入 entry.executionTimeMs
        String p6spyLine = "2026-08-31 17:31:00.123  INFO 1234 --- [pool-2-thread-1] p6spy : #1725000000000 | elapsed 5 ms | statement | INSERT INTO user_order (user_id, amount) VALUES (?, ?)";
        // p6spy 语句行不含 `Preparing:` 前缀——此处用标准 MyBatis 前缀形态验证：
        // SQL 从 Preparing/Parameters 配对，耗时从同帧 p6spy elapsed 行解析（FilterProxyHandler 单帧取行）
        List<LogSqlEntry> entries = assembleWithDuration(
                feedAll(
                        parser(),
                        "==>  Preparing: INSERT INTO user_order (user_id, amount) VALUES (?, ?)",
                        "==> Parameters: 1(Long), 99.5(BigDecimal)"),
                p6spyLine, // 耗时来自 p6spy elapsed 行
                null);
        assertEquals(1, entries.size());
        assertEquals(5L, (long) entries.get(0).executionTimeMs());
        assertEquals("INSERT INTO user_order (user_id, amount) VALUES (1, 99.5)", entries.get(0).restoredSql());
    }

    @Test
    public void 真实日志_自定义耗时前缀Time_耗时200ms() throws Exception {
        // 用户配置 logTimePrefix=Time: 后，日志 `Time: 200ms` 行被解析
        List<LogSqlEntry> entries = assembleWithDuration(
                feedAll(
                        parser(),
                        "==>  Preparing: SELECT * FROM big_table WHERE created_at > ?",
                        "==> Parameters: 2026-08-31 17:00:00.0(Timestamp)",
                        "Time: 200ms"),
                "Time: 200ms",
                "Time:");
        assertEquals(1, entries.size());
        assertEquals(200L, (long) entries.get(0).executionTimeMs());
        assertTrue(entries.get(0).restoredSql().contains("'2026-08-31 17:00:00.0'"));
    }

    // ---------- 真实场景 2：分页查询连续 5 次 → 去重留 1 条 + 徽章 4 ----------

    @Test
    public void 真实场景_分页查询连续5次_去重留1条徽章4() throws Exception {
        SqlLogEntryBuffer buffer = new SqlLogEntryBuffer(200);
        buffer.setDedupeEnabled(true);
        buffer.setDedupeWindowMs(60000L);
        int[] notified = {0};
        buffer.addListener(() -> notified[0]++);

        // 真实分页：同一页 SQL 因不同页码参数不同 → 不同 SQL；模拟「同一查询重复执行 5 次」
        String sql = "SELECT id,username FROM user WHERE status = 'ACTIVE' ORDER BY id LIMIT 10";
        for (int i = 0; i < 5; i++) {
            LogSqlEntry entry = new LogSqlEntry(
                    sql,
                    sql,
                    List.of(),
                    1000L * i); // 同窗口内
            buffer.addEntry(entry);
        }

        assertEquals("窗口内 5 次执行只留 1 条", 1, buffer.snapshot().size());
        assertEquals("徽章 ⨉4", 4, buffer.dedupeCount(sql));
        assertEquals("5 次入列 + 4 次丢弃共通知 5 次", 5, notified[0]);
    }

    @Test
    public void 真实场景_超窗口后相同SQL重新计数() throws Exception {
        SqlLogEntryBuffer buffer = new SqlLogEntryBuffer(200);
        buffer.setDedupeEnabled(true);
        buffer.setDedupeWindowMs(1000L);
        String sql = "SELECT COUNT(*) FROM orders WHERE user_id = 7";

        // 第一波：3 次（窗口内）→ 留 1 条、徽章 2
        for (int i = 0; i < 3; i++) {
            buffer.addEntry(new LogSqlEntry(sql, sql, List.of(), 100L * i));
        }
        assertEquals(1, buffer.snapshot().size());
        assertEquals(2, buffer.dedupeCount(sql));

        // 第二波：窗口滑过后（>1s）再执行 → 新条目入列，留 2 条
        buffer.addEntry(new LogSqlEntry(sql, sql, List.of(), 2000L));
        assertEquals("超窗口后同 SQL 重新入列", 2, buffer.snapshot().size());
        assertEquals("第二波丢弃计数继续累计（此时无窗口内重复）", 2, buffer.dedupeCount(sql));
    }

    @Test
    public void 真实场景_接口交错执行不同SQL_去重互不影响() throws Exception {
        SqlLogEntryBuffer buffer = new SqlLogEntryBuffer(200);
        buffer.setDedupeEnabled(true);
        buffer.setDedupeWindowMs(60000L);
        // 真实接口并发：查询用户 / 更新订单 / 删除日志 交错
        String select = "SELECT * FROM user WHERE id = 1";
        String update = "UPDATE orders SET status = 'PAID' WHERE id = 99";
        String delete = "DELETE FROM operation_log WHERE id = 100";

        buffer.addEntry(new LogSqlEntry(select, select, List.of(), 0L));
        buffer.addEntry(new LogSqlEntry(update, update, List.of(), 10L));
        buffer.addEntry(new LogSqlEntry(delete, delete, List.of(), 20L));
        buffer.addEntry(new LogSqlEntry(select, select, List.of(), 30L)); // 丢弃
        buffer.addEntry(new LogSqlEntry(update, update, List.of(), 40L)); // 丢弃
        buffer.addEntry(new LogSqlEntry(delete, delete, List.of(), 50L)); // 丢弃

        assertEquals("不同 SQL 各留 1 条", 3, buffer.snapshot().size());
        assertEquals(1, buffer.dedupeCount(select));
        assertEquals(1, buffer.dedupeCount(update));
        assertEquals(1, buffer.dedupeCount(delete));
    }

    // ---------- 真实场景 3：勾选「移除重复的sql」前后 ----------

    @Test
    public void 真实场景_未开去重累积后勾选_立即裁剪并累计徽章() throws Exception {
        SqlLogEntryBuffer buffer = new SqlLogEntryBuffer(200);
        buffer.setDedupeEnabled(false); // 用户未勾选，重复 SQL 全保留
        String sql = "SELECT * FROM user WHERE id = 42";
        for (int i = 0; i < 4; i++) {
            buffer.addEntry(new LogSqlEntry(sql, sql, List.of(), 100L * i));
        }
        assertEquals("未勾选时 4 条重复全展示", 4, buffer.snapshot().size());
        assertEquals("未勾选不累计计数", 0, buffer.dedupeCount(sql));

        // 用户勾选「移除重复的sql」→ 面板调 dedupeEnabled=true + dedupeExisting()
        buffer.setDedupeEnabled(true);
        int removed = buffer.dedupeExisting();
        assertEquals("立即裁剪 3 条", 3, removed);
        assertEquals("只留 1 条", 1, buffer.snapshot().size());
        assertEquals("徽章累计 3", 3, buffer.dedupeCount(sql));

        // 后续实时去重继续累计：又执行 1 次
        buffer.addEntry(new LogSqlEntry(sql, sql, List.of(), 500L));
        assertEquals("徽章 3+1=4", 4, buffer.dedupeCount(sql));
        assertEquals(1, buffer.snapshot().size());
    }

    @Test
    public void 真实场景_关闭去重清空徽章_再开启从零起算() throws Exception {
        SqlLogEntryBuffer buffer = new SqlLogEntryBuffer(200);
        buffer.setDedupeEnabled(false);
        String sql = "SELECT 1";
        for (int i = 0; i < 3; i++) {
            buffer.addEntry(new LogSqlEntry(sql, sql, List.of(), 10L * i));
        }
        buffer.setDedupeEnabled(true);
        buffer.dedupeExisting(); // 裁 2 条
        assertEquals(2, buffer.dedupeCount(sql));

        // 用户关闭去重 → 累计清零
        buffer.disableDedupe();
        assertEquals("关闭清空徽章", 0, buffer.dedupeCount(sql));
        assertFalse(buffer.isDedupeEnabled());

        // 重新开启 → 从零起算
        buffer.setDedupeEnabled(true);
        buffer.addEntry(new LogSqlEntry(sql, sql, List.of(), 1000L)); // 与保留的旧条目同窗口 → 丢弃
        assertEquals("重新开启后从 1 起算", 1, buffer.dedupeCount(sql));
    }

    // ---------- 真实场景 4：耗时字段参与面板排序/着色数据源 ----------

    @Test
    public void 真实场景_耗时解析结果供排序与慢查询判定() throws Exception {
        // 真实 p6spy 日志：rawSql 为 `时间戳|毫秒|statement|SQL`——LogDurationParser 从 rawSql 解析，
        // 而显示层读 executionTimeMs（FilterProxyHandler 同帧解析 elapsed 行写入）。
        // 两条数据源在真实 p6spy 日志中同源（同一行的 elapsed 与 |ms| 一致），此处对齐模拟。
        java.util.function.BiFunction<Long, String, LogSqlEntry> p6spyEntry = (ms, sql) -> new LogSqlEntry(
                "1723456789012|" + ms + "|statement|" + sql,
                sql,
                List.of(),
                0L,
                null,
                0,
                ms); // bridge 从 elapsed 行解析后写入
        LogSqlEntry e1 = p6spyEntry.apply(5L, "SELECT 1");
        LogSqlEntry e2 = p6spyEntry.apply(200L, "SELECT 2");
        LogSqlEntry e3 = p6spyEntry.apply(1500L, "SELECT 3");

        // 排序：耗时降序（面板 SortMode.DURATION_DESC 同款逻辑）
        List<LogSqlEntry> sorted = new ArrayList<>(List.of(e1, e2, e3));
        sorted.sort(Comparator.comparingLong(
                (LogSqlEntry e) -> LogDurationParser.parse(e) != null ? LogDurationParser.parse(e) : -1L).reversed());
        assertEquals("SELECT 3 (1500ms) 最前", "SELECT 3", sorted.get(0).restoredSql());
        assertEquals("SELECT 2 (200ms) 中间", "SELECT 2", sorted.get(1).restoredSql());
        assertEquals("SELECT 1 (5ms) 最后", "SELECT 1", sorted.get(2).restoredSql());

        // 慢查询判定：阈值 1000ms
        long threshold = PluginSettings.DEFAULT_SLOW_QUERY_THRESHOLD_MS;
        assertEquals("1500ms 是慢查询", true, SlowQueryDetector.isSlow(e3.executionTimeMs(), threshold));
        assertEquals("200ms 不是慢查询", false, SlowQueryDetector.isSlow(e2.executionTimeMs(), threshold));
    }
}
