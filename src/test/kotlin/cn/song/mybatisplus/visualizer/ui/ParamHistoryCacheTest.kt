package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P-05：[ParamHistoryCache] 纯逻辑单测（LRU + fingerprint + 持久化往返）。
 *
 * 纯逻辑、无 IDE 依赖（PluginSettings.getInstance() 在测试环境返回默认 State），
 * 轻量 JUnit 4 测试即可。
 */
class ParamHistoryCacheTest {

    // ------------------------------------------------------------------
    // save + recall 往返
    // ------------------------------------------------------------------

    @Test
    fun testSaveAndRecallRoundTrip() {
        val cache = ParamHistoryCache()
        cache.clear()
        val key = cache.computeKey("user", "SELECT * FROM user WHERE id = ?")
        assertNotNull(key)
        cache.save(key!!, mapOf("id" to "123", "name" to "张三"))
        val recalled = cache.recall(key)
        assertNotNull("应命中缓存", recalled)
        assertEquals(mapOf("id" to "123", "name" to "张三"), recalled)
    }

    @Test
    fun testRecallMissReturnsNull() {
        val cache = ParamHistoryCache()
        cache.clear()
        val key = cache.computeKey("user", "SELECT * FROM user")
        assertNull("未保存的 key 应返回 null", cache.recall(key!!))
    }

    // ------------------------------------------------------------------
    // LRU 淘汰
    // ------------------------------------------------------------------

    @Test
    fun testLruEvictionOverMaxSize() {
        // 小上限便于测试淘汰（上限 3）
        val cache = ParamHistoryCache(maxSize = 3)
        cache.clear()
        val sql = "SELECT * FROM tbl WHERE id = ?"
        // 存入 4 条（超过上限 3）
        repeat(4) { i ->
            val key = "tbl:$i"
            cache.save(key, mapOf("id" to "$i"))
        }
        assertEquals("超过上限后应淘汰到 maxSize", 3, cache.size())
        // 最久未访问的第 0 条应被淘汰
        assertNull("最早条目应被淘汰", cache.recall("tbl:0"))
        // 第 1/2/3 条仍在
        assertNotNull(cache.recall("tbl:1"))
        assertNotNull(cache.recall("tbl:2"))
        assertNotNull(cache.recall("tbl:3"))
    }

    @Test
    fun testLruAccessOrderUpdate() {
        val cache = ParamHistoryCache(maxSize = 3)
        cache.clear()
        repeat(3) { i -> cache.save("tbl:$i", mapOf("id" to "$i")) }
        // 访问第 0 条（最近访问 → 排到尾部，不再是最久未访问）
        cache.recall("tbl:0")
        // 插入第 4 条 → 淘汰最久未访问的第 1 条（第 0 条刚被访问过）
        cache.save("tbl:3", mapOf("id" to "3"))
        assertNotNull("最近访问的第 0 条应保留", cache.recall("tbl:0"))
        assertNull("最久未访问的第 1 条应被淘汰", cache.recall("tbl:1"))
    }

    // ------------------------------------------------------------------
    // computeKey 一致性
    // ------------------------------------------------------------------

    @Test
    fun testComputeKeyConsistency() {
        val cache = ParamHistoryCache()
        cache.clear()
        val key1 = cache.computeKey("user", "SELECT * FROM user WHERE id = ?")
        val key2 = cache.computeKey("user", "SELECT * FROM user WHERE id = ?")
        assertEquals("相同 tableName+rawSql 应生成相同 key", key1, key2)
    }

    @Test
    fun testComputeKeyDifferentSql() {
        val cache = ParamHistoryCache()
        cache.clear()
        val key1 = cache.computeKey("user", "SELECT * FROM user WHERE id = ?")
        val key2 = cache.computeKey("user", "SELECT * FROM user WHERE name = ?")
        assertFalse("不同 SQL 应生成不同 key", key1 == key2)
    }

    @Test
    fun testComputeKeyNullTableNameReturnsNull() {
        val cache = ParamHistoryCache()
        cache.clear()
        assertNull("无表名应返回 null（不缓存）", cache.computeKey(null, "SELECT 1"))
        assertNull("空表名应返回 null（不缓存）", cache.computeKey("", "SELECT 1"))
    }

    // ------------------------------------------------------------------
    // fingerprint 稳定性
    // ------------------------------------------------------------------

    @Test
    fun testFingerprintStability() {
        val sql = "SELECT * FROM user WHERE id = ?"
        val fp1 = ParamHistoryCache.fingerprint(sql)
        val fp2 = ParamHistoryCache.fingerprint(sql)
        assertEquals("相同 SQL → 相同指纹", fp1, fp2)
        assertEquals("指纹应为 16 hex 字符", 16, fp1.length)
    }

    @Test
    fun testFingerprintDifferentSql() {
        val fp1 = ParamHistoryCache.fingerprint("SELECT * FROM user")
        val fp2 = ParamHistoryCache.fingerprint("SELECT * FROM order")
        assertFalse("不同 SQL → 不同指纹", fp1 == fp2)
    }

    // ------------------------------------------------------------------
    // clear 清空
    // ------------------------------------------------------------------

    @Test
    fun testClearEmptiesCache() {
        val cache = ParamHistoryCache()
        cache.clear()
        val key = cache.computeKey("user", "SELECT * FROM user")
        cache.save(key!!, mapOf("id" to "1"))
        assertEquals(1, cache.size())
        cache.clear()
        assertEquals("clear 后应为空", 0, cache.size())
        assertNull("clear 后 recall 返回 null", cache.recall(key))
    }

    // ------------------------------------------------------------------
    // 持久化往返
    // ------------------------------------------------------------------

    @Test
    fun testPersistenceRoundTrip() {
        val cache1 = ParamHistoryCache()
        cache1.clear()
        val key = cache1.computeKey("user", "SELECT * FROM user WHERE id = ?")
        cache1.save(key!!, mapOf("id" to "42", "status" to "active"))
        // 模拟 IDE 重启：新建 cache 实例，从持久化恢复
        val cache2 = ParamHistoryCache()
        val recalled = cache2.recall(key)
        assertNotNull("新实例应从持久化恢复", recalled)
        assertEquals(mapOf("id" to "42", "status" to "active"), recalled)
        cache2.clear()
    }
}
