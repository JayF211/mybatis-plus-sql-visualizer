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
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(3);
        buf.setDedupeEnabled(false); // 本用例验证删除语义（与去重开关态隔离）
        LogSqlEntry e1 = entry("SELECT 1");
        LogSqlEntry e2 = entry("SELECT 2");
        buf.addEntry(e1);
        buf.addEntry(e2);
        // T-LogRowNumber：面板从列表模型拿到的是入库实例（带行号 copy），与 e1 同一
        LogSqlEntry storedOne = buf.snapshot().stream()
                .filter(it -> it.restoredSql().equals("SELECT 1")).findFirst().orElseThrow();
        buf.removeEntry(storedOne);
        assertEquals(List.of("SELECT 2"), buf.snapshot().stream().map(LogSqlEntry::restoredSql).toList());
        buf.clear();
        assertTrue(buf.snapshot().isEmpty());
    }

    @Test
    public void removeEntry按同一性删除_带行号实例可删_无号原型不误删() throws Exception {
        // T-LogRowNumber：入库为带号 copy；面板引用=入库实例（同一性成立可删）
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(false);
        buf.addEntry(entry("SELECT 1", 0L));
        LogSqlEntry stored = buf.snapshot().get(0);
        assertEquals(1L, stored.sequenceNumber());
        buf.removeEntry(stored);
        assertTrue(buf.snapshot().isEmpty());
        // 无号原型（外部构造的等值条目）不应误删列表中的真身
        LogSqlEntry proto = entry("SELECT 2", 100L);
        buf.addEntry(proto);
        buf.removeEntry(proto);
        assertEquals(1, buf.snapshot().size());
        assertEquals("SELECT 2", buf.snapshot().get(0).restoredSql());
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

    // ---------- T-LogDedupeMpMark：跨格式副本（仅差首尾空白）判重 ----------

    @Test
    public void 尾随空白差异判重命中() throws Exception {
        // JdbcTemplate 双路日志：named 副本 trim、prepared 副本尾随空白原样保留
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("SELECT 1", 0L));
        assertFalse(buffer.addEntry(entry("SELECT 1  ", 100L)));
        assertEquals(1, buffer.snapshot().size());
    }

    @Test
    public void 首部与内部连续空白差异判重命中() throws Exception {
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("SELECT * FROM t WHERE id = 1", 0L));
        assertFalse(buffer.addEntry(entry("  SELECT *  FROM t WHERE id = 1", 100L)));
        assertEquals(1, buffer.snapshot().size());
    }

    @Test
    public void 空白差异副本丢弃计数并入同一徽章键() throws Exception {
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("SELECT 1", 0L));
        buffer.addEntry(entry("SELECT 1 ", 100L));
        buffer.addEntry(entry("  SELECT 1", 200L));
        assertEquals(2, buffer.dedupeCount("SELECT 1"));
    }

    @Test
    public void 空白规范化不改大小写与内容_不同SQL仍不去重() throws Exception {
        buffer.setDedupeEnabled(true);
        buffer.addEntry(entry("select 1", 0L));
        assertTrue(buffer.addEntry(entry("SELECT 1", 100L)));
        assertTrue(buffer.addEntry(entry("SELECT 1 WHERE", 200L)));
        assertEquals(3, buffer.snapshot().size());
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
        // T-Perf-19：快照键为 SQL 指纹（非原文）——经 sqlFingerprint 查键
        assertEquals(Integer.valueOf(2), buffer.dedupeCountsSnapshot().get(LogSqlModelsKt.sqlFingerprint("SELECT 1")));
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
    public void dedupeExisting_空白差异副本按同键裁剪() throws Exception {
        // T-LogDedupeMpMark：勾选去重对既有跨格式副本（named trim / prepared 尾随空白）同样生效
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(false);
        buf.setDedupeWindowMs(1000L);
        buf.addEntry(entry("SELECT 1", 0L));
        buf.addEntry(entry("SELECT 1  ", 200L));
        buf.setDedupeEnabled(true);
        assertEquals(1, buf.dedupeExisting());
        assertEquals(1, buf.snapshot().size());
        assertEquals("SELECT 1", buf.snapshot().get(0).restoredSql());
        assertEquals(1, buf.dedupeCount("SELECT 1"));
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
    public void dedupeExisting_跨窗口重复也裁剪() throws Exception {
        // T-LogRowNumber 追加：手动全量裁剪不看时间窗口——跨窗口（>dedupeWindowMs）的重复也必须裁掉，
        // 否则用户点击「移除重复」后重复项仍在列表（用户反馈「显示不正确」根因）。
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(false);
        buf.setDedupeWindowMs(1000L);
        buf.addEntry(entry("SELECT 1", 0L));
        buf.addEntry(entry("SELECT 1", 2000L)); // 超 1s 窗口
        assertEquals(2, buf.snapshot().size());
        buf.setDedupeEnabled(true);
        assertEquals(1, buf.dedupeExisting()); // 跨窗口仍裁 1 条
        assertEquals(1, buf.snapshot().size());
        assertEquals(1, buf.dedupeCount("SELECT 1"));
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

    // ---------- T-LogRowNumber：捕获序号（列表行号） ----------

    @Test
    public void 行号连续入队单调递增() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.addEntry(entry("SELECT 1", 0L));
        buf.addEntry(entry("SELECT 2", 100L));
        buf.addEntry(entry("SELECT 3", 200L));
        List<Long> seqs = buf.snapshot().stream().map(LogSqlEntry::sequenceNumber).toList();
        assertEquals(List.of(1L, 2L, 3L), seqs);
    }

    @Test
    public void 去重丢弃不占号() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(true);
        buf.addEntry(entry("SELECT 1", 0L));                 // #1
        assertFalse(buf.addEntry(entry("SELECT 1", 100L)));  // 丢弃
        buf.addEntry(entry("SELECT 2", 200L));               // 应为 #2（不跳号）
        List<Long> seqs = buf.snapshot().stream().map(LogSqlEntry::sequenceNumber).toList();
        assertEquals(List.of(1L, 2L), seqs);
    }

    @Test
    public void 暂停态与过滤关键字不占号() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(false); // 本用例专验暂停/过滤分支不占号（与既有去重用例隔离）
        buf.addEntry(entry("SELECT 1", 0L));  // #1
        buf.setPaused(true);
        assertFalse(buf.addEntry(entry("SELECT X", 100L)));
        buf.setPaused(false);
        buf.setFilterKeywords(List.of("TMP_"));
        assertFalse(buf.addEntry(entry("SELECT TMP_LOG", 200L)));
        buf.setFilterKeywords(List.of());
        buf.addEntry(entry("SELECT 2", 300L)); // 应为 #2
        List<Long> seqs = buf.snapshot().stream().map(LogSqlEntry::sequenceNumber).toList();
        assertEquals(List.of(1L, 2L), seqs);
    }

    @Test
    public void clear后行号重新从1计数() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.addEntry(entry("SELECT 1", 0L));
        buf.addEntry(entry("SELECT 2", 100L));
        buf.clear();
        buf.addEntry(entry("SELECT 3", 200L));
        List<LogSqlEntry> snap = buf.snapshot();
        assertEquals(1, snap.size());
        assertEquals(1L, snap.get(0).sequenceNumber());
    }

    @Test
    public void resetEntrySequence后新条目从1起旧条目行号保留() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.addEntry(entry("SELECT 1", 0L));  // #1
        buf.addEntry(entry("SELECT 2", 100L)); // #2
        buf.resetEntrySequence();
        // 旧条目不清、行号保留原值
        assertEquals(List.of(1L, 2L), buf.snapshot().stream().map(LogSqlEntry::sequenceNumber).toList());
        buf.addEntry(entry("SELECT 3", 200L)); // 新一轮 #1
        assertEquals(1L, buf.snapshot().get(buf.snapshot().size() - 1).sequenceNumber());
    }

    @Test
    public void dedupeExisting裁剪后剩余条目行号保留原值() throws Exception {
        // T-LogRowNumber 用户确认：裁剪只删条目不重新编号，跳号表达「重复被移除」
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(10);
        buf.setDedupeEnabled(false);
        buf.setDedupeWindowMs(1000L);
        buf.addEntry(entry("SELECT 1", 0L));    // #1
        buf.addEntry(entry("SELECT 1", 200L));  // #2（重复副本）
        buf.addEntry(entry("SELECT 2", 400L));  // #3
        buf.setDedupeEnabled(true);
        assertEquals(1, buf.dedupeExisting()); // 裁掉 #2
        List<LogSqlEntry> snap = buf.snapshot();
        assertEquals(List.of("SELECT 1", "SELECT 2"), snap.stream().map(LogSqlEntry::restoredSql).toList());
        assertEquals(List.of(1L, 3L), snap.stream().map(LogSqlEntry::sequenceNumber).toList()); // 跳号：#2 不复用
    }

    @Test
    public void 头部淘汰后剩余条目行号保留原值() throws Exception {
        SqlLogEntryBuffer buf = new SqlLogEntryBuffer(2);
        buf.addEntry(entry("SELECT 1", 0L));    // #1，后被淘汰
        buf.addEntry(entry("SELECT 2", 100L));  // #2
        buf.addEntry(entry("SELECT 3", 200L));  // #3，触发淘汰 #1
        List<LogSqlEntry> snap = buf.snapshot();
        assertEquals(List.of("SELECT 2", "SELECT 3"), snap.stream().map(LogSqlEntry::restoredSql).toList());
        assertEquals(List.of(2L, 3L), snap.stream().map(LogSqlEntry::sequenceNumber).toList());
    }
}
