package cn.song.mybatisplus.visualizer.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R15（W3 历史工作流包）：历史条目钉住——裁剪跳过钉住条目、全钉住不淘汰、上限拒钉。
 * 纯逻辑（SqlHistoryManager 无平台依赖，直接构造）。
 */
class SqlHistoryPinTest {



    @Test
    fun testR15_togglePin_往返() {
        val m = SqlHistoryManager()
        val e = m.add(SqlHistoryEntry().apply { setSql("SELECT 1"); setTimestamp(1000L) })
        assertTrue(m.togglePin(e.id))
        assertTrue("钉住态翻转", e.isPinned)
        assertTrue(m.togglePin(e.id))
        assertFalse("再翻回未钉住", e.isPinned)
    }

    /** R15 核心：钉住条目裁剪跳过——maxSize=2，钉住最旧后新增第 3 条，被淘汰的应是中间非钉住条。 */
    @Test
    fun testR15_钉住条目_裁剪跳过() {
        val m = SqlHistoryManager().apply { setMaxSize(2) }
        val e1 = m.add(SqlHistoryEntry().apply { setSql("SELECT 1"); setTimestamp(1000L); setPinned(true) })
        m.add(SqlHistoryEntry().apply { setSql("SELECT 2"); setTimestamp(2000L) })
        // 新增第 3 条触发淘汰：最旧非钉住（SELECT 2）被移除，钉住的 SELECT 1 保留
        m.add(SqlHistoryEntry().apply { setSql("SELECT 3"); setTimestamp(3000L) })
        val remaining = m.entries.map { it.sql }
        assertTrue("钉住条目不被淘汰", "SELECT 1" in remaining)
        assertFalse("非钉住最旧条目被淘汰", "SELECT 2" in remaining)
        assertTrue("最新条目保留", "SELECT 3" in remaining)
    }

    /** R15 边缘：全钉住时零淘汰（淘汰循环找不到非钉住条目即停）。 */
    @Test
    fun testR15_全钉住_不淘汰() {
        val m = SqlHistoryManager().apply { setMaxSize(2) }
        m.add(SqlHistoryEntry().apply { setSql("SELECT 1"); setTimestamp(1000L); setPinned(true) })
        m.add(SqlHistoryEntry().apply { setSql("SELECT 2"); setTimestamp(2000L); setPinned(true) })
        m.add(SqlHistoryEntry().apply { setSql("SELECT 3"); setTimestamp(3000L); setPinned(true) })
        assertEquals("全钉住零淘汰", 3, m.entries.size)
    }
}
