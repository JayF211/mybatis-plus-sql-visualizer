package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.log.agent.*
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.ui.insight.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import javax.swing.*

class WriteImpactAssociationEntryTest : BasePlatformTestCase() {
    private fun all(root: Component): List<Component> = listOf(root) + if (root is Container) root.components.flatMap(::all) else emptyList()
    fun testWriteEvidenceAndChosenTransactionShareDetailAndEditingInvalidates() {
        val panel = SqlPreviewPanel(null)
        try {
            val sql = "DELETE FROM account WHERE id=7"
            panel.current = PreviewResult(sql, sql, emptyList(), null, null, "account", renderedDialect = MySqlDialect)
            panel.nativeEditorDoc = EditorFactory.getInstance().createDocument(sql)
            val workers = mutableListOf<() -> Unit>()
            panel.observedBackgroundForTest = { workers += it }
            val tx = TransactionEvent(TraceEventEnvelope("run-2", null, null, 1, 1, 1), "CLOSE", "conn-2", null, "tx-2", null, null, "SUCCESS", null, null, null, null, "THREAD_ONLY")
            panel.runtimeInsightSource = object : RuntimeInsightSource {
                override fun enable(enabled: Boolean) = Unit
                override fun snapshot() = RuntimeInsightSnapshot(emptyList(), emptyList(), listOf(tx))
            }
            panel.showObservedSqlDiff()
            workers.removeAt(0)(); UIUtil.dispatchAllInvocationEvents()
            val host = panel.previewTabs.component("observed-sql-diff")!!
            val components = all(host)
            fun click(key: String) = components.filterIsInstance<JButton>().single { it.text == insightText(key) }.doClick()
            click("runtime.loadTransactions")
            workers.removeAt(0)(); UIUtil.dispatchAllInvocationEvents()
            components.filterIsInstance<JComboBox<*>>().single().selectedIndex = 0
            click("runtime.associateTransaction")
            val detail = components.filterIsInstance<JTextArea>().map { it.text }.first { it.contains("COUNT probe") }
            assertTrue(detail.contains("target=account"))
            assertTrue(detail.contains("WHERE=id = 7"))
            assertTrue(detail.contains("NOT EXECUTED"))
            assertTrue(detail.contains("NOT_COMMIT"))
            assertTrue(detail.contains("run=run-2"))
            assertTrue(detail.contains("tx=tx-2"))
            assertTrue(detail.contains("MANUAL_NOTE_NOT_MATCHED"))
            WriteCommandAction.runWriteCommandAction(project) { panel.nativeEditorDoc!!.setText("DELETE FROM account WHERE id=8") }
            assertNull(panel.previewTabs.component("observed-sql-diff"))
        } finally { Disposer.dispose(panel) }
    }
}
