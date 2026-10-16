package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.log.insight.*
import cn.song.mybatisplus.visualizer.ui.insight.RequestBaselineView
import org.junit.Assert.*
import org.junit.Test
import java.awt.Component
import java.awt.Container
import javax.swing.*

class RequestBaselineViewTest {
    private fun all(root: Component): List<Component> = listOf(root) + if (root is Container) root.components.flatMap(::all) else emptyList()
    @Test fun `switching request rejects an earlier capture callback`() {
        SwingUtilities.invokeAndWait {
            val pending = mutableListOf<() -> Unit>()
            val view = RequestBaselineView({ it }, { task, apply -> pending += { apply(task()) } }, {})
            fun request(id: String): ObservedRequest {
                val e = SqlRequestEvidence(id, "run", true, false, null, null, null, null, false, true, emptyList())
                return ObservedRequest(id, null, null, null, e, 0L, emptyList())
            }
            view.present(listOf(request("A"), request("B")))
            val components = all(view)
            val fields = components.filterIsInstance<JTextField>()
            fields[0].text = "local"; fields[1].text = "scenario"
            val choices = components.filterIsInstance<JComboBox<*>>().single()
            choices.selectedIndex = 0
            components.filterIsInstance<JButton>().single { it.text == "baseline.capture" }.doClick()
            choices.selectedIndex = 1
            pending.single()()
            assertEquals("", components.filterIsInstance<JTextArea>().single { it.isEditable }.text)
        }
    }

    @Test fun `baseline capture load and copy require explicit selection and clicks`() {
        SwingUtilities.invokeAndWait {
            var submitted = 0
            val copied = mutableListOf<String>()
            val view = RequestBaselineView({ it }, { task, apply -> submitted++; apply(task()) }, { copied += it })
            val evidence = SqlRequestEvidence("request", "run", true, false, null, null, null, null, false, true, emptyList())
            view.present(listOf(ObservedRequest("request", null, null, null, evidence, 0L, emptyList())))
            val components = all(view)
            val fields = components.filterIsInstance<JTextField>()
            fields[0].text = "local test"; fields[1].text = "scenario-login"
            fun click(key: String) = components.filterIsInstance<JButton>().single { it.text == key }.doClick()
            click("baseline.capture")
            assertEquals(0, submitted)
            components.filterIsInstance<JComboBox<*>>().single().selectedIndex = 0
            click("baseline.capture")
            assertEquals(1, submitted)
            val json = components.filterIsInstance<JTextArea>().single { it.isEditable }.text
            assertTrue(json.startsWith("{"))
            val decoded = RequestBaselineCodec.decode(json) as RequestBaselineCodec.DecodeResult.Ready
            assertFalse(decoded.baseline.complete)
            assertFalse(json.contains("scenario-login"))
            assertTrue(copied.isEmpty())
            click("baseline.copy")
            assertEquals(listOf(json), copied)
            click("baseline.evaluate")
            assertTrue(components.filterIsInstance<JTextArea>().single { !it.isEditable }.text.contains("INCONCLUSIVE"))
        }
    }
}
