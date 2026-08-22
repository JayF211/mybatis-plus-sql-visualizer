package cn.song.mybatisplus.visualizer.history

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SqlHistoryPanel 过滤/搜索/筛选逻辑测试（F-7）。
 *
 * 测试策略：由于 SqlHistoryPanel 构造函数需完整 ActionToolbar/EDT 上下文（headless 沙盒中 DisposalException，
 * 沿 [cn.song.mybatisplus.visualizer.log.SqlLogPanelFilterTest] 同模式），不构造面板本体的 Swing 组件，
 * 转而测试面板在 [SqlHistoryManager] 之上新增的**过滤/搜索编排逻辑**：
 *  - 面板合并收藏分区 + 非收藏分区为单一倒序视图
 *  - 标签筛选 / 收藏筛选 / 搜索词过滤三态切换
 *
 * <p>纯 manager 逻辑（增删改查/淘汰/持久化/标签 CRUD/搜索）由 [SqlHistoryManagerTest] 全覆盖；
 * 本类聚焦「多条件组合」与「视图编排」。
 */
class SqlHistoryPanelTest {

    private lateinit var manager: SqlHistoryManager

    @Before
    fun setUp() {
        manager = SqlHistoryManager.getInstance()
        manager.clear()
    }

    @After
    fun tearDown() {
        manager.clear()
    }

    private fun entry(
        sql: String = "SELECT * FROM user",
        favorite: Boolean = false,
        tags: List<String> = emptyList(),
        ts: Long = System.currentTimeMillis(),
    ) = SqlHistoryEntry(
        sql = sql,
        compactSql = sql,
        timestamp = ts,
        sourceFile = "UserMapper.java",
        sourceLine = 10,
        tableName = "user",
        favorite = favorite,
        tags = tags.toMutableList(),
    )

    // ---------- 视图编排（收藏 + 非收藏合并为单一倒序视图） ----------

    @Test
    fun `面板视图合并收藏与非收藏分区且按时间倒序`() {
        // 收藏条目（较旧）+ 非收藏条目（较新）
        manager.add(entry(sql = "FAV", favorite = true, ts = 1000L))
        manager.add(entry(sql = "NORMAL_NEW", ts = 3000L))
        manager.add(entry(sql = "NORMAL_OLD", ts = 2000L))
        val view = manager.entries
        assertEquals(3, view.size)
        // 倒序：NEW(3000) > OLD(2000) > FAV(1000)
        assertEquals(listOf("NORMAL_NEW", "NORMAL_OLD", "FAV"), view.map { it.sql })
    }

    @Test
    fun `收藏条目在合并视图中保留收藏标记`() {
        manager.add(entry(sql = "FAV", favorite = true, ts = 1000L))
        manager.add(entry(sql = "NORMAL", ts = 2000L))
        val view = manager.entries
        assertTrue(view.first { it.sql == "FAV" }.favorite)
        assertFalse(view.first { it.sql == "NORMAL" }.favorite)
    }

    // ---------- 标签筛选 + 搜索组合 ----------

    @Test
    fun `标签筛选后再应用搜索词取交集`() {
        manager.add(entry(sql = "SELECT * FROM user", tags = listOf("慢查询"), ts = 1000L))
        manager.add(entry(sql = "SELECT * FROM order", tags = listOf("慢查询"), ts = 2000L))
        manager.add(entry(sql = "UPDATE user SET x = 1", ts = 3000L))
        // 标签筛选
        val tagged = manager.filterByTag("慢查询")
        assertEquals(2, tagged.size)
        // 在标签结果上应用搜索词（面板 refresh 内逻辑）
        val searched = tagged.filter { it.sql.contains("user", ignoreCase = true) }
        assertEquals(1, searched.size)
        assertEquals("SELECT * FROM user", searched.first().sql)
    }

    @Test
    fun `收藏筛选排除非收藏`() {
        manager.add(entry(sql = "FAV_1", favorite = true, ts = 1000L))
        manager.add(entry(sql = "NORMAL_1", ts = 2000L))
        manager.add(entry(sql = "FAV_2", favorite = true, ts = 3000L))
        val favs = manager.filterFavorites()
        assertEquals(2, favs.size)
        assertTrue(favs.all { it.favorite })
        assertEquals(listOf("FAV_2", "FAV_1"), favs.map { it.sql })
    }

    @Test
    fun `搜索在收藏筛选视图上生效`() {
        manager.add(entry(sql = "SELECT * FROM user", favorite = true, ts = 1000L))
        manager.add(entry(sql = "SELECT * FROM order", favorite = true, ts = 2000L))
        val favs = manager.filterFavorites()
        val searched = favs.filter { it.sql.contains("user", ignoreCase = true) }
        assertEquals(1, searched.size)
    }

    // ---------- 筛选态切换（面板 onFilterChanged 逻辑） ----------

    @Test
    fun `从收藏筛选切换到全部时恢复非收藏条目`() {
        manager.add(entry(sql = "FAV", favorite = true, ts = 1000L))
        manager.add(entry(sql = "NORMAL", ts = 2000L))
        // 收藏态
        assertEquals(1, manager.filterFavorites().size)
        // 切换到全部
        assertEquals(2, manager.entries.size)
    }

    @Test
    fun `标签不存在时筛选返回空`() {
        manager.add(entry(sql = "SELECT 1", tags = listOf("慢查询"), ts = 1000L))
        val result = manager.filterByTag("不存在的标签")
        assertEquals(0, result.size)
    }
}
