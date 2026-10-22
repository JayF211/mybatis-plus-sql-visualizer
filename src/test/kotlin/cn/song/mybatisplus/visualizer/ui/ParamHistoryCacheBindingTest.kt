package cn.song.mybatisplus.visualizer.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * T-PresetMethodBinding：同一方法里改 SQL 后预设不丢。
 *
 * 语义：键仍是 `表名 + SQL 指纹 + 声明作用域`，精确键优先；改 SQL 后精确键未命中，
 * 按「该方案在该方法下最近一次保存」回落。不同方法（不同 scope）之间不互相回落。
 */
class ParamHistoryCacheBindingTest {

    private lateinit var cache: ParamHistoryCache

    @Before
    fun setUp() {
        ParamHistoryCache().clear()
        cache = ParamHistoryCache()
    }

    @After
    fun tearDown() {
        ParamHistoryCache().clear()
    }

    private fun scoped(sql: String, scope: String = "scope-a"): String =
        cache.computeKey("t_order", sql, scope)!!

    @Test
    fun testRecallRecentFallsBackWithinSameMethodAfterSqlChange() {
        val oldKey = scoped("SELECT * FROM t_order WHERE id = ?")
        val newKey = scoped("SELECT * FROM t_order WHERE id = ? AND status = ?")
        assertEquals(
            ParamHistoryCache.SchemeOpResult.OK,
            cache.saveAsScheme("预设", oldKey, linkedMapOf("id" to "1")),
        )

        assertNull("新 SQL 键本身没有快照", cache.recallFromScheme("预设", newKey))
        assertEquals("同一方法改 SQL 后应找回上次参数", "1", cache.recallRecent(newKey)!!["id"])
        assertNull("精确键命中时 recallRecent 不应越位返回", cache.recallRecent(oldKey))
    }

    @Test
    fun testRecallRecentDoesNotLeakAcrossMethods() {
        val keyA = scoped("SELECT * FROM t_order WHERE id = ?", "scope-a")
        val keyBNewSql = scoped("SELECT * FROM t_order WHERE id = ? AND status = ?", "scope-b")
        cache.saveAsScheme("预设", keyA, linkedMapOf("id" to "1"))
        assertNull("另一个方法改 SQL 后不得回落到 A 的快照", cache.recallRecent(keyBNewSql))
    }

    @Test
    fun testActiveSchemeSurvivesSqlChange() {
        val oldKey = scoped("SELECT * FROM t_order WHERE id = ?")
        val newKey = scoped("SELECT * FROM t_order WHERE id = ? AND status = ?")
        cache.saveAsScheme("预设", oldKey, linkedMapOf("id" to "1"))
        assertEquals("改 SQL 后该方案仍应视为激活（否则回落链在 activeSchemeFor 处断掉）", "预设", cache.activeSchemeFor(newKey))
    }

    @Test
    fun testSaveAsSchemeUpdatesPointerPerScope() {
        val keyA1 = scoped("SELECT 1", "scope-a")
        val keyB1 = scoped("SELECT 1", "scope-b")
        cache.saveAsScheme("同名", keyA1, linkedMapOf("id" to "A"))
        cache.saveAsScheme("同名", keyB1, linkedMapOf("id" to "B"))

        val keyA2 = scoped("SELECT 2", "scope-a")
        val keyB2 = scoped("SELECT 2", "scope-b")
        assertEquals("A 方法应回落自己的值", "A", cache.recallRecent(keyA2)!!["id"])
        assertEquals("B 方法应回落自己的值", "B", cache.recallRecent(keyB2)!!["id"])
    }

    @Test
    fun testRecallRecentSurvivesPersistenceRoundTrip() {
        val oldKey = scoped("SELECT * FROM t_order WHERE id = ?")
        val newKey = scoped("SELECT * FROM t_order WHERE id = ? AND status = ?")
        cache.saveAsScheme("预设", oldKey, linkedMapOf("id" to "1"))

        val reloaded = ParamHistoryCache()
        assertNotNull("指针应随持久化恢复", reloaded.recallRecent(newKey))
        assertEquals("恢复后仍能拿到上次参数", "1", reloaded.recallRecent(newKey)!!["id"])
    }

    @Test
    fun testUnscopedKeyHasNoRecentFallback() {
        val legacy = cache.computeKey("t_order", "SELECT * FROM t_order WHERE id = ?")!!
        val legacyOther = cache.computeKey("t_order", "SELECT * FROM t_order WHERE id = ? AND status = ?")!!
        cache.save(legacy, linkedMapOf("id" to "1"))
        assertNull("无方法上下文的键没有「上次」概念", cache.recallRecent(legacyOther))
    }

    @Test
    fun testClearDropsPointers() {
        val oldKey = scoped("SELECT 1")
        val newKey = scoped("SELECT 2")
        cache.saveAsScheme("预设", oldKey, linkedMapOf("id" to "1"))
        cache.clear()
        assertNull("清空后指针一并失效", cache.recallRecent(newKey))
    }
}
