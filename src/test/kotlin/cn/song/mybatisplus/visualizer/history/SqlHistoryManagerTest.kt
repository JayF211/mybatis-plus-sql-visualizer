package cn.song.mybatisplus.visualizer.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SqlHistoryManager 单元测试（F-7）：添加 / 上限淘汰 / 收藏豁免 / 标签 / 搜索 / 持久化往返。
 *
 * 直接实例化 SqlHistoryManager（不依赖平台服务容器），验证核心逻辑与持久化契约。
 */
class SqlHistoryManagerTest {

    private fun entry(
        sql: String = "SELECT * FROM user",
        favorite: Boolean = false,
        tags: List<String> = emptyList(),
    ) = SqlHistoryEntry(
        sql = sql,
        compactSql = sql,
        timestamp = System.currentTimeMillis(),
        sourceFile = "UserMapper.java",
        sourceLine = 10,
        tableName = "user",
        favorite = favorite,
        tags = tags.toMutableList(),
    )

    // ---------- 添加 / 查询 ----------

    @Test
    fun `add increases entry count`() {
        val manager = SqlHistoryManager()
        assertEquals(0, manager.entries.size)
        manager.add(entry(sql = "SELECT 1"))
        assertEquals(1, manager.entries.size)
        assertEquals("SELECT 1", manager.entries.first().sql)
    }

    @Test
    fun `add assigns unique id when blank`() {
        val manager = SqlHistoryManager()
        val e = entry()
        assertTrue(e.id.isBlank())
        manager.add(e)
        assertTrue(e.id.isNotBlank())
    }

    @Test
    fun `entries returned newest first`() {
        val manager = SqlHistoryManager()
        manager.add(entry(sql = "FIRST").apply { timestamp = 1000L })
        manager.add(entry(sql = "SECOND").apply { timestamp = 2000L })
        manager.add(entry(sql = "THIRD").apply { timestamp = 3000L })
        // 最新在前
        assertEquals("THIRD", manager.entries[0].sql)
        assertEquals("SECOND", manager.entries[1].sql)
        assertEquals("FIRST", manager.entries[2].sql)
    }

    // ---------- 上限淘汰 ----------

    @Test
    fun `evicts oldest non-favorite when over limit`() {
        val manager = SqlHistoryManager()
        // 默认上限 200，添加 201 条
        repeat(200) { i -> manager.add(entry(sql = "SQL_$i").apply { timestamp = i.toLong() * 1000L }) }
        assertEquals(200, manager.entries.size)
        manager.add(entry(sql = "NEW").apply { timestamp = 99999L })
        assertEquals("添加第 201 条后应仍保持 200 条", 200, manager.entries.size)
        // 最旧的非收藏条目 (SQL_0) 被淘汰
        val sqls = manager.entries.map { it.sql }
        assertFalse("最旧条目应被淘汰", sqls.contains("SQL_0"))
        assertTrue("最新条目应存在", sqls.contains("NEW"))
        assertTrue("SQL_1 应保留", sqls.contains("SQL_1"))
    }

    @Test
    fun `favorite entries are never evicted`() {
        val manager = SqlHistoryManager()
        // 添加 1 条收藏 + 200 条非收藏 = 201 条
        manager.add(entry(sql = "FAV").apply { favorite = true; timestamp = 1L })
        repeat(200) { i -> manager.add(entry(sql = "SQL_$i").apply { timestamp = (i + 2).toLong() * 1000L }) }
        assertEquals(201, manager.entries.size)
        // 再添加一条触发淘汰（非收藏上限 200）
        manager.add(entry(sql = "NEW").apply { timestamp = 99999L })
        // 淘汰 1 条最旧非收藏 → 1 收藏 + 200 非收藏 = 201
        assertEquals(201, manager.entries.size)
        // 收藏条目不被淘汰
        assertTrue("收藏条目应保留", manager.entries.any { it.sql == "FAV" && it.favorite })
    }

    @Test
    fun `respects custom max size`() {
        val manager = SqlHistoryManager()
        manager.maxSize = 5
        repeat(7) { i -> manager.add(entry(sql = "SQL_$i")) }
        assertEquals("自定义上限 5 条", 5, manager.entries.size)
        val sqls = manager.entries.map { it.sql }
        assertTrue(sqls.contains("SQL_6"))
        assertTrue(sqls.contains("SQL_5"))
        assertFalse(sqls.contains("SQL_0"))
    }

    // ---------- 收藏 ----------

    @Test
    fun `toggleFavorite flips state`() {
        val manager = SqlHistoryManager()
        manager.add(entry(sql = "SELECT 1"))
        val id = manager.entries.first().id
        assertFalse(manager.entries.first().favorite)
        manager.toggleFavorite(id)
        assertTrue(manager.entries.first().favorite)
        manager.toggleFavorite(id)
        assertFalse(manager.entries.first().favorite)
    }

    @Test
    fun `toggleFavorite on unknown id is no-op`() {
        val manager = SqlHistoryManager()
        manager.add(entry())
        // 不应抛异常
        manager.toggleFavorite("nonexistent-id")
        assertEquals(1, manager.entries.size)
    }

