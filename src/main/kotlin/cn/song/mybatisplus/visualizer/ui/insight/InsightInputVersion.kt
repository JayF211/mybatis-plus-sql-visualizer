package cn.song.mybatisplus.visualizer.ui.insight

import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.Document

/** EDT-only shared gate for user-owned local text inputs. Workers receive copies and a captured version. */
internal class InsightInputVersion(private val changed: () -> Unit, vararg documents: Document) : AutoCloseable {
    private var version = 0L
    private var closed = false
    private val documents = documents.toList()
    private val listener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = invalidate()
        override fun removeUpdate(e: DocumentEvent) = invalidate()
        override fun changedUpdate(e: DocumentEvent) = invalidate()
    }
    init { this.documents.forEach { it.addDocumentListener(listener) } }
    fun capture(): Long = version
    fun isCurrent(token: Long): Boolean = !closed && token == version
    fun invalidate() { if (!closed) { version++; changed() } }
    override fun close() {
        if (closed) return
        closed = true; version++
        documents.forEach { it.removeDocumentListener(listener) }
    }
}
