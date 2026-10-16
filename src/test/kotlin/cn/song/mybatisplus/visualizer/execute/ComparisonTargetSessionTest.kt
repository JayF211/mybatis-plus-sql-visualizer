package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.*
import org.junit.Test

class ComparisonTargetSessionTest {
    @Test fun `explicit selection SQL invalidation and clear advance generation`() {
        val handle = Any()
        val option = ComparisonTargetOption("id", "Local fixture", "H2")
        val session = ComparisonTargetSession { listOf(ComparisonTargetSession.Record(option, handle, "digest")) }
        assertNull(session.snapshot())
        assertEquals(listOf(option), session.listTargets())
        val first = session.select("id")!!
        session.invalidateSql(); val edited = session.snapshot()!!
        assertTrue(edited.generation > first.generation)
        session.clear(); assertNull(session.snapshot())
        assertTrue(session.select("id")!!.generation > edited.generation)
    }
    @Test fun `configuration changes replacement and removal invalidate authorization`() {
        var record = ComparisonTargetSession.Record(ComparisonTargetOption("id", "test", "H2"), Any(), "one")
        var present = true
        val session = ComparisonTargetSession { if (present) listOf(record) else emptyList() }
        val first = session.select("id")!!
        record = record.copy(configurationFingerprint = "two")
        val changed = session.refresh()!!; assertTrue(changed.generation > first.generation)
        record = record.copy(handle = Any())
        assertTrue(session.refresh()!!.generation > changed.generation)
        present = false; assertNull(session.refresh()); assertNull(session.snapshot())
    }
    @Test fun `late selection cannot overwrite newer input generation`() {
        val option = ComparisonTargetOption("id", "test", "H2")
        val session = ComparisonTargetSession { listOf(ComparisonTargetSession.Record(option, Any(), "a")) }
        val oldGeneration = session.generationToken()
        session.invalidateSql()
        assertNull(session.select("id", oldGeneration))
        assertNull(session.snapshot())
        assertNotNull(session.select("id", session.generationToken()))
    }
    @Test fun `ambiguous identity never selects a target`() {
        val option = ComparisonTargetOption("same", "test", "H2")
        val session = ComparisonTargetSession { listOf(ComparisonTargetSession.Record(option, Any(), "a"),
            ComparisonTargetSession.Record(option, Any(), "b")) }
        assertNull(session.select("same")); assertNull(session.select("missing"))
    }
}
