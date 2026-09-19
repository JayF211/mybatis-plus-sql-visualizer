package cn.song.mybatisplus.visualizer.sql.rollback

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * 回滚快照登记簿测试（T-RollbackSql P2-F5/F6）：project 级 light service 的登记/查找/
 * 留痕终态/LRU 上限。BasePlatformTestCase 承载 project service 生命周期。
 */
class RollbackSnapshotStoreTest : BasePlatformTestCase() {

    private fun store(): RollbackSnapshotStore = project.getService(RollbackSnapshotStore::class.java)

    override fun tearDown() {
        store().clearForTest()
        super.tearDown()
    }

    fun testRegisterAndFindBySql() {
        val store = store()
        val sql = "UPDATE user SET name = 'x' WHERE id = 1"
        val entry = store.register(sql, "user", "SELECT * FROM user WHERE id = 1;", listOf("name"))

        assertEquals(1, store.size())
        // 指纹匹配：空白差异不命中失效（normalizeSqlWhitespace 折叠）
        val found = store.findBySql("UPDATE user SET name = 'x' WHERE id = 1\n")
        assertNotNull(found)
        assertEquals(entry.executionId, found!!.executionId)
        assertEquals("user", found.targetTable)
        assertEquals(listOf("name"), found.setColumns)
        assertEquals(RollbackSnapshotStore.Status.REGISTERED, found.status)
    }

    fun testFindBySqlMiss() {
        assertNull(store().findBySql("DELETE FROM t WHERE id = 999"))
    }

    fun testSameSqlReregisterOverwrites() {
        val store = store()
        val sql = "DELETE FROM t WHERE id = 1"
        val first = store.register(sql, "t", "SELECT * FROM t WHERE id = 1;")
        val second = store.register("$sql ", "t", "SELECT * FROM t WHERE id = 1;")

        assertEquals(1, store.size())
        assertEquals(first.executionId + 1, second.executionId)
        assertEquals(second.executionId, store.findBySql(sql)!!.executionId)
    }

    fun testMarkRollbackExecutedSetsFinalStatus() {
        val store = store()
        val sql = "UPDATE t SET a = 1 WHERE id = 1"
        val entry = store.register(sql, "t", "SELECT * FROM t WHERE id = 1;")

        assertTrue(store.markRollbackExecuted(entry))

        val found = store.findBySql(sql)!!
        assertEquals(RollbackSnapshotStore.Status.ROLLBACK_EXECUTED, found.status)
        // 重复置终态幂等
        assertTrue(store.markRollbackExecuted(entry))
    }

    fun testRollbackEntryCarriesParentExecutionId() {
        val store = store()
        val parent = store.register("UPDATE t SET a = 1 WHERE id = 1", "t", "SELECT * FROM t WHERE id = 1;")
        val rollback = store.register(
            "UPDATE t SET a = 2 WHERE id = 1 AND a = 1", "t", null,
            parentExecutionId = parent.executionId,
        )

        assertEquals(parent.executionId, rollback.parentExecutionId)
        assertEquals(RollbackSnapshotStore.NO_EXECUTION_ID, parent.parentExecutionId)
    }

    fun testLruCap() {
        val store = store()
        for (i in 0 until RollbackSnapshotStore.MAX_ENTRIES + 20) {
            store.register("DELETE FROM t_$i WHERE id = 1", "t_$i", null)
        }
        assertEquals(RollbackSnapshotStore.MAX_ENTRIES, store.size())
        // 最老条目被淘汰（t_0 不再命中），最新条目存活
        assertNull(store.findBySql("DELETE FROM t_0 WHERE id = 1"))
        assertNotNull(store.findBySql("DELETE FROM t_${RollbackSnapshotStore.MAX_ENTRIES + 19} WHERE id = 1"))
    }

    fun testMarkRollbackExecutedOnEvictedEntry() {
        val store = store()
        val entry = store.register("DELETE FROM evict_me WHERE id = 1", "t", null)
        store.clearForTest()
        assertFalse(store.markRollbackExecuted(entry))
    }

    // ---------- T-PS5-10：sqlIndex 随 LRU 有界 ----------

    fun testSqlIndex随Lru有界() {
        val store = store()
        // 长会话模拟：远超容量的 distinct DML——此前 sqlIndex 只增不减（无上界）
        for (i in 0 until RollbackSnapshotStore.MAX_ENTRIES * 3) {
            store.register("DELETE FROM t_$i WHERE id = 1", "t_$i", null)
        }
        assertEquals("条目 LRU 容量", RollbackSnapshotStore.MAX_ENTRIES, store.size())
        assertEquals(
            "指纹索引随 LRU 淘汰同步收缩（有界）",
            RollbackSnapshotStore.MAX_ENTRIES,
            store.sqlIndexSizeForTest(),
        )
        // 淘汰后取值 null 行为不变
        assertNull(store.findBySql("DELETE FROM t_0 WHERE id = 1"))
        assertNotNull(store.findBySql("DELETE FROM t_${RollbackSnapshotStore.MAX_ENTRIES * 3 - 1} WHERE id = 1"))
    }

    fun test重复执行同语句索引不膨胀() {
        val store = store()
        repeat(5) { store.register("UPDATE t SET a = $it WHERE id = 1", "t", null) }
        repeat(5) { store.register("UPDATE t SET a = 9 WHERE id = 1", "t", null) }
        assertEquals("同指纹覆盖写：索引条目 = distinct 指纹数", 6, store.sqlIndexSizeForTest())
        assertEquals("条目数 = distinct 指纹数（旧条目按指纹移除）", 6, store.size())
    }
}
