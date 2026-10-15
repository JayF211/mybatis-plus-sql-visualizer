package cn.song.mybatisplus.visualizer.history

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import org.junit.Assert.*
import org.junit.Test

class ExplainPlanHistorySessionTest {
    private val sql = "select * from t"
    private val key = ExplainPlanKey.create("project-a", "eb03ba8b-a414-4453-b6e0-5bf55b34fed2", "MySQL", ExplainOutputFormat.TABLE, sql)
    private fun raw(n: Int) = RawResult(listOf("id", "table", "rows"), listOf(listOf("1", "t", "$n")))
    private fun manager() = SqlHistoryManager().apply { add(SqlHistoryEntry(sql = sql, projectIdentity = "project-a")) }

    @Test fun `fresh panel session gets persisted baseline before saving new result`() {
        val m = manager()
        val first = ExplainPlanHistorySession()
        val target = Any()
        assertTrue(first.complete(m, first.begin(m, sql, key, target), raw(10)).baselines.isEmpty())
        val reopened = ExplainPlanHistorySession()
        val result = reopened.complete(m, reopened.begin(m, sql, key, target), raw(20))
        assertEquals(listOf(raw(10)), result.baselines.map { it.raw })
        assertEquals(2, m.matchingExplainPlans(key).size)
    }

    @Test fun `unknown UUID preserves same target session behavior but rejects target switch`() {
        val m = manager()
        val session = ExplainPlanHistorySession()
        val unknown = key.copy(dataSourceUuid = "")
        val target = Any()
        session.complete(m, session.begin(m, sql, unknown, target), raw(10))
        val same = session.complete(m, session.begin(m, sql, unknown, target), raw(20))
        assertEquals(listOf(raw(10)), same.baselines.map { it.raw })
        assertEquals(ExplainPlanSaveResult.SESSION_ONLY, same.saved)
        assertTrue(session.complete(m, session.begin(m, sql, unknown, Any()), raw(30)).baselines.isEmpty())
        assertTrue(m.matchingExplainPlans(key).isEmpty())
    }

    @Test fun `known UUID session comparisons never cross target dialect format or SQL`() {
        listOf(key.copy(dataSourceUuid = "eb03ba8b-a414-4453-b6e0-5bf55b34fed3"), key.copy(dialect = "MariaDB"),
            key.copy(format = "JSON"), key.copy(exactFingerprint = "another")).forEach { changed ->
            val m = manager()
            val session = ExplainPlanHistorySession()
            val target = Any()
            session.complete(m, session.begin(m, sql, key, target), raw(10))
            assertTrue(session.complete(m, session.begin(m, sql, changed, target), raw(20)).baselines.isEmpty())
        }
    }

    @Test fun `clear also invalidates session baseline and late result`() {
        val m = manager()
        val session = ExplainPlanHistorySession()
        val target = Any()
        session.complete(m, session.begin(m, sql, key, target), raw(10))
        val late = session.begin(m, sql, key, target)
        m.clear()
        val result = session.complete(m, late, raw(20))
        assertEquals(ExplainPlanSaveResult.CLEARED_OR_REMOVED, result.saved)
        assertTrue(result.baselines.isEmpty())
        assertTrue(m.entries.isEmpty())
        assertTrue(session.complete(m, session.begin(m, sql, key, target), raw(30)).baselines.isEmpty())
    }
}
