package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JComponent
import javax.swing.JPanel

/** Capture prepareDefaultConsole's actual callbacks; only DBN dispatch is replaced, not the callback logic. */
class SqlPreviewPrepareCallbackLifecycleTest : BasePlatformTestCase() {
    private data class Delivery(val console: (JComponent) -> Unit, val editor: (Editor) -> Unit)
    private val panels = mutableListOf<SqlPreviewPanel>()
    private val nativeEditors = mutableListOf<Editor>()

    private fun result(sql: String) = PreviewResult(sql, sql, emptyList(), null, null, null)
    private fun panel(): Pair<SqlPreviewPanel, MutableList<Delivery>> {
        val deliveries = mutableListOf<Delivery>()
        val panel = SqlPreviewPanel(project)
        panel.prepareConsoleForTest = { _, console, editor -> deliveries += Delivery(console, editor) }
        panels += panel
        return panel to deliveries
    }
    private fun readyEditor(sql: String): Editor = EditorFactory.getInstance().createEditor(
        EditorFactory.getInstance().createDocument(sql)).also { nativeEditors += it }

    override fun tearDown() {
        try {
            panels.forEach { panel ->
                panel.prepareConsoleForTest = null
                panel.detachConsole()
                panel.nativeEditorDoc = null
                panel.releaseEditorForTest()
                Disposer.dispose(panel)
            }
            nativeEditors.filterNot { it.isDisposed }.forEach { EditorFactory.getInstance().releaseEditor(it) }
        } finally { super.tearDown() }
    }

    fun testLivePreparationAcceptsEditorBeforeConsoleAttachment() {
        val (panel, deliveries) = panel()
        panel.showResult(result("SELECT 1"))
        val native = readyEditor("SELECT 1")
        deliveries.single().editor(native)
        assertSame(native.document, panel.nativeEditorDoc)
        assertSame(native, panel.nativeConsoleEditor)
        assertNull(panel.editorForTest())
    }

    fun testClearedPanelRejectsOldEditorAfterAnotherPanelStartsPreparing() {
        val (a, first) = panel()
        a.showResult(result("SELECT 1"))
        val late = first.single()
        a.clear()
        val (b, second) = panel()
        b.showResult(result("SELECT 2"))
        val bEditor = b.editorForTest()
        assertEquals(1, second.size)
        late.editor(readyEditor("SELECT 1"))
        assertNull("cleared A must not regain a native document", a.nativeEditorDoc)
        assertSame("A callback must not disturb B's editor", bEditor, b.editorForTest())
        assertNull(b.nativeEditorDoc)
    }

    fun testDisposedPanelRejectsOldEditorAfterAnotherPanelStartsPreparing() {
        val (a, first) = panel()
        a.showResult(result("SELECT 1"))
        val late = first.single()
        Disposer.dispose(a)
        val (b, second) = panel()
        b.showResult(result("SELECT 2"))
        assertEquals(1, second.size)
        late.editor(readyEditor("SELECT 1"))
        assertNull("disposed A must not retain a newly delivered native editor", a.nativeEditorDoc)
        assertNull(b.nativeEditorDoc)
    }

    fun testSamePanelReplacementRejectsPriorResultEditor() {
        val (panel, deliveries) = panel()
        panel.showResult(result("SELECT 1"))
        val old = deliveries.single()
        panel.showResult(result("SELECT 2"))
        val expected = panel.editorForTest()
        old.editor(readyEditor("SELECT 1"))
        assertSame("old preparation must not release the new preview editor", expected, panel.editorForTest())
        assertNull(panel.nativeEditorDoc)
    }

    fun testClearedPanelRejectsOldConsoleComponent() {
        val (panel, deliveries) = panel()
        panel.showResult(result("SELECT 1"))
        val old = deliveries.single()
        panel.clear()
        val component = JPanel()
        old.console(component)
        assertNull("cleared preview must not embed a late console component", panel.embeddedConsole)
        assertNull(component.parent)
    }
}
