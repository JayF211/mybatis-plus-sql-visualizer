package cn.song.mybatisplus.visualizer.ui.insight

import cn.song.mybatisplus.visualizer.sql.insight.*
import cn.song.mybatisplus.visualizer.ui.PreviewTabManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.util.Disposer
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit, offline analysis only. All document/tab access runs on EDT; workers own immutable snapshots. */
internal class PreviewInsightController(
    private val tabs: PreviewTabManager,
    private val text: (String) -> String,
    private val dialect: () -> SqlInsightDialect,
    private val execute: (() -> Unit) -> Future<*>,
    private val dispatch: (() -> Unit) -> Unit,
    private val navigate: (Document, SqlSourceRange) -> Boolean,
    private val analyze: (AnalysisSnapshot, () -> Boolean) -> SqlSemanticModel = SqlSemanticAnalyzer::analyze,
    private val nanoTime: () -> Long = System::nanoTime,
    private val snapshotFactory: (String, SqlInsightDialect, String) -> AnalysisSnapshot = { sql, d, version -> AnalysisSnapshot(sql, d, sourceVersion = version) },
    private val metadataGeneration: () -> Long = { 0L },
    private val clearMetadata: () -> Unit = {},
) : Disposable {
    private var document: Document? = null
    private var listenerLife: Disposable? = null
    private var view: InsightPanel? = null
    private var generation = 0L
    private var closed = false
    private var active: Run? = null
    private var displayed: Run? = null

    private class Run(
        val document: Document,
        val stamp: Long,
        val generation: Long,
        val ticket: PreviewTabManager.RefreshTicket,
        val snapshot: AnalysisSnapshot,
        val started: Long,
        val metadataGeneration: Long,
        val cancelled: AtomicBoolean = AtomicBoolean(),
        var future: Future<*>? = null,
    )

    fun bind(next: Document?) {
        if (closed || document === next) return
        invalidate()
        listenerLife?.let(Disposer::dispose)
        listenerLife = null
        document = next
        if (next != null) {
            val lifetime = Disposer.newDisposable("SqlPreview.insight.document")
            listenerLife = lifetime
            next.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) { invalidate() }
            }, lifetime)
        }
    }

    fun invalidate() {
        if (closed) return
        generation++
        cancelActive()
        displayed = null
        view?.markStale()
    }

    fun open() {
        if (closed) return
        if (view == null) {
            val panel = InsightPanel(text, ::refresh, ::cancel, { tabs.remove(TAB_ID) }, ::locate, {
                clearMetadata(); invalidate(); refresh()
            })
            if (!tabs.put(TAB_ID, text("title"), panel, onRemove = {
                    cancelActive(); displayed = null; view = null
                })) return
            view = panel
        }
        tabs.pane?.selectedComponent = view
        refresh()
    }

    fun refresh() {
        if (closed) return
        val panel = view ?: return
        cancelActive()
        displayed = null
        panel.markStale()
        val doc = document
        if (doc == null) { panel.status("noDocument"); return }
        if (doc.textLength > MAX_INPUT) { panel.status("inputLimit"); return }
        // Empty text is authoritative: never fall back to the previous PreviewResult.sql.
        val sql = doc.text
        if (sql.isBlank()) { panel.status("empty"); return }
        val ticket = tabs.beginRefresh(TAB_ID) { cancelActive() } ?: return
        val run = Run(doc, doc.modificationStamp, generation, ticket,
            snapshotFactory(sql, dialect(), "$generation:${doc.modificationStamp}"), nanoTime(), metadataGeneration())
        active = run
        panel.status("loading")
        try {
            run.future = execute {
                try {
                    val cancelled = { run.cancelled.get() || Thread.currentThread().isInterrupted || nanoTime() - run.started > BUDGET_NANOS }
                    if (cancelled()) {
                        dispatch { if (isCurrent(run)) tabs.applyIfCurrent(run.ticket) { panel.status("timeLimit") } }
                        return@execute
                    }
                    val model = analyze(run.snapshot, cancelled)
                    val presentation = if (cancelled()) null else InsightPresenter.build(model, text, cancelled)
                    dispatch {
                        if (isCurrent(run)) tabs.applyIfCurrent(run.ticket) {
                            if (presentation == null) panel.status("timeLimit") else {
                                displayed = run
                                panel.present(presentation)
                            }
                        }
                    }
                } catch (_: java.util.concurrent.CancellationException) {
                    dispatch { if (isCurrent(run)) tabs.applyIfCurrent(run.ticket) { panel.status("timeLimit") } }
                } catch (_: Exception) {
                    dispatch { if (isCurrent(run)) tabs.applyIfCurrent(run.ticket) { panel.status("failed") } }
                }
            }
        } catch (_: Exception) {
            if (isCurrent(run)) panel.status("failed")
        }
    }

    private fun isCurrent(run: Run): Boolean = !closed && !run.cancelled.get() && active === run &&
        generation == run.generation && metadataGeneration() == run.metadataGeneration && document === run.document && document?.modificationStamp == run.stamp &&
        tabs.component(TAB_ID) === view

    fun cancel() {
        cancelActive()
        displayed = null
        view?.status("cancelled")
    }

    private fun cancelActive() {
        val run = active
        active = null
        run?.cancelled?.set(true)
        run?.future?.cancel(true)
    }

    private fun locate(range: SqlSourceRange?) {
        val run = displayed ?: return
        if (!isCurrent(run)) { view?.markStale(); return }
        tabs.applyIfCurrent(run.ticket) {
            val exact = InsightPresenter.validRange(range, run.snapshot.sql.length)
            if (navigate(run.document, InsightPresenter.navigationRange(range, run.snapshot.sql.length))) {
                view?.status(if (exact) "exactLocation" else "wholeLocation")
            } else view?.status("stale")
        }
    }

    override fun dispose() {
        if (closed) return
        closed = true
        generation++
        cancelActive()
        displayed = null
        listenerLife?.let(Disposer::dispose)
        listenerLife = null
        document = null
        tabs.remove(TAB_ID)
        view = null
    }

    internal fun panelForTest(): InsightPanel? = view
    internal fun generationForTest(): Long = generation

    companion object {
        const val TAB_ID = "analysis"
        const val MAX_INPUT = 65_536
        private const val BUDGET_NANOS = 2_000_000_000L
    }
}
