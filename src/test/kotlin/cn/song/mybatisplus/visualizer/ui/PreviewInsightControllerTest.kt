package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.sql.insight.*
import cn.song.mybatisplus.visualizer.ui.insight.PreviewInsightController
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.BorderLayout
import java.util.concurrent.CompletableFuture
import javax.swing.JPanel

class PreviewInsightControllerTest : BasePlatformTestCase() {
    private class Queue {
        val workers = mutableListOf<() -> Unit>()
        val callbacks = mutableListOf<() -> Unit>()
        fun execute(task: () -> Unit): java.util.concurrent.Future<*> {
            workers += task
            return CompletableFuture<Unit>()
        }
        fun work() { workers.removeAt(0)() }
        fun apply() { callbacks.toList().also { callbacks.clear() }.forEach { it() } }
    }
    private fun tabs(): PreviewTabManager {
        val sql = JPanel()
        return PreviewTabManager(JPanel(BorderLayout()).apply { add(sql, BorderLayout.CENTER) }, sql, "SQL")
    }
    private fun controller(tabs: PreviewTabManager, queue: Queue, analyze: (AnalysisSnapshot, () -> Boolean) -> SqlSemanticModel = SqlSemanticAnalyzer::analyze) =
        PreviewInsightController(tabs, { it }, { SqlInsightDialect.POSTGRESQL }, queue::execute, { queue.callbacks += it }, { _, _ -> true }, analyze)

    fun testRealAnalyzerProducesContentAndEditingToEmptyInvalidatesQueuedResult() {
        val queue = Queue(); val tabs = tabs(); val controller = controller(tabs, queue)
        try {
            val doc = EditorFactory.getInstance().createDocument("SELECT t.id FROM t")
            controller.bind(doc); controller.open(); queue.work(); queue.apply()
            assertEquals("ready", controller.panelForTest()!!.statusText())
            assertTrue(controller.panelForTest()!!.tree.rowCount > 1)
            controller.refresh(); queue.work()
            val generation = controller.generationForTest()
            WriteCommandAction.runWriteCommandAction(project) { doc.setText("") }
            assertTrue(controller.generationForTest() > generation)
            queue.apply()
            assertEquals("stale", controller.panelForTest()!!.statusText())
            controller.refresh()
            assertEquals("empty", controller.panelForTest()!!.statusText())
            assertTrue(queue.workers.isEmpty())
        } finally { controller.dispose() }
    }

    fun testDocumentIdentityTabReopenAndDisposeRejectLateCallbacks() {
        val queue = Queue(); val tabs = tabs(); val controller = controller(tabs, queue)
        val first = EditorFactory.getInstance().createDocument("SELECT t.id FROM t")
        val second = EditorFactory.getInstance().createDocument(first.text)
        try {
            controller.bind(first); controller.open(); queue.work()
            controller.bind(second); queue.apply()
            assertEquals("stale", controller.panelForTest()!!.statusText())
            controller.refresh(); queue.work()
            tabs.remove("analysis"); controller.open(); queue.apply()
            assertEquals("loading", controller.panelForTest()!!.statusText())
            queue.work(); queue.apply()
            assertEquals("ready", controller.panelForTest()!!.statusText())
            controller.refresh(); queue.work(); controller.dispose(); queue.apply()
            assertNull(tabs.component("analysis"))
            controller.open(); assertNull(tabs.component("analysis"))
        } finally { controller.dispose() }
    }

    fun testLargeInputAndCancelledQueuedWorkNeverCallAnalyzer() {
        var calls = 0
        val queue = Queue(); val tabs = tabs()
        val controller = controller(tabs, queue) { snapshot, cancelled -> calls++; SqlSemanticAnalyzer.analyze(snapshot, cancelled) }
        try {
            controller.bind(EditorFactory.getInstance().createDocument("x".repeat(65_537)))
            controller.open()
            assertEquals("inputLimit", controller.panelForTest()!!.statusText())
            assertTrue(queue.workers.isEmpty())
            controller.bind(EditorFactory.getInstance().createDocument("SELECT 1"))
            controller.refresh(); controller.cancel(); queue.work(); queue.apply()
            assertEquals("cancelled", controller.panelForTest()!!.statusText())
            assertEquals(0, calls)
        } finally { controller.dispose() }
    }

    fun testMetadataGenerationChangeRejectsAlreadyComputedResult() {
        val queue = Queue(); val tabs = tabs(); val binding = AnalysisMetadataBinding()
        val controller = PreviewInsightController(tabs, { it }, { SqlInsightDialect.POSTGRESQL }, queue::execute,
            { queue.callbacks += it }, { _, _ -> true }, snapshotFactory = binding::snapshot,
            metadataGeneration = binding::generation, clearMetadata = binding::clear)
        try {
            controller.bind(EditorFactory.getInstance().createDocument("SELECT t.id FROM t"))
            controller.open(); queue.work()
            binding.clear()
            queue.apply()
            assertEquals("loading", controller.panelForTest()!!.statusText())
            controller.refresh(); queue.work(); queue.apply()
            assertEquals("ready", controller.panelForTest()!!.statusText())
        } finally { controller.dispose() }
    }

    fun testTimeBudgetStopsBeforeAnalyzerAndReleasesLoadingState() {
        val queue = Queue(); val tabs = tabs(); var now = 0L; var calls = 0
        val controller = PreviewInsightController(tabs, { it }, { SqlInsightDialect.POSTGRESQL }, queue::execute,
            { queue.callbacks += it }, { _, _ -> true },
            { snapshot, cancelled -> calls++; SqlSemanticAnalyzer.analyze(snapshot, cancelled) }, { now })
        try {
            controller.bind(EditorFactory.getInstance().createDocument("SELECT 1"))
            controller.open(); now = 3_000_000_000L; queue.work(); queue.apply()
            assertEquals(0, calls)
            assertEquals("timeLimit", controller.panelForTest()!!.statusText())
        } finally { controller.dispose() }
    }

    fun testMenuHasExplicitActionAndBothEditorDocumentFieldsRebindController() {
        val panel = SqlPreviewPanel(null)
        val queue = Queue()
        val controller = controller(panel.previewTabs, queue)
        try {
            panel.insightController = controller
            assertTrue(panel.moreActions().any { it.first == cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("insight.action") })
            val editor = EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument("SELECT 1"))
            panel.editor = editor
            controller.open(); queue.work(); queue.apply()
            val before = controller.generationForTest()
            panel.nativeEditorDoc = EditorFactory.getInstance().createDocument("")
            assertTrue(controller.generationForTest() > before)
            controller.refresh()
            assertEquals("empty", controller.panelForTest()!!.statusText())
            panel.current = cn.song.mybatisplus.visualizer.model.PreviewResult("SELECT 2", "SELECT 2", emptyList(), null, null, null)
            assertEquals("stale", controller.panelForTest()!!.statusText())
            panel.clear()
            assertNull(panel.previewTabs.component("analysis"))
        } finally { panel.clear(); com.intellij.openapi.util.Disposer.dispose(panel) }
    }
}
