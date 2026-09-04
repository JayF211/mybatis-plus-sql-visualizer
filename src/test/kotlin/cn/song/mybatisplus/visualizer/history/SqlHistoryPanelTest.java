package cn.song.mybatisplus.visualizer.history;

import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

/**
 * SqlHistoryPanel 过滤/搜索/筛选逻辑测试（F-7）：聚焦「多条件组合」与「视图编排」。
 */
public class SqlHistoryPanelTest {

    private SqlHistoryManager manager;

    @Before
    public void setUp() throws Exception {
        manager = new SqlHistoryManager();
        manager.clear();
    }

    @After
    public void tearDown() throws Exception {
        manager.clear();
    }

    private static SqlHistoryEntry entry(String sql, boolean favorite, List<String> tags, long ts) {
        return new SqlHistoryEntry("", sql, sql, ts, "UserMapper.java", 10, "user", favorite, tags);
    }

    // ---------- 视图编排（收藏 + 非收藏合并为单一倒序视图） ----------

    @Test
    public void panelViewMergesFavoriteAndNonFavoriteDescendingByTime() throws Exception {
        manager.add(entry("FAV", true, List.of(), 1000L));
        manager.add(entry("NORMAL_NEW", false, List.of(), 3000L));
        manager.add(entry("NORMAL_OLD", false, List.of(), 2000L));
        List<SqlHistoryEntry> view = manager.getEntries();
        assertEquals(3, view.size());
        assertEquals(List.of("NORMAL_NEW", "NORMAL_OLD", "FAV"),
                view.stream().map(SqlHistoryEntry::getSql).collect(Collectors.toList()));
    }

    @Test
    public void favoriteMarkRetainedInMergedView() throws Exception {
        manager.add(entry("FAV", true, List.of(), 1000L));
        manager.add(entry("NORMAL", false, List.of(), 2000L));
        List<SqlHistoryEntry> view = manager.getEntries();
        assertTrue(view.stream().filter(e -> "FAV".equals(e.getSql())).findFirst().get().isFavorite());
        assertFalse(view.stream().filter(e -> "NORMAL".equals(e.getSql())).findFirst().get().isFavorite());
    }

    // ---------- 标签筛选 + 搜索组合 ----------

    @Test
    public void tagFilterThenSearchTakesIntersection() throws Exception {
        manager.add(entry("SELECT * FROM user", false, List.of("慢查询"), 1000L));
        manager.add(entry("SELECT * FROM order", false, List.of("慢查询"), 2000L));
        manager.add(entry("UPDATE user SET x = 1", false, List.of(), 3000L));
        List<SqlHistoryEntry> tagged = manager.filterByTag("慢查询");
        assertEquals(2, tagged.size());
        List<SqlHistoryEntry> searched = tagged.stream()
                .filter(e -> e.getSql().toLowerCase().contains("user"))
                .collect(Collectors.toList());
        assertEquals(1, searched.size());
        assertEquals("SELECT * FROM user", searched.get(0).getSql());
    }

    @Test
    public void favoriteFilterExcludesNonFavorite() throws Exception {
        manager.add(entry("FAV_1", true, List.of(), 1000L));
        manager.add(entry("NORMAL_1", false, List.of(), 2000L));
        manager.add(entry("FAV_2", true, List.of(), 3000L));
        List<SqlHistoryEntry> favs = manager.filterFavorites();
        assertEquals(2, favs.size());
        assertTrue(favs.stream().allMatch(SqlHistoryEntry::isFavorite));
        assertEquals(List.of("FAV_2", "FAV_1"),
                favs.stream().map(SqlHistoryEntry::getSql).collect(Collectors.toList()));
    }

    @Test
    public void searchWorksOnFavoriteView() throws Exception {
        manager.add(entry("SELECT * FROM user", true, List.of(), 1000L));
        manager.add(entry("SELECT * FROM order", true, List.of(), 2000L));
        List<SqlHistoryEntry> favs = manager.filterFavorites();
        List<SqlHistoryEntry> searched = favs.stream()
                .filter(e -> e.getSql().toLowerCase().contains("user"))
                .collect(Collectors.toList());
        assertEquals(1, searched.size());
    }

    // ---------- 筛选态切换（面板 onFilterChanged 逻辑） ----------

    @Test
    public void switchFromFavoriteFilterToAllRestoresNonFavorite() throws Exception {
        manager.add(entry("FAV", true, List.of(), 1000L));
        manager.add(entry("NORMAL", false, List.of(), 2000L));
        assertEquals(1, manager.filterFavorites().size());
        assertEquals(2, manager.getEntries().size());
    }

    @Test
    public void missingTagFilterReturnsEmpty() throws Exception {
        manager.add(entry("SELECT 1", false, List.of("慢查询"), 1000L));
        List<SqlHistoryEntry> result = manager.filterByTag("不存在的标签");
        assertEquals(0, result.size());
    }
}
