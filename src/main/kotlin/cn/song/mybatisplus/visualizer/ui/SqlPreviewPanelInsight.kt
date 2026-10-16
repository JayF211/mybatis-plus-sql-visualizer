package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.sql.insight.SqlInsightDialect
import cn.song.mybatisplus.visualizer.ui.insight.PreviewInsightController
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ScrollType

/** Current document, including empty text. A detached native console is not this preview's input. */
internal fun SqlPreviewPanel.insightDocument(): Document? = nativeEditorDoc ?: editor?.document

internal fun SqlPreviewPanel.syncInsightDocument() {
    val doc = if (previewDisposed) null else insightDocument()
    insightController?.bind(doc)
    bindAuxiliaryInsightDocument(doc)
}

internal fun SqlPreviewPanel.showSqlInsight() {
    if (previewDisposed || project?.isDisposed == true) return
    val controller = insightController ?: PreviewInsightController(
        previewTabs,
        text = { MpSqlVisualizerBundle.message("insight.$it") },
        dialect = {
            when (current?.effectiveDialect?.name) {
                "MySQL" -> SqlInsightDialect.MYSQL
                "PostgreSQL" -> SqlInsightDialect.POSTGRESQL
                else -> SqlInsightDialect.UNKNOWN
            }
        },
        execute = { task -> ApplicationManager.getApplication().executeOnPooledThread(Runnable { task() }) },
        dispatch = { task -> ApplicationManager.getApplication().invokeLater { if (!previewDisposed && project?.isDisposed != true) task() } },
        snapshotFactory = analysisMetadataBinding::snapshot,
        metadataGeneration = analysisMetadataBinding::generation,
        clearMetadata = analysisMetadataBinding::clear,
        navigate = { doc, range ->
            val target = nativeConsoleEditor?.takeIf { !it.isDisposed && it.document === doc }
                ?: editor?.takeIf { !it.isDisposed && it.document === doc }
            if (target == null) false else {
                contentTabPane?.let { pane ->
                    (0 until pane.tabCount).map { pane.getComponentAt(it) }
                        .firstOrNull { javax.swing.SwingUtilities.isDescendingFrom(target.component, it) }
                        ?.let { pane.selectedComponent = it }
                }
                target.selectionModel.setSelection(range.startOffset, range.endOffsetExclusive)
                target.caretModel.moveToOffset(range.startOffset)
                target.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
                target.contentComponent.requestFocusInWindow()
                true
            }
        },
    ).also { insightController = it }
    controller.bind(insightDocument())
    controller.open()
}
