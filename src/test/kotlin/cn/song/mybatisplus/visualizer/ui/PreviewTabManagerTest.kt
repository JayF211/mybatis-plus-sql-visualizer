package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.*
import org.junit.Test
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** H03：纯 Swing 页签契约，不加载 Database/Diagrams/JCEF，不创建窗口。 */
class PreviewTabManagerTest {
    private fun onEdt(test: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) test() else SwingUtilities.invokeAndWait(test)
    }

    @Test fun `four page lifecycles preserve SQL and unrelated selection`() = onEdt {
        val sql = JPanel()
        val host = JPanel(BorderLayout()).apply { add(sql, BorderLayout.CENTER) }
        val tabs = PreviewTabManager(host, sql, "SQL")
        repeat(3) {
            val columns = JPanel()
            val analysis = JPanel()
            val join = JPanel()
            var releases = 0
            tabs.put("columns", "same title", columns)
            tabs.put("analysis", "same title", analysis, onRemove = { releases++ })
            tabs.pane!!.selectedComponent = analysis
            tabs.put("join", "JOIN", join)
            assertEquals(4, tabs.pane!!.tabCount)
            assertSame(analysis, tabs.pane!!.selectedComponent)
            tabs.remove("join")
            assertEquals(3, tabs.pane!!.tabCount)
            assertSame(analysis, tabs.pane!!.selectedComponent)
            assertSame(columns, tabs.component("columns"))
            tabs.remove("analysis")
            assertSame(sql, tabs.pane!!.selectedComponent)
            assertEquals(1, releases)
            tabs.remove("analysis")
            assertEquals(1, releases)
            tabs.remove("columns")
            assertNull(tabs.pane)
            assertSame(sql, (host.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER))
        }
    }

    @Test fun `activation follows component not tab index or title`() = onEdt {
        val sql = JPanel()
        val host = JPanel(BorderLayout()).apply { add(sql, BorderLayout.CENTER) }
        val tabs = PreviewTabManager(host, sql, "SQL")
        val columns = JPanel()
        val join = JPanel()
        var activations = 0
        tabs.put("columns", "JOIN", columns)
        tabs.put("join", "JOIN", join, onSelected = { activations++ })
        tabs.pane!!.selectedComponent = columns
        assertEquals(0, activations)
        tabs.pane!!.selectedComponent = join
        assertEquals(1, activations)
        tabs.remove("columns")
        tabs.pane!!.selectedComponent = sql
        tabs.pane!!.selectedComponent = join
        assertEquals(2, activations)
        tabs.close()
    }

    @Test fun `replacement refreshes content and preserves selected identity`() = onEdt {
        val sql = JPanel()
        val tabs = PreviewTabManager(JPanel(BorderLayout()).apply { add(sql) }, sql, "SQL")
        val old = JPanel()
        val replacement = JPanel()
        var releases = 0
        tabs.put("columns", "columns", old, onRemove = { releases++ })
        tabs.pane!!.selectedComponent = old
        val ticket = tabs.beginRefresh("columns")!!
        tabs.put("columns", "renamed", replacement)
        assertSame(replacement, tabs.pane!!.selectedComponent)
        assertEquals(2, tabs.pane!!.tabCount)
        assertEquals(1, releases)
        assertFalse(tabs.applyIfCurrent(ticket) { fail("old component must not be updated") })
        tabs.put("columns", "renamed again", replacement)
        assertEquals(2, tabs.pane!!.tabCount)
        tabs.close()
    }

    @Test fun `ordinary cleanup failures still release all pages and retain suppressed failures`() = onEdt {
        val sql = JPanel()
        val tabs = PreviewTabManager(JPanel(BorderLayout()).apply { add(sql) }, sql, "SQL")
        val first = IllegalStateException("first cleanup")
        val second = IllegalArgumentException("second cleanup")
        var released = false
        tabs.put("first", "first", JPanel(), onRemove = { throw first })
        tabs.put("second", "second", JPanel(), onRemove = { throw second })
        tabs.put("last", "last", JPanel(), onRemove = { released = true })
        try {
            tabs.clear()
            fail("ordinary cleanup failure must be reported")
        } catch (failure: IllegalStateException) {
            assertSame(first, failure)
            assertEquals(listOf(second), failure.suppressed.toList())
        }
        assertTrue(released)
        assertNull(tabs.pane)
    }

    @Test fun `fatal cleanup error is never hidden behind an earlier ordinary failure`() = onEdt {
        val sql = JPanel()
        val tabs = PreviewTabManager(JPanel(BorderLayout()).apply { add(sql) }, sql, "SQL")
        val ordinary = IllegalStateException("first cleanup")
        val fatal = LinkageError("fatal cleanup")
        var laterReleased = false
        tabs.put("ordinary", "ordinary", JPanel(), onRemove = { throw ordinary })
        tabs.put("fatal", "fatal", JPanel(), onRemove = { throw fatal })
        tabs.put("later", "later", JPanel(), onRemove = { laterReleased = true })
        var caught: Throwable? = null
        try { tabs.clear() } catch (failure: Throwable) { caught = failure }
        assertSame("fatal must escape as the primary failure", fatal, caught)
        assertFalse("fatal must stop cleanup immediately", laterReleased)
        assertTrue(ordinary.suppressed.isEmpty())
        tabs.clear() // release the remaining ordinary page after the test has caught the synthetic fatal
        assertTrue(laterReleased)
    }

    @Test fun `late callbacks cannot cross refresh removal reopening clear or disposal`() = onEdt {
        val sql = JPanel()
        val tabs = PreviewTabManager(JPanel(BorderLayout()).apply { add(sql) }, sql, "SQL")
        val analysis = JPanel()
        var cancellations = 0
        var writes = 0
        tabs.put("analysis", "analysis", analysis)
        val old = tabs.beginRefresh("analysis") { cancellations++ }!!
        val latest = tabs.beginRefresh("analysis") { cancellations++ }!!
        assertEquals(1, cancellations)
        assertFalse(tabs.applyIfCurrent(old) { writes++ })
        assertTrue(tabs.applyIfCurrent(latest) { writes++ })
        tabs.remove("analysis")
        tabs.put("analysis", "analysis", analysis) // 同一组件重挂也不能复活旧请求。
        assertFalse(tabs.applyIfCurrent(latest) { writes++ })
        val reopened = tabs.beginRefresh("analysis")!!
        tabs.invalidateRefreshes()
        assertFalse(tabs.applyIfCurrent(reopened) { writes++ })
        val beforeClear = tabs.beginRefresh("analysis")!!
        tabs.clear()
        assertFalse(tabs.applyIfCurrent(beforeClear) { writes++ })
        tabs.put("analysis", "analysis", analysis)
        val beforeClose = tabs.beginRefresh("analysis")!!
        tabs.close()
        tabs.close()
        assertFalse(tabs.applyIfCurrent(beforeClose) { writes++ })
        assertNull(tabs.beginRefresh("analysis"))
        assertFalse(tabs.put("analysis", "analysis", JPanel()))
        assertEquals(1, writes)
        assertEquals(2, cancellations)
    }
}
