package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamScenarioSnapshotTest {
    @Test
    fun `includes default and only current statement schemes without changing active`() {
        val cache = ParamHistoryCache()
        cache.clear()
        cache.saveForScheme(ParamHistoryCache.DEFAULT_SCHEME, "one", mapOf("id" to "1"))
        cache.saveAsScheme("A", "one", mapOf("id" to "2"))
        cache.saveAsScheme("B", "two", mapOf("id" to "3"))
        val before = cache.activeSchemeFor("one")
        val snapshots = cache.snapshotSchemesFor("one")
        assertEquals(listOf("", "A"), snapshots.schemes.map { it.name })
        assertEquals(listOf("1", "2"), snapshots.schemes.map { it.values["id"] })
        assertEquals(before, cache.activeSchemeFor("one"))
        cache.clear()
    }

    @Test
    fun `snapshot does not make oldest key recent`() {
        val cache = ParamHistoryCache(maxSize = 2)
        cache.clear()
        cache.saveForScheme("", "old", mapOf("id" to "1"))
        cache.saveForScheme("", "new", mapOf("id" to "2"))
        assertEquals("1", cache.snapshotSchemesFor("old").schemes.single().values["id"])
        cache.saveForScheme("", "latest", mapOf("id" to "3"))
        assertNull(cache.recall("old"))
        assertEquals("2", cache.recall("new")?.get("id"))
        cache.clear()
    }

    @Test
    fun `snapshot has value semantics and clearing invalidates stale cache`() {
        val cache = ParamHistoryCache()
        cache.clear()
        cache.saveForScheme("", "one", mapOf("id" to "1"))
        val snapshot = cache.snapshotSchemesFor("one")
        cache.saveForScheme("", "one", mapOf("id" to "2"))
        assertEquals("1", snapshot.schemes.single().values["id"])
        cache.clear()
        assertTrue(cache.snapshotSchemesFor("one").schemes.isEmpty())
        assertTrue(cache.snapshotSchemesFor("one").storageGeneration > snapshot.storageGeneration)
    }

    @Test
    fun `batch limit is explicit and does not write cache`() {
        val cache = ParamHistoryCache()
        cache.clear()
        cache.saveForScheme("", "one", mapOf("id" to "0"))
        cache.saveAsScheme("A", "one", mapOf("id" to "1"))
        cache.saveAsScheme("B", "one", mapOf("id" to "2"))
        assertEquals(2, cache.snapshotSchemesFor("one", 2).schemes.size)
        assertEquals(3, cache.snapshotSchemesFor("one", 20).schemes.size)
        assertEquals("B", cache.activeSchemeFor("one"))
        cache.clear()
    }
}
