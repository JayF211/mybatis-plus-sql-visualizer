package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.ui.insight.ScenarioInsightPresentation
import cn.song.mybatisplus.visualizer.ui.insight.ScenarioInsightView
import cn.song.mybatisplus.visualizer.xml.*
import org.junit.Assert.*
import org.junit.Test
import javax.swing.JButton
import javax.swing.SwingUtilities

class ScenarioInsightViewTest {
    @Test fun `current plus stored snapshots are bounded copied and uniquely named`() {
        val current = mutableMapOf("id" to "1")
        val stored = (1..25).map { "current" to mapOf("id" to "$it") }
        val inputs = ScenarioInsightPresentation.inputs(current, stored)
        current["id"] = "changed"
        assertEquals(20, inputs.size)
        assertEquals(20, inputs.map { it.name }.distinct().size)
        assertEquals("1", inputs.first().values["id"])
    }
    @Test fun `trace details explicitly retain expanded path precision`() {
        val row = ScenarioInsightPresentation.row(XmlScenarioResult("x", "SELECT 1", 0, mapOf("x" to false),
            XmlRenderTrace(listOf(XmlRenderTraceEvent("statement/1", 1, "IfSqlNode", "x", false, "PREVIEW_OVERRIDE", null)), false),
            null, listOf("warning")), "local label")
        assertTrue(row.detail.contains("EXPANDED_STATEMENT_NODE_PATH"))
        assertTrue(row.detail.contains("statement/1"))
        assertTrue(row.detail.contains("UNKNOWN"))
    }
    @Test fun `export is only projected after explicit preview click and stale state disables it`() {
        SwingUtilities.invokeAndWait {
            val exports = mutableListOf<String>()
            val view = ScenarioInsightView({ it }, {}, {}, {}, exports::add)
            view.present(listOf(XmlScenarioResult("secret-preset", "SELECT 'password' FROM private_table", 1, emptyMap(), null, null, emptyList())), listOf("secret-preset"), "notice")
            assertTrue(exports.isEmpty())
            val button = view.panel.actions.components.filterIsInstance<JButton>().last()
            button.doClick()
            assertEquals(1, exports.size)
            assertFalse(exports.single().contains("password"))
            assertFalse(exports.single().contains("secret-preset"))
            view.clear("stale")
            assertFalse(button.isEnabled)
        }
    }
}
