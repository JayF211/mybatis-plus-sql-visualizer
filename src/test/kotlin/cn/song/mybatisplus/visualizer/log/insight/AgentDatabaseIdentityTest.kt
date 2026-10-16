package cn.song.mybatisplus.visualizer.log.insight

import cn.song.mybatisplus.visualizer.log.agent.AgentEvent
import cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter
import cn.song.mybatisplus.visualizer.log.agent.JdbcUrlDialects
import org.junit.Assert.*
import org.junit.Test

class AgentDatabaseIdentityTest {
    private fun event(url: String?, run: String = "run", connection: String? = "c1", execution: String = "e1") = AgentEvent(
        sql = "SELECT id FROM users", timestamp = 1, durationNanos = 1, updateCount = null, errorText = null,
        sourceFile = "Mapper.java", sourceLine = 1, methodKey = "Mapper#find", requestTag = null, params = emptyList(),
        threadName = "main", url = url, runId = run, executionId = execution, sequence = 1, captureEpoch = 1,
        startNanos = 1, timestampSource = "ENTRY_WALL_MILLIS", resultStatus = "SUCCESS", connectionId = connection)
    private fun identity(e: AgentEvent) = AgentAnalysisProjection.observation(e, AgentEventAdapter.toEntry(e))!!.databaseIdentity!!

    @Test fun `display badge already includes host but identity uses canonical endpoint not display`() {
        assertTrue(JdbcUrlDialects.badgeName("jdbc:mysql://db-a:3306/app")!!.contains("db-a"))
        assertNotEquals(identity(event("jdbc:mysql://db-a/app")), identity(event("jdbc:mysql://db-b/app")))
        assertEquals(identity(event("jdbc:mysql://DB-A/app")), identity(event("jdbc:mysql://db-a:3306/app")))
        assertNotEquals(identity(event("jdbc:mysql://db-a/app")), identity(event("jdbc:mysql://db-a/other")))
    }

    @Test fun `credentials and nonidentity query options do not change or leak target identity`() {
        val a = identity(event("jdbc:postgresql://alice:secret@db/app?user=alice&password=secret&ssl=true"))
        val b = identity(event("jdbc:postgresql://bob:changed@db:5432/app?password=changed&user=bob&ssl=false&applicationName=client"))
        assertEquals(a, b)
        assertTrue(a.startsWith("jdbc-target:"))
        assertFalse(a.contains("secret") || a.contains("alice") || a.contains("db/app"))
    }

    @Test fun `explicit schema changes identity without query secret contamination`() {
        val a = identity(event("jdbc:postgresql://db/app?currentSchema=tenant_a&password=x"))
        val b = identity(event("jdbc:postgresql://db/app?password=y&currentSchema=tenant_a"))
        assertEquals(a, b)
        assertNotEquals(a, identity(event("jdbc:postgresql://db/app?currentSchema=tenant_b")))
        assertNotEquals(a, identity(event("jdbc:postgresql://db/app")))
    }

    @Test fun `unsupported oracle multihost malformed and routing options remain visibly unknown`() {
        for (url in listOf(null, "junk", "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=a)))", "jdbc:mysql://a,b/app",
            "jdbc:postgresql://db", "jdbc:mysql://db:99999/app", "jdbc:postgresql://db/app?options=-c%20search_path=x",
            "jdbc:postgresql://db/app?currentSchema=a&currentSchema=b")) {
            val a = identity(event(url))
            assertTrue("$url -> $a", a.startsWith("unknown-connection:"))
            assertNotEquals(a, identity(event(url, connection = "c2")))
            assertNotEquals(a, identity(event(url, run = "other-run")))
        }
    }

    @Test fun `unknown without connection id isolates execution instead of merging null database buckets`() {
        val a = identity(event("jdbc:unknown:data", connection = null))
        assertTrue(a.startsWith("unknown-event:"))
        assertNotEquals(a, identity(event("jdbc:unknown:data", connection = null, execution = "e2")))
        val store = AnalysisSessionStore()
        val ticket = store.start(AnalysisCaptureBoundary("run", 1, 0))
        listOf(event("jdbc:unknown:data", connection = null), event("jdbc:unknown:data", connection = null, execution = "e2").copy(sequence = 2)).forEach {
            store.accept(ticket, AgentAnalysisProjection.observation(it, AgentEventAdapter.toEntry(it))!!)
        }
        assertEquals(2, store.snapshot().groups.size)
    }
}
