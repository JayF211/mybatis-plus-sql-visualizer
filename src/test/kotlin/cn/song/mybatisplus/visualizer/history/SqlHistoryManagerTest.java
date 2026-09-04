package cn.song.mybatisplus.visualizer.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SqlHistoryManager 单元测试（F-7）：添加 / 上限淘汰 / 收藏豁免 / 标签 / 搜索 / 持久化往返。
 *
 * 直接实例化 SqlHistoryManager（不依赖平台服务容器），验证核心逻辑与持久化契约。
 */
public class SqlHistoryManagerTest {

    private static SqlHistoryEntry entry() {
        return entry("SELECT * FROM user", false, new ArrayList<>());
    }

    private static SqlHistoryEntry entry(String sql) {
        return entry(sql, false, new ArrayList<>());
    }

    private static SqlHistoryEntry entry(String sql, boolean favorite, List<String> tags) {
        return new SqlHistoryEntry("", sql, sql, System.currentTimeMillis(), "UserMapper.java", 10, "user",
                favorite, new ArrayList<>(tags));
    }

    private static Set<String> setOf(String... items) {
        Set<String> s = new HashSet<>();
        for (String it : items) {
            s.add(it);
        }
        return s;
    }

    private static List<String> sqls(List<SqlHistoryEntry> entries) {
        return entries.stream().map(SqlHistoryEntry::getSql).collect(Collectors.toList());
    }

    // ---------- 添加 / 查询 ----------

