package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.sql.insight.AnalysisQuality
import cn.song.mybatisplus.visualizer.ui.insight.*
import org.junit.Assert.*
import org.junit.Test
import javax.swing.*

class InsightSummaryTest {
    @Test fun `summary copy requires click is byte bounded and stale content cannot copy`() {
        SwingUtilities.invokeAndWait {
            val copies = mutableListOf<String>()
            val panel = InsightPanel({ it }, {}, {}, {}, {}, copySummary = { copies += it })
            val presentation = InsightPresentation("<html>plain", List(1000) { InsightItem("\u4e2d".repeat(1000)) }, emptyList(), emptyList(), true, AnalysisQuality.UNKNOWN)
            panel.present(presentation)
            assertTrue(copies.isEmpty())
            val north = (panel.layout as java.awt.BorderLayout).getLayoutComponent(java.awt.BorderLayout.NORTH) as JPanel
            val toolbar = (north.layout as java.awt.BorderLayout).getLayoutComponent(java.awt.BorderLayout.NORTH) as JPanel
            val button = toolbar.components.filterIsInstance<JButton>().single { it.text == "copySummary" }
            button.doClick()
            assertEquals(1, copies.size)
            assertTrue(copies.single().startsWith("<html>plain"))
            assertTrue(copies.single().toByteArray(Charsets.UTF_8).size <= 16 * 1024)
            panel.markStale(); button.doClick(); assertEquals(1, copies.size)
        }
    }
}
