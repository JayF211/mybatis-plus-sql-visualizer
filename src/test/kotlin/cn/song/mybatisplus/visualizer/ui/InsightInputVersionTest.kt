package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.sql.insight.SqlSourceLocation
import cn.song.mybatisplus.visualizer.ui.insight.InsightInputVersion
import org.junit.Assert.*
import org.junit.Test
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class InsightInputVersionTest {
    @Test fun `either input edit invalidates capture and close rejects late application`() {
        SwingUtilities.invokeAndWait {
            val a = JTextArea("before"); val b = JTextArea("after"); var changes = 0
            val gate = InsightInputVersion({ changes++ }, a.document, b.document)
            val first = gate.capture(); assertTrue(gate.isCurrent(first))
            b.append("changed"); assertFalse(gate.isCurrent(first))
            val second = gate.capture(); a.text = ""; assertFalse(gate.isCurrent(second))
            assertTrue(changes >= 2)
            val third = gate.capture(); gate.close(); assertFalse(gate.isCurrent(third))
        }
    }
    @Test fun `source navigation requires matching PSI version and bounded host range`() {
        val location = SqlSourceLocation("file", 3, 10, 7)
        assertTrue(usageLocationIsCurrent(location, 7, 20))
        assertFalse(usageLocationIsCurrent(location, 8, 20))
        assertFalse(usageLocationIsCurrent(location, 7, 9))
        assertFalse(usageLocationIsCurrent(location.copy(modificationStamp = null), 7, 20))
    }
}
