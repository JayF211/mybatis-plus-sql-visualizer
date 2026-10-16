package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.share.*
import cn.song.mybatisplus.visualizer.ui.insight.OfflineTraceView
import cn.song.mybatisplus.visualizer.ui.insight.boundedTextArea
import org.junit.Assert.*
import org.junit.Test
import javax.swing.JButton
import javax.swing.JTextField
import javax.swing.SwingUtilities

class OfflineTraceViewTest {
    @Test fun `no parse or external link without explicit click and valid template`() {
        SwingUtilities.invokeAndWait {
            var parsed = 0; var opened = 0
            val view = OfflineTraceView({ it }, { parsed++ }, {}, { opened++ })
            view.input.text = "{}"
            assertEquals(0, parsed)
            val buttons = view.results.actions.components.filterIsInstance<JButton>()
            buttons.first().doClick(); assertEquals(1, parsed)
            val span = ImportedDatabaseSpan("1".repeat(32), "2".repeat(16), null, null, "SELECT", "mysql", 0, 1, ImportedSpanStatus.UNSET)
            view.present(OfflineTraceImport.Result.Accepted(OfflineTraceSnapshot(listOf(span), 0)))
            buttons.last().doClick(); assertEquals(0, opened)
            view.results.actions.components.filterIsInstance<JTextField>().single().text = "https://example.com/trace/{traceId}"
            buttons.last().doClick(); assertEquals(1, opened)
            assertTrue(view.results.status.text.contains("unknown_external_coverage") || view.results.status.text == "import.invalidLink")
        }
    }
    @Test fun `input component rejects oversize text without allocating unbounded document`() {
        SwingUtilities.invokeAndWait {
            val area = boundedTextArea(10)
            area.text = "valid"
            area.append("01234567890")
            assertEquals("valid", area.text)
        }
    }
}
