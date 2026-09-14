package cn.song.mybatisplus.visualizer.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SqlHistorySearchBar 行为单测（T-TestGuard-07 零覆盖补测：F-7 Phase C 搜索栏此前零覆盖）。
 *
 * 纯 Swing + bundle，headless 可跑：搜索词即时回调（trim）、筛选模式语义
 * （null=全部 / __favorites__=收藏 / 标签名）、refreshTags 保留现选中。
 */
class SqlHistorySearchBarTest {

    /** SAM 回调构造（k2j 互操作适配——抽到非 test 前缀私有方法）。 */
    private fun newBar(): Pair<SqlHistorySearchBar, MutableList<String>> {
        val queries = mutableListOf<String>()
        val bar = SqlHistorySearchBar(
            SqlHistorySearchBar.SearchListener { queries.add(it) },
            SqlHistorySearchBar.FilterListener { },
        )
        return bar to queries
    }

    @Test
    fun `输入搜索词即时回调并trim`() {
        val (bar, queries) = newBar()
        bar.searchTextForTest("  select * from t  ")
        assertEquals("应触发即时回调并 trim 首尾空白", listOf("select * from t"), queries)
        assertEquals("searchQuery 应与回调一致", "select * from t", bar.searchQuery)
    }

    @Test
    fun `清空输入回调空词`() {
        val (bar, queries) = newBar()
        bar.searchTextForTest("abc")
        bar.searchTextForTest("")
        assertEquals(listOf("abc", ""), queries)
    }

    @Test
    fun `筛选模式语义_全部为null_收藏为标记值`() {
        val (bar, _) = newBar()
        assertNull("默认（全部）应为 null", bar.activeFilter)
        bar.selectFilterIndexForTest(1)
        assertEquals("收藏筛选应为内部标记值", SqlHistorySearchBar.FAVORITES_FILTER, bar.activeFilter)
    }

    @Test
    fun `选中标签后activeFilter返回标签名`() {
        val (bar, _) = newBar()
        bar.refreshTags(setOf("订单", "用户"))
        bar.selectTagForTest("订单")
        assertEquals("订单", bar.activeFilter)
    }

    @Test
    fun `refreshTags保留仍存在的选中_清空后回退全部`() {
        val (bar, _) = newBar()
        bar.refreshTags(setOf("b", "a", "c"))
        bar.selectTagForTest("b")
        // 再刷新且 b 仍在 → 保留选中
        bar.refreshTags(setOf("b", "d"))
        assertEquals("仍存在的标签应保留选中", "b", bar.activeFilter)
        // 全部清空 → 回退「全部」= null
        bar.refreshTags(emptySet())
        assertNull("标签清空后应回退全部（null）", bar.activeFilter)
    }

    @Test
    fun `refreshTags选项按字母序排列`() {
        val (bar, _) = newBar()
        bar.refreshTags(setOf("c", "a", "b"))
        // 「全部」占据 index 0，标签从 1 开始按序
        assertEquals("a", bar.tagItemAtForTest(1))
        assertEquals("b", bar.tagItemAtForTest(2))
        assertEquals("c", bar.tagItemAtForTest(3))
    }
}
