package cn.song.mybatisplus.visualizer.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 日志条目缓冲单元测试（T-M31-03，纯逻辑）。
 */
public class SqlLogEntryBufferTest {

    private static final long DEFAULT_WINDOW = 60000L;

    private final SqlLogEntryBuffer buffer = new SqlLogEntryBuffer(3);

    private LogSqlEntry entry(String sql) {
        return entry(sql, 0L);
    }

    private LogSqlEntry entry(String sql, long ts) {
        return new LogSqlEntry(
                sql,
                sql,
                List.of(),
                ts);
    }

    @Test
    public void 新增条目最早在前() throws Exception {
        buffer.addEntry(entry("SELECT 1"));
        buffer.addEntry(entry("SELECT 2"));
        List<LogSqlEntry> snap = buffer.snapshot();
        assertEquals(2, snap.size());
        assertEquals("SELECT 1", snap.get(0).restoredSql());
        assertEquals("SELECT 2", snap.get(1).restoredSql());
    }

    @Test
    public void 超过上限裁掉最旧() throws Exception {
        buffer.addEntry(entry("SELECT 1"));
        buffer.addEntry(entry("SELECT 2"));
        buffer.addEntry(entry("SELECT 3"));
        buffer.addEntry(entry("SELECT 4"));
        List<LogSqlEntry> snap = buffer.snapshot();
        assertEquals(3, snap.size());
        assertEquals("SELECT 2", snap.get(0).restoredSql());
        assertFalse(snap.stream().anyMatch(it -> it.restoredSql().equals("SELECT 1")));
    }

    @Test
    public void 暂停态丢弃新增() throws Exception {
        buffer.setPaused(true);
        assertFalse(buffer.addEntry(entry("SELECT 1")));
        assertTrue(buffer.snapshot().isEmpty());
        buffer.setPaused(false);
        assertTrue(buffer.addEntry(entry("SELECT 1")));
    }

    @Test
    public void 清空与删除单条() throws Exception {
        LogSqlEntry e1 = entry("SELECT 1");
        LogSqlEntry e2 = entry("SELECT 2");
        buffer.addEntry(e1);
        buffer.addEntry(e2);
        buffer.removeEntry(e1);
        assertEquals(List.of("SELECT 2"), buffer.snapshot().stream().map(LogSqlEntry::restoredSql).toList());
        buffer.clear();
        assertTrue(buffer.snapshot().isEmpty());
    }

    @Test
    public void 监听器在条目变更时通知() throws Exception {
        int[] notified = {0};
        Runnable listener = () -> notified[0]++;
        buffer.addListener(listener);
        buffer.addEntry(entry("SELECT 1"));
        buffer.addEntry(entry("SELECT 2")); // 不同 SQL（去重不拦截）
        assertEquals(2, notified[0]);
        buffer.removeListener(listener);
        buffer.addEntry(entry("SELECT 3"));
        assertEquals(2, notified[0]);
    }

    // ---------- F-43：onEvicted 回调 ----------

