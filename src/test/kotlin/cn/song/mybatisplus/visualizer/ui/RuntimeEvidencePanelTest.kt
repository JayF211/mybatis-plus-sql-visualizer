package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.log.insight.*
import cn.song.mybatisplus.visualizer.ui.insight.*
import org.junit.Assert.*
import org.junit.Test
import javax.swing.*
import java.awt.image.BufferedImage

class RuntimeEvidencePanelTest {
    private fun event(id: String, start: Long) = SqlExecutionObservation("run", id, 1, 1, 0, start, 50, 1, "shape", "db", "site", "label", ExecutionObservationStatus.UNKNOWN)
    @Test fun `ignoring repeated evidence does not mutate underlying request facts`() {
        SwingUtilities.invokeAndWait {
            val events = listOf(event("1", 0), event("2", 20), event("3", 40))
            val evidence = SqlRequestEvidence("request", "run", true, false, null, null, null, null, false, true, events)
            val request = ObservedRequest("request", null, null, null, evidence, 90, listOf(RepeatedQueryEvidence("shape", "site", "db", 3, listOf("1", "2", "3"))))
            val snapshot = RuntimeInsightSnapshot(listOf(AnalysisSessionSnapshot(1, true, events, emptyList(), setOf("coverage_not_proven"), 100)), listOf(request), emptyList())
            val panel = RuntimeEvidencePanel({ it }); panel.present(snapshot)
            val tabs = panel.components.single() as JTabbedPane
            val alerts = tabs.getComponentAt(1) as InsightDetailPanel
            alerts.actions.components.filterIsInstance<JButton>().first().doClick()
            assertEquals(1, panel.ignoredForTest().size)
            assertEquals(0, alerts.table.rowCount)
            assertEquals(3, snapshot.requests.single().evidence.observations.size)
            alerts.actions.components.filterIsInstance<JButton>().last().doClick()
            assertEquals(1, alerts.table.rowCount)
        }
    }
    @Test fun `timeline paints overlapping intervals without platform plugins`() {
        SwingUtilities.invokeAndWait {
            val timeline = RuntimeTimeline(); timeline.present(listOf(event("1", -100), event("2", -60)), "one run")
            assertEquals(2, timeline.intervalCountForTest())
            timeline.setSize(900, 200)
            val image = BufferedImage(900, 200, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics(); try { timeline.paint(g) } finally { g.dispose() }
            assertTrue(timeline.preferredSize.height >= 100)
        }
    }
}
