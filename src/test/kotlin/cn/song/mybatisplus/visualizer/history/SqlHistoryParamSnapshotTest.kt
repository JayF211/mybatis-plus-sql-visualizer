package cn.song.mybatisplus.visualizer.history

import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R51（O-105 余半·主体）数据模型层测试：SqlHistoryEntry.paramSnapshot 的管理器回写/回落 +
 * 真 XmlSerializer 持久化往返 + 旧历史文件迁移兼容（V75①②的**实类版**——探针
 * V75ParamSnapshotSerializerProbeTest 钉的是形态前提，本类钉真类契约）。
 */
class SqlHistoryParamSnapshotTest {

    private fun entry(
        sql: String = "SELECT * FROM user",
        tableName: String = "user",
        timestamp: Long = System.currentTimeMillis(),
    ) = SqlHistoryEntry(
        sql = sql,
        compactSql = sql,
        timestamp = timestamp,
        tableName = tableName,
    )

    // ---------- 管理器：回写 / 回落 ----------

    @Test
    fun `updateNewestParamSnapshot updates only the newest matching entry`() {
        val m = SqlHistoryManager()
        m.add(entry(sql = "SELECT 1", timestamp = 100L))
        m.add(entry(sql = "SELECT 1", timestamp = 200L)) // 同 SQL 两条：新者权威
        m.add(entry(sql = "SELECT 2", timestamp = 300L))

        val updated = m.updateNewestParamSnapshot({ it.sql == "SELECT 1" }, mapOf("id" to "7"))

        assertTrue(updated)
        val matches = m.entries.filter { it.sql == "SELECT 1" }
        assertEquals(2, matches.size)
        val newest = matches.maxByOrNull { it.timestamp }!!
        val oldest = matches.minByOrNull { it.timestamp }!!
        assertEquals(mapOf("id" to "7"), newest.paramSnapshot.toMap())
        assertEquals("旧条目保留自身时点快照（不连坐覆写）", emptyMap<String, String>(), oldest.paramSnapshot.toMap())
    }

    @Test
    fun `updateNewestParamSnapshot returns false on miss and rejects empty snapshot`() {
        val m = SqlHistoryManager()
        m.add(entry(sql = "SELECT 1"))
        assertFalse("无匹配条目返回 false（不抛）", m.updateNewestParamSnapshot({ it.sql == "NOPE" }, mapOf("a" to "1")))
        assertFalse("空快照直接拒绝（空值无信息量，不置脏）", m.updateNewestParamSnapshot({ it.sql == "SELECT 1" }, emptyMap()))
        assertEquals(emptyMap<String, String>(), m.entries.first().paramSnapshot.toMap())
    }

    @Test
    fun `newestParamSnapshot skips entries without snapshot and merges favorites partition`() {
        val m = SqlHistoryManager()
        m.add(entry(sql = "SELECT 1", timestamp = 100L)) // 唯一匹配但快照为空 → 跳过
        assertNull(m.newestParamSnapshot({ it.sql == "SELECT 1" }))

        m.updateNewestParamSnapshot({ it.sql == "SELECT 1" }, mapOf("id" to "9"))
        assertEquals(mapOf("id" to "9"), m.newestParamSnapshot({ it.sql == "SELECT 1" }))

        // 收藏分区同样参与（entries 合并视图）
        m.add(entry(sql = "SELECT 2", timestamp = 50L).apply { favorite = true })
        assertTrue(m.updateNewestParamSnapshot({ it.sql == "SELECT 2" }, mapOf("x" to "1")))
        assertNotNull(m.newestParamSnapshot({ it.sql == "SELECT 2" }))
    }

    @Test
    fun `snapshot overwrite replaces previous content`() {
        val m = SqlHistoryManager()
        m.add(entry(sql = "SELECT 1"))
        m.updateNewestParamSnapshot({ it.sql == "SELECT 1" }, mapOf("a" to "1", "b" to "2"))
        m.updateNewestParamSnapshot({ it.sql == "SELECT 1" }, mapOf("c" to "3"))
        assertEquals("二次回写应整包替换（不残留旧键）", mapOf("c" to "3"), m.entries.first().paramSnapshot.toMap())
    }

    // ---------- 持久化往返（真 XmlSerializer） ----------

    @Test
    fun `paramSnapshot round trips through real XmlSerializer`() {
        val original = entry(sql = "SELECT * FROM t WHERE name = ?").apply {
            paramSnapshot["name"] = "zhang's \"quoted\""
            paramSnapshot["__chk__:status"] = "1"
            paramSnapshot["__fev__:ids"] = "1,2,3"
            paramSnapshot["multi\nline"] = "v2"
        }
        val element = XmlSerializer.serialize(original)
        val restored = XmlSerializer.deserialize(element, SqlHistoryEntry::class.java)
        assertEquals(4, restored.paramSnapshot.size)
        assertEquals("zhang's \"quoted\"", restored.paramSnapshot["name"])
        assertEquals("1", restored.paramSnapshot["__chk__:status"])
        assertEquals("1,2,3", restored.paramSnapshot["__fev__:ids"])
        assertEquals("v2", restored.paramSnapshot["multi\nline"])
        assertEquals("普通字段不回归", "user", restored.tableName)
    }

    /** V75② 实类版：旧历史文件（无 paramSnapshot 节点）载入新类——不炸、缺省空 Map。 */
    @Test
    fun `old xml without snapshot element loads with empty default`() {
        val original = entry(sql = "SELECT 1").apply { paramSnapshot["a"] = "1" }
        val element = XmlSerializer.serialize(original)
        removeSnapshotElements(element)

        val restored = XmlSerializer.deserialize(element, SqlHistoryEntry::class.java)
        assertEquals("旧文件缺字段应缺省为空 Map", emptyMap<String, String>(), restored.paramSnapshot.toMap())
        assertEquals("其余字段不受迁移影响", "SELECT 1", restored.sql)
        assertEquals("user", restored.tableName)
    }

    /** State 级（PersistentStateComponent 持久化面）往返含快照条目。 */
    @Test
    fun `state round trip preserves snapshots across partitions`() {
        val m = SqlHistoryManager()
        m.add(entry(sql = "SELECT 1").apply { paramSnapshot["k"] = "v1" })
        m.add(entry(sql = "SELECT 2", timestamp = 1L).apply {
            favorite = true
            paramSnapshot["k2"] = "v2"
        })
        val fresh = SqlHistoryManager()
        fresh.loadState(m.getState())
        assertEquals("v1", fresh.entries.first { it.sql == "SELECT 1" }.paramSnapshot["k"])
        assertEquals("v2", fresh.entries.first { it.sql == "SELECT 2" }.paramSnapshot["k2"])
    }

    private fun removeSnapshotElements(parent: Element) {
        val children = parent.children.toList()
        for (child in children) {
            if (child.name == "paramSnapshot" ||
                (child.name == "option" && child.getAttributeValue("name") == "paramSnapshot")
            ) {
                parent.removeContent(child)
            } else {
                removeSnapshotElements(child)
            }
        }
    }
}