    @Test
    public void add_increases_entry_count() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        assertEquals(0, manager.getEntries().size());
        manager.add(entry("SELECT 1"));
        assertEquals(1, manager.getEntries().size());
        assertEquals("SELECT 1", manager.getEntries().get(0).getSql());
    }

    @Test
    public void add_assigns_unique_id_when_blank() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        SqlHistoryEntry e = entry();
        assertTrue(e.getId().isBlank());
        manager.add(e);
        assertFalse(e.getId().isBlank());
    }

    @Test
    public void entries_returned_newest_first() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        SqlHistoryEntry first = entry("FIRST");
        first.setTimestamp(1000L);
        manager.add(first);
        SqlHistoryEntry second = entry("SECOND");
        second.setTimestamp(2000L);
        manager.add(second);
        SqlHistoryEntry third = entry("THIRD");
        third.setTimestamp(3000L);
        manager.add(third);
        // 最新在前
        assertEquals("THIRD", manager.getEntries().get(0).getSql());
        assertEquals("SECOND", manager.getEntries().get(1).getSql());
        assertEquals("FIRST", manager.getEntries().get(2).getSql());
    }

    // ---------- 上限淘汰 ----------

    @Test
    public void evicts_oldest_non_favorite_when_over_limit() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        // 默认上限 200，添加 200 条
        for (int i = 0; i < 200; i++) {
            SqlHistoryEntry e = entry("SQL_" + i);
            e.setTimestamp((long) i * 1000L);
            manager.add(e);
        }
        assertEquals(200, manager.getEntries().size());
        SqlHistoryEntry n = entry("NEW");
        n.setTimestamp(99999L);
        manager.add(n);
        assertEquals("添加第 201 条后应仍保持 200 条", 200, manager.getEntries().size());
        // 最旧的非收藏条目 (SQL_0) 被淘汰
        List<String> sqls = sqls(manager.getEntries());
        assertFalse("最旧条目应被淘汰", sqls.contains("SQL_0"));
        assertTrue("最新条目应存在", sqls.contains("NEW"));
        assertTrue("SQL_1 应保留", sqls.contains("SQL_1"));
    }

    @Test
    public void favorite_entries_are_never_evicted() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        // 添加 1 条收藏 + 200 条非收藏 = 201 条
        SqlHistoryEntry fav = entry("FAV");
        fav.setFavorite(true);
        fav.setTimestamp(1L);
        manager.add(fav);
        for (int i = 0; i < 200; i++) {
            SqlHistoryEntry e = entry("SQL_" + i);
            e.setTimestamp((long) (i + 2) * 1000L);
            manager.add(e);
        }
        assertEquals(201, manager.getEntries().size());
        // 再添加一条触发淘汰（非收藏上限 200）
        SqlHistoryEntry n = entry("NEW");
        n.setTimestamp(99999L);
        manager.add(n);
        // 淘汰 1 条最旧非收藏 → 1 收藏 + 200 非收藏 = 201
        assertEquals(201, manager.getEntries().size());
        // 收藏条目不被淘汰
        boolean anyFav = manager.getEntries().stream()
                .anyMatch(it -> "FAV".equals(it.getSql()) && it.isFavorite());
        assertTrue("收藏条目应保留", anyFav);
    }

    @Test
    public void respects_custom_max_size() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        manager.setMaxSize(5);
        for (int i = 0; i < 7; i++) {
            manager.add(entry("SQL_" + i));
        }
        assertEquals("自定义上限 5 条", 5, manager.getEntries().size());
        List<String> sqls = sqls(manager.getEntries());
        assertTrue(sqls.contains("SQL_6"));
        assertTrue(sqls.contains("SQL_5"));
        assertFalse(sqls.contains("SQL_0"));
    }

    // ---------- 收藏 ----------

    @Test
    public void toggleFavorite_flips_state() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        manager.add(entry("SELECT 1"));
        String id = manager.getEntries().get(0).getId();
        assertFalse(manager.getEntries().get(0).isFavorite());
        manager.toggleFavorite(id);
        assertTrue(manager.getEntries().get(0).isFavorite());
        manager.toggleFavorite(id);
        assertFalse(manager.getEntries().get(0).isFavorite());
    }

    @Test
    public void toggleFavorite_on_unknown_id_is_no_op() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        manager.add(entry());
        // 不应抛异常
        manager.toggleFavorite("nonexistent-id");
        assertEquals(1, manager.getEntries().size());
    }

    // ---------- 标签 ----------

    @Test
    public void addTag_and_removeTag() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        manager.add(entry());
        String id = manager.getEntries().get(0).getId();
        manager.addTag(id, "慢查询");
        manager.addTag(id, "联表");
        assertEquals(setOf("慢查询", "联表"), new HashSet<>(manager.getEntries().get(0).getTags()));
        manager.removeTag(id, "慢查询");
        assertEquals(setOf("联表"), new HashSet<>(manager.getEntries().get(0).getTags()));
    }

    @Test
    public void addTag_ignores_duplicate() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        manager.add(entry());
        String id = manager.getEntries().get(0).getId();
        manager.addTag(id, "慢查询");
        manager.addTag(id, "慢查询");
        assertEquals(1, manager.getEntries().get(0).getTags().size());
    }

    @Test
    public void allTags_returns_union_of_all_tags() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        SqlHistoryEntry e1 = entry();
        e1.getTags().add("慢查询");
        manager.add(e1);
        SqlHistoryEntry e2 = entry();
        e2.getTags().add("联表");
        manager.add(e2);
        SqlHistoryEntry e3 = entry();
        e3.getTags().add("慢查询");
        e3.getTags().add("待优化");
        manager.add(e3);
        assertEquals(setOf("慢查询", "联表", "待优化"), manager.allTags());
    }

    // ---------- 搜索 / 筛选 ----------

    @Test
    public void search_filters_by_substring_ignore_case() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        manager.add(entry("SELECT * FROM user WHERE id = 1"));
        manager.add(entry("SELECT * FROM order WHERE user_id = 1"));
        manager.add(entry("UPDATE product SET name = 'x'"));
        List<SqlHistoryEntry> result = manager.search("user");
        // user / user_id 含子串 "user"，product 不含 → 命中 2 条
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(it -> it.getSql().toLowerCase(Locale.ROOT).contains("user")));
    }

    @Test
    public void search_empty_query_returns_all() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        manager.add(entry("A"));
        manager.add(entry("B"));
        assertEquals(2, manager.search("").size());
    }

    @Test
    public void filterByTag_returns_matching_entries() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        SqlHistoryEntry e1 = entry("SELECT 1");
        e1.getTags().add("慢查询");
        manager.add(e1);
        manager.add(entry("SELECT 2"));
        SqlHistoryEntry e3 = entry("SELECT 3");
        e3.getTags().add("慢查询");
        manager.add(e3);
        List<SqlHistoryEntry> result = manager.filterByTag("慢查询");
        assertEquals(2, result.size());
    }

    @Test
    public void filterFavorites_returns_only_favorites() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        SqlHistoryEntry a = entry("A");
        a.setFavorite(true);
        manager.add(a);
        manager.add(entry("B"));
        SqlHistoryEntry c = entry("C");
        c.setFavorite(true);
        manager.add(c);
        List<SqlHistoryEntry> result = manager.filterFavorites();
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(SqlHistoryEntry::isFavorite));
    }

    // ---------- 删除 / 清空 ----------

    @Test
    public void remove_deletes_by_id() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        manager.add(entry("A"));
        String id = manager.getEntries().get(0).getId();
        manager.remove(id);
        assertEquals(0, manager.getEntries().size());
    }

    @Test
    public void clear_removes_all() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        manager.add(entry("A"));
        manager.add(entry("B"));
        manager.clear();
        assertEquals(0, manager.getEntries().size());
    }

    // ---------- 持久化往返 ----------

    @Test
    public void state_round_trip_preserves_entries_and_favorites_and_tags() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        SqlHistoryEntry e1 = entry("SELECT 1");
        e1.setFavorite(true);
        e1.getTags().add("慢查询");
        e1.setTimestamp(12345L);
        manager.add(e1);
        manager.add(entry("UPDATE t SET x = 1"));
        SqlHistoryManager.State state = manager.getState();
        // 收藏条目进 favoriteEntries 分区，非收藏进 entries 分区
        assertEquals(1, state.getEntries().size());
        assertEquals(1, state.getFavoriteEntries().size());
        // 模拟重启：新实例 loadState
        SqlHistoryManager fresh = new SqlHistoryManager();
        fresh.loadState(state);
        assertEquals(2, fresh.getEntries().size());
        SqlHistoryEntry fav = fresh.getEntries().stream()
                .filter(it -> "SELECT 1".equals(it.getSql())).findFirst().get();
        assertTrue("收藏状态应保留", fav.isFavorite());
        assertEquals(List.of("慢查询"), fav.getTags());
        assertEquals(12345L, fav.getTimestamp());
    }

    @Test
    public void loadState_with_corrupt_data_falls_back_gracefully() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        // 空状态（模拟损坏/空文件）不抛异常
        manager.loadState(new SqlHistoryManager.State());
        assertEquals(0, manager.getEntries().size());
    }

    @Test
    public void loadState_replaces_existing_entries() throws Exception {
        SqlHistoryManager manager = new SqlHistoryManager();
        manager.add(entry("OLD"));
        assertEquals(1, manager.getEntries().size());
        SqlHistoryManager.State newState = new SqlHistoryManager.State(
                new ArrayList<>(List.of(entry("NEW"))), new ArrayList<>());
        manager.loadState(newState);
        assertEquals(1, manager.getEntries().size());
        assertEquals("NEW", manager.getEntries().get(0).getSql());
    }
}
