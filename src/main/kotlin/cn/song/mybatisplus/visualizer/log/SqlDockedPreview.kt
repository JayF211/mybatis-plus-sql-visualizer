package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingConstants

internal class SqlDockedPreview(
    private val project: Project,
    private val list: JList<LogSqlEntry>,
    private val onClose: () -> Unit,
) : Disposable {
    val component = JPanel(BorderLayout())
    private val status = JLabel(MpSqlVisualizerBundle.message("log.dockedPreview.empty"))
    private val restore = JButton(MpSqlVisualizerBundle.message("log.dockedPreview.restore")).apply {
        isVisible = false
        addActionListener { restoreAndFollow() }
    }
    private var content: SqlClickPreviewContent? = null
    private var displayedEntry: LogSqlEntry? = null
    private var originalSql: String? = null
    private var updating = false
    private var opened = false

    val isOpen: Boolean get() = opened
    internal val displayedSqlForTest: String? get() = content?.editor?.document?.text?.takeIf { displayedEntry != null }
    internal val editorForTest: com.intellij.openapi.editor.Editor? get() = content?.editor
    internal val isPinnedForTest: Boolean get() = isPinned()
    internal fun restoreAndFollowForTest() = restoreAndFollow()

    init {
        component.preferredSize = java.awt.Dimension(200, 220)
        component.add(JPanel(BorderLayout()).apply {
            add(status, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { add(restore) }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        component.add(JLabel(MpSqlVisualizerBundle.message("log.dockedPreview.empty"), SwingConstants.CENTER), BorderLayout.CENTER)
        list.addListSelectionListener { if (!it.valueIsAdjusting) refreshSelection() }
        list.model.addListDataListener(object : javax.swing.event.ListDataListener {
            override fun intervalAdded(e: javax.swing.event.ListDataEvent) = refreshSelection()
            override fun intervalRemoved(e: javax.swing.event.ListDataEvent) = refreshSelection()
            override fun contentsChanged(e: javax.swing.event.ListDataEvent) = refreshSelection()
        })
    }

    fun open() {
        opened = true
        refreshSelection()
    }

    fun close() {
        opened = false
    }

    fun resetContext() {
        displayedEntry = null
        originalSql = null
        restore.isVisible = false
        val editor = content?.editor
        if (editor != null && editor.document.text.isNotEmpty()) {
            updating = true
            try {
                WriteCommandAction.runWriteCommandAction(project) { editor.document.setText("") }
            } finally {
                updating = false
            }
        }
        status.text = MpSqlVisualizerBundle.message("log.dockedPreview.empty")
        showCenter(JLabel(status.text, SwingConstants.CENTER))
    }

    fun refreshSelection() {
        if (!opened || isPinned()) return
        val entry = list.selectedValue?.takeIf { list.selectedIndices.size == 1 }
        if (entry == null) {
            displayedEntry = null
            originalSql = null
            status.text = MpSqlVisualizerBundle.message(
                if (list.selectedIndices.size > 1) "log.dockedPreview.multiple" else "log.dockedPreview.empty",
            )
            showCenter(JLabel(status.text, SwingConstants.CENTER))
            return
        }
        if (displayedEntry === entry) return
        val state = PluginSettings.getInstance().state
        val sql = clickPreviewSql(entry.restoredSql, state.logFormatSql, state.displayMaskEnabled)
        val current = content ?: SqlClickPreview.buildContent(project, sql, copyText = {
            cn.song.mybatisplus.visualizer.util.UiTextClipboard.copy(it)
        }, close = onClose).also { built ->
            content = built
            built.editor.document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = updatePinnedStatus()
            })
        }
        if (current.editor.document.text != sql) {
            updating = true
            try {
                WriteCommandAction.runWriteCommandAction(project) { current.editor.document.setText(sql) }
            } finally {
                updating = false
            }
        }
        displayedEntry = entry
        originalSql = sql
        status.text = MpSqlVisualizerBundle.message("log.dockedPreview.title")
        restore.isVisible = false
        showCenter(current.component)
    }

    private fun isPinned(): Boolean = displayedEntry != null &&
        content?.editor?.document?.text != originalSql

    private fun updatePinnedStatus() {
        if (updating || displayedEntry == null) return
        val pinned = isPinned()
        status.text = MpSqlVisualizerBundle.message(
            if (pinned) "log.dockedPreview.pinned" else "log.dockedPreview.title",
        )
        restore.isVisible = pinned
    }

    private fun restoreAndFollow() {
        val original = originalSql ?: return
        val editor = content?.editor ?: return
        updating = true
        try {
            WriteCommandAction.runWriteCommandAction(project) { editor.document.setText(original) }
        } finally {
            updating = false
        }
        restore.isVisible = false
        refreshSelection()
        updatePinnedStatus()
    }

    private fun showCenter(next: java.awt.Component) {
        (component.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER)?.let { component.remove(it) }
        component.add(next, BorderLayout.CENTER)
        component.revalidate()
        component.repaint()
    }

    override fun dispose() {
        content?.editor?.let { EditorFactory.getInstance().releaseEditor(it) }
        content = null
    }
}