    // ---------- 标签 ----------

    @Test
    fun `addTag and removeTag`() {
        val manager = SqlHistoryManager()
        manager.add(entry())
        val id = manager.entries.first().id
        manager.addTag(id, "慢查询")
        manager.addTag(id, "联表")
        assertEquals(setOf("慢查询", "联表"), manager.entries.first().tags.toSet())
        manager.removeTag(id, "慢查询")
        assertEquals(setOf("联表"), manager.entries.first().tags.toSet())
    }

    @Test
    fun `addTag ignores duplicate`() {
        val manager = SqlHistoryManager()
        manager.add(entry())
        val id = manager.entries.first().id
        manager.addTag(id, "慢查询")
        manager.addTag(id, "慢查询")
        assertEquals(1, manager.entries.first().tags.size)
    }

    @Test
    fun `allTags returns union of all tags`() {
        val manager = SqlHistoryManager()
        manager.add(entry().apply { tags.add("慢查询") })
        manager.add(entry().apply { tags.add("联表") })
        manager.add(entry().apply { tags.add("慢查询"); tags.add("待优化") })
        assertEquals(setOf("慢查询", "联表", "待优化"), manager.allTags())
    }

    // ---------- 搜索 / 筛选 ----------

    @Test
    fun `search filters by substring ignore case`() {
        val manager = SqlHistoryManager()
        manager.add(entry(sql = "SELECT * FROM user WHERE id = 1"))
        manager.add(entry(sql = "SELECT * FROM order WHERE user_id = 1"))
        manager.add(entry(sql = "UPDATE product SET name = 'x'"))
        val result = manager.search("user")
        // user / user_id 含子串 "user"，product 不含 → 命中 2 条
        assertEquals(2, result.size)
        assertTrue(result.all { it.sql.contains("user", ignoreCase = true) })
    }

    @Test
    fun `search empty query returns all`() {
        val manager = SqlHistoryManager()
        manager.add(entry(sql = "A"))
        manager.add(entry(sql = "B"))
        assertEquals(2, manager.search("").size)
    }

    @Test
    fun `filterByTag returns matching entries`() {
        val manager = SqlHistoryManager()
        manager.add(entry(sql = "SELECT 1").apply { tags.add("慢查询") })
        manager.add(entry(sql = "SELECT 2"))
        manager.add(entry(sql = "SELECT 3").apply { tags.add("慢查询") })
        val result = manager.filterByTag("慢查询")
        assertEquals(2, result.size)
    }

    @Test
    fun `filterFavorites returns only favorites`() {
        val manager = SqlHistoryManager()
        manager.add(entry(sql = "A").apply { favorite = true })
        manager.add(entry(sql = "B"))
        manager.add(entry(sql = "C").apply { favorite = true })
        val result = manager.filterFavorites()
        assertEquals(2, result.size)
        assertTrue(result.all { it.favorite })
    }

    // ---------- 删除 / 清空 ----------

    @Test
    fun `remove deletes by id`() {
        val manager = SqlHistoryManager()
        manager.add(entry(sql = "A"))
        val id = manager.entries.first().id
        manager.remove(id)
        assertEquals(0, manager.entries.size)
    }

    @Test
    fun `clear removes all`() {
        val manager = SqlHistoryManager()
        manager.add(entry(sql = "A"))
        manager.add(entry(sql = "B"))
        manager.clear()
        assertEquals(0, manager.entries.size)
    }

    // ---------- 持久化往返 ----------

    @Test
    fun `state round trip preserves entries and favorites and tags`() {
        val manager = SqlHistoryManager()
        manager.add(entry(sql = "SELECT 1").apply {
            favorite = true
            tags.add("慢查询")
            timestamp = 12345L
        })
        manager.add(entry(sql = "UPDATE t SET x = 1"))
        val state = manager.getState()
        // 收藏条目进 favoriteEntries 分区，非收藏进 entries 分区
        assertEquals(1, state.entries.size)
        assertEquals(1, state.favoriteEntries.size)
        // 模拟重启：新实例 loadState
        val fresh = SqlHistoryManager()
        fresh.loadState(state)
        assertEquals(2, fresh.entries.size)
        val fav = fresh.entries.first { it.sql == "SELECT 1" }
        assertTrue("收藏状态应保留", fav.favorite)
        assertEquals(listOf("慢查询"), fav.tags)
        assertEquals(12345L, fav.timestamp)
    }

    @Test
    fun `loadState with corrupt data falls back gracefully`() {
        val manager = SqlHistoryManager()
        // 空状态（模拟损坏/空文件）不抛异常
        manager.loadState(SqlHistoryManager.State())
        assertEquals(0, manager.entries.size)
    }

    @Test
    fun `loadState replaces existing entries`() {
        val manager = SqlHistoryManager()
        manager.add(entry(sql = "OLD"))
        assertEquals(1, manager.entries.size)
        val newState = SqlHistoryManager.State(
            entries = mutableListOf(entry(sql = "NEW")),
        )
        manager.loadState(newState)
        assertEquals(1, manager.entries.size)
        assertEquals("NEW", manager.entries.first().sql)
    }
}
