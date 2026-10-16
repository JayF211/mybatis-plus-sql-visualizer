package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.log.insight.*
import cn.song.mybatisplus.visualizer.ui.insight.*
import org.junit.Assert.*
import org.junit.Test
import javax.swing.JCheckBox
import javax.swing.SwingUtilities

class RuntimeInsightViewTest {
    @Test fun `opening and refreshing never opt in and close disables once`() {
        SwingUtilities.invokeAndWait {
            val changes = mutableListOf<Boolean>()
            val source = object : RuntimeInsightSource {
                override fun enable(enabled: Boolean) { changes += enabled }
                override fun snapshot() = RuntimeInsightSnapshot(emptyList(), emptyList(), emptyList())
            }
            val view = RuntimeInsightView({ it }, source, {}, {}, {})
            view.present(source.snapshot())
            assertTrue(changes.isEmpty())
            view.workload.actions.components.filterIsInstance<JCheckBox>().single().doClick()
            assertEquals(listOf(true), changes)
            view.dispose(); view.dispose()
            assertEquals(listOf(true, false), changes)
        }
    }
    @Test fun `request without terminal or cross thread proof remains inconclusive`() {
        val evidence = SqlRequestEvidence("request", "run", true, false, null, null, null, null, false, true, emptyList())
        val request = ObservedRequest("request", null, null, null, evidence, 0L, emptyList())
        val snapshot = RuntimeInsightSnapshot(emptyList(), listOf(request), emptyList())
        val row = RuntimeInsightPresentation.requests(snapshot, SqlRequestBudget(100, 1_000_000)).single()
        assertEquals("INCONCLUSIVE", row.cells[2])
        assertTrue(row.detail.contains("cross_thread_coverage_unknown"))
    }
    @Test fun `page baseline does not invent producer totals for empty capture`() {
        val snapshot = RuntimeInsightSnapshot(emptyList(), emptyList(), emptyList())
        val baseline = RuntimeInsightPresentation.baseline(snapshot)
        assertNull(baseline.generatedCount)
        assertEquals(SqlBudgetStatus.INCONCLUSIVE, SqlWorkloadBaselineComparator.compare(baseline, baseline).status)
    }
}
