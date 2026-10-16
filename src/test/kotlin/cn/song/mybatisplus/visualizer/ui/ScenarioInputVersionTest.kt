package cn.song.mybatisplus.visualizer.ui

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ScenarioInputVersionTest : BasePlatformTestCase() {
    fun testSamePreviewParameterChangeRejectsCapturedScenarioCallback() {
        val document = EditorFactory.getInstance().createDocument("SELECT ?")
        val version = ScenarioInputVersion(2, document, document.modificationStamp, mapOf("id" to "1"))
        var presented = 0
        val callback = { values: Map<String, String> -> if (version.matches(2, document, values)) presented++ }
        callback(mapOf("id" to "2"))
        assertEquals(0, presented)
        callback(mapOf("id" to "1"))
        assertEquals(1, presented)
        WriteCommandAction.runWriteCommandAction(project) { document.setText("") }
        callback(mapOf("id" to "1"))
        assertEquals(1, presented)
    }
    fun testDocumentEditInvalidatesScenarioAndRemovesExportAndActualDiff() {
        val panel = SqlPreviewPanel(null)
        try {
            panel.nativeEditorDoc = EditorFactory.getInstance().createDocument("SELECT 1")
            panel.previewTabs.put("scenarios", "scenarios", javax.swing.JPanel())
            panel.previewTabs.put("scenario-export", "export", javax.swing.JPanel())
            panel.previewTabs.put("observed-sql-diff", "diff", javax.swing.JPanel())
            val ticket = panel.previewTabs.beginRefresh("scenarios")!!
            WriteCommandAction.runWriteCommandAction(project) { panel.nativeEditorDoc!!.setText("SELECT 2") }
            assertFalse(panel.previewTabs.applyIfCurrent(ticket) { fail("stale callback") })
            assertNull(panel.previewTabs.component("scenario-export"))
            assertNull(panel.previewTabs.component("observed-sql-diff"))
        } finally { Disposer.dispose(panel) }
    }
}
