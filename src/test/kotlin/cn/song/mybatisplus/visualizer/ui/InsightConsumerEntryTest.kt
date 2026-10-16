package cn.song.mybatisplus.visualizer.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JButton
import cn.song.mybatisplus.visualizer.ui.insight.InsightDetailPanel

class InsightConsumerEntryTest : BasePlatformTestCase() {
    fun testSchemaAndImportInputsAreExplicitAndDoNotMountPlaceholderTabsAtConstruction() {
        val panel = SqlPreviewPanel(null)
        try {
            assertNull(panel.contentTabPane)
            panel.showSchemaComparison()
            assertNotNull(panel.previewTabs.component("schema-comparison"))
            panel.showOfflineTraceImport()
            assertNotNull(panel.previewTabs.component("offline-trace"))
            assertEquals(3, panel.contentTabPane!!.tabCount)
            assertNull(panel.current)
            panel.clear()
            assertNull(panel.contentTabPane)
        } finally { Disposer.dispose(panel) }
    }
    fun testNonXmlScenarioExplainsUnsupportedInputWithoutChangingPreview() {
        val panel = SqlPreviewPanel(null)
        try {
            panel.showParameterScenarios()
            assertEquals(insightText("scenario.xmlOnly"), panel.scenarioInsightView!!.panel.status.text)
            assertTrue(panel.scenarioInsightView!!.panel.actions.components.filterIsInstance<JButton>().last().isEnabled.not())
            assertNull(panel.current)
        } finally { Disposer.dispose(panel) }
    }
    fun testReadOnlyDetailsDoNotInterpretHtmlOrAllowEditing() {
        val panel = InsightDetailPanel(listOf("name"), "notice")
        panel.showRows(listOf(cn.song.mybatisplus.visualizer.ui.insight.InsightDetailRow(listOf("<html><img src='file:///secret'>"), "plain")))
        assertFalse(panel.table.isCellEditable(0, 0))
        val renderer = panel.table.prepareRenderer(panel.table.getCellRenderer(0, 0), 0, 0) as javax.swing.JLabel
        assertNull(renderer.getClientProperty("html"))
        assertEquals("plain", panel.detail.text)
    }
}