    @Test
    public void onEvicted_回调在淘汰时触发() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(2);
        List<String> evicted = new ArrayList<>();
        buf.setOnEvicted((Consumer<LogSqlEntry>) e -> evicted.add(e.restoredSql()));
        buf.addEntry(entry("SELECT 1"));
        buf.addEntry(entry("SELECT 2"));
        buf.addEntry(entry("SELECT 3")); // 淘汰 SELECT 1
        assertEquals(List.of("SELECT 1"), evicted);
    }

    @Test
    public void onEvicted_null_时无异常() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(1);
        buf.setOnEvicted(null);
        buf.addEntry(entry("SELECT 1"));
        buf.addEntry(entry("SELECT 2")); // 淘汰 SELECT 1，无回调
        assertEquals(1, buf.snapshot().size());
        assertEquals("SELECT 2", buf.snapshot().get(0).restoredSql());
    }

    @Test
    public void 多条目连续淘汰触发多次回调() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(1);
        List<String> evicted = new ArrayList<>();
        buf.setOnEvicted((Consumer<LogSqlEntry>) e -> evicted.add(e.restoredSql()));
        buf.addEntry(entry("SELECT 1"));
        buf.addEntry(entry("SELECT 2"));
        buf.addEntry(entry("SELECT 3"));
        buf.addEntry(entry("SELECT 4"));
        assertEquals(List.of("SELECT 1", "SELECT 2", "SELECT 3"), evicted);
    }

    @Test
    public void onEvicted_回调顺序为最早到最新() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(2);
        List<String> evicted = new ArrayList<>();
        buf.setOnEvicted((Consumer<LogSqlEntry>) e -> evicted.add(e.restoredSql()));
        buf.addEntry(entry("A"));
        buf.addEntry(entry("B"));
        buf.addEntry(entry("C")); // 淘汰 A
        buf.addEntry(entry("D")); // 淘汰 B
        buf.addEntry(entry("E")); // 淘汰 C
        assertEquals(List.of("A", "B", "C"), evicted);
    }

    // ---------- 去重（对齐 MybatisCodeHelper-Pro「移除重复的sql」） ----------

    @Test
    public void 去重窗口内相同_SQL_丢弃且通知徽章() throws Exception {
        // F-44-2：用户实测「重复值计数更新的也不及时」——徽章数据变了必须通知 UI，
        // 本用例断言「首条入列通知 + 末尾丢弃通知」共 2 次。
        buffer.setDedupeEnabled(true);
        int[] notified = {0};
        buffer.addListener(() -> notified[0]++);
        assertTrue(buffer.addEntry(entry("SELECT 1", 0L)));
        assertFalse(buffer.addEntry(entry("SELECT 1", 500L))); // 窗口内
        assertEquals(2, notified[0]); // 入列 1 次 + 丢弃通知 1 次
        assertEquals(1, buffer.snapshot().size());
        assertEquals(1, buffer.dedupeCount("SELECT 1"));
    }

    @Test
    public void 去重窗口外相同_SQL_保留() throws Exception {
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("SELECT 1", 0L));
        assertTrue(buffer.addEntry(entry("SELECT 1", DEFAULT_WINDOW + 100L))); // 超窗口
        assertEquals(2, buffer.snapshot().size());
    }

    @Test
    public void 不同_SQL_不去重() throws Exception {
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("SELECT 1", 0L));
        assertTrue(buffer.addEntry(entry("SELECT 2", 100L)));
        assertEquals(2, buffer.snapshot().size());
    }

    @Test
    public void 去重开关默认开启() throws Exception {
        assertTrue(buffer.isDedupeEnabled()); // 默认开（T-M31.13 用户实测后要求）
        buffer.addEntry(entry("SELECT 1", 0L));
        assertFalse(buffer.addEntry(entry("SELECT 1", 100L))); // 窗口内重复丢弃
    }

    @Test
    public void 过滤关键字命中时丢弃() throws Exception {
        buffer.setFilterKeywords(List.of("from_dual", "TMP_"));
        assertFalse(buffer.addEntry(entry("SELECT * FROM from_dual", 0L)));
        assertFalse(buffer.addEntry(entry("SELECT * FROM TMP_LOG", 100L)));
        assertTrue(buffer.addEntry(entry("SELECT * FROM user", 200L)));
        assertEquals(1, buffer.snapshot().size());
    }

    @Test
    public void 过滤关键字为空时全部保留() throws Exception {
        buffer.setFilterKeywords(List.of());
        assertTrue(buffer.addEntry(entry("SELECT * FROM user", 0L)));
        assertEquals(1, buffer.snapshot().size());
    }

    @Test
    public void 去重开关显式关闭时全部保留() throws Exception {
        buffer.setDedupeEnabled(false);
        buffer.addEntry(entry("SELECT 1", 0L));
        assertTrue(buffer.addEntry(entry("SELECT 1", 100L)));
        assertEquals(2, buffer.snapshot().size());
    }

    // ---------- 去重丢弃计数（列表 ⨉N 徽章数据源） ----------

    @Test
    public void 去重丢弃计数随丢弃递增() throws Exception {
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("SELECT 1", 0L));
        buffer.addEntry(entry("SELECT 1", 100L)); // 丢弃 1 次
        buffer.addEntry(entry("SELECT 1", 200L)); // 丢弃 2 次
        assertEquals(2, buffer.dedupeCount("SELECT 1"));
        assertEquals(Integer.valueOf(2), buffer.dedupeCountsSnapshot().get("SELECT 1"));
    }

    @Test
    public void 去重计数键为还原SQL原文_不同SQL互不干扰() throws Exception {
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("SELECT 1", 0L));
        buffer.addEntry(entry("SELECT 2", 0L));
        buffer.addEntry(entry("SELECT 1", 100L)); // 丢弃
        assertEquals(1, buffer.dedupeCount("SELECT 1"));
        assertEquals(0, buffer.dedupeCount("SELECT 2"));
        assertEquals(0, buffer.dedupeCount("SELECT 3")); // 无记录返回 0
    }

    @Test
    public void clear_归零去重计数() throws Exception {
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("SELECT 1", 0L));
        buffer.addEntry(entry("SELECT 1", 100L)); // 丢弃 1 次
        assertEquals(1, buffer.dedupeCount("SELECT 1"));
        buffer.clear();
        assertEquals(0, buffer.dedupeCount("SELECT 1"));
    }

    @Test
    public void resetDedupeCounts_归零而不清列表() throws Exception {
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("SELECT 1", 0L));
        buffer.addEntry(entry("SELECT 1", 100L)); // 丢弃 1 次
        buffer.resetDedupeCounts();
        assertEquals(0, buffer.dedupeCount("SELECT 1"));
        assertEquals(1, buffer.snapshot().size()); // 列表条目保留
    }

    @Test
    public void 去重关闭期间不累计计数() throws Exception {
        buffer.setDedupeEnabled(false);
        buffer.addEntry(entry("SELECT 1", 0L));
        buffer.addEntry(entry("SELECT 1", 100L)); // 去重关闭，正常入列
        assertEquals(0, buffer.dedupeCount("SELECT 1"));
    }

    @Test
    public void 去重丢弃触发监听器() throws Exception {
        // F-44-2：徽章数据变了必须通知 UI（实测反馈「不及时」是缺陷）
        buffer.setDedupeEnabled(true);
        int[] notified = {0};
        buffer.addListener(() -> notified[0]++);
        buffer.addEntry(entry("SELECT 1", 0L));
        buffer.addEntry(entry("SELECT 1", 100L)); // 丢弃
        assertEquals(2, notified[0]); // 第一条入列 + 第二条丢弃
    }

    // ---------- F-44-2：dedupeExisting / disableDedupe ----------

    @Test
    public void dedupeExisting_按窗口裁剪既有条目() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        // 先关闭去重以便添加多条重复条目（验证 dedupeExisting 自身的去重逻辑，而非 addEntry 的）
        buf.setDedupeEnabled(false);
        buf.setDedupeWindowMs(1000L);
        buf.addEntry(entry("SELECT 1", 0L));
        buf.addEntry(entry("SELECT 1", 200L));
        buf.addEntry(entry("SELECT 1", 400L));
        buf.addEntry(entry("SELECT 2", 800L));
        // 不动缓冲，先确认现有 4 条都在
        assertEquals(4, buf.snapshot().size());
        // 用户勾选「移除重复的sql」→ 启用 dedupeEnabled 后调用 dedupeExisting
        buf.setDedupeEnabled(true);
        int removed = buf.dedupeExisting();
        // SELECT 1 三个 → 留首条、丢 2 个；SELECT 2 不重复 → 0 个；总移除 2
        assertEquals(2, removed);
        assertEquals(2, buf.snapshot().size());
        assertEquals(0L, buf.snapshot().get(0).timestamp()); // 首条保留
        assertEquals("SELECT 2", buf.snapshot().get(1).restoredSql());
        assertEquals(2, buf.dedupeCount("SELECT 1"));
    }

    @Test
    public void dedupeExisting_关闭时不生效() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(false);
        buf.setDedupeWindowMs(1000L);
        buf.addEntry(entry("SELECT 1", 0L));
        buf.addEntry(entry("SELECT 1", 100L));
        assertEquals(0, buf.dedupeExisting());
        assertEquals(2, buf.snapshot().size());
    }

    @Test
    public void dedupeExisting_单条不触发() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(true);
        buf.addEntry(entry("SELECT 1", 0L));
        assertEquals(0, buf.dedupeExisting());
        assertEquals(1, buf.snapshot().size());
    }

    @Test
    public void dedupeExisting_超窗口保留所有() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        // 同上：先关闭以让多条重复都入列
        buf.setDedupeEnabled(false);
        buf.setDedupeWindowMs(1000L);
        buf.addEntry(entry("SELECT 1", 0L));
        buf.addEntry(entry("SELECT 1", 2000L)); // 超 1s 窗口
        assertEquals(2, buf.snapshot().size());
        // 再开启 dedupeEnabled 后调用 dedupeExisting，验证窗口判定
        buf.setDedupeEnabled(true);
        assertEquals(0, buf.dedupeExisting());
        assertEquals(2, buf.snapshot().size());
    }

    @Test
    public void disableDedupe_清空累计计数() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(false);
        buf.addEntry(entry("SELECT 1", 0L));
        buf.addEntry(entry("SELECT 1", 100L));
        // 关闭态 → 入列，不累计计数
        assertEquals(0, buf.dedupeCount("SELECT 1"));
        buf.setDedupeEnabled(true);
        buf.addEntry(entry("SELECT 1", 200L)); // 现在窗口内，去重 +1
        assertEquals(1, buf.dedupeCount("SELECT 1"));
        buf.disableDedupe();
        assertEquals(0, buf.dedupeCount("SELECT 1"));
        assertFalse(buf.isDedupeEnabled());
    }

    @Test
    public void disableDedupe_重复调用幂等() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(true);
        buf.addEntry(entry("SELECT 1", 0L));
        buf.disableDedupe();
        // 二次调用不应抛异常/重置状态报错
        buf.disableDedupe();
        assertEquals(0, buf.dedupeCount("SELECT 1"));
    }
}
