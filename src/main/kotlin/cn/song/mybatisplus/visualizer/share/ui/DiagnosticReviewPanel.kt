package cn.song.mybatisplus.visualizer.share.ui

import cn.song.mybatisplus.visualizer.share.DiagnosticShareDraft
import cn.song.mybatisplus.visualizer.share.ShareContentProjection
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.ui.components.JBLabel
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal class DiagnosticReviewPanel(
    private val draft: DiagnosticShareDraft,
    private val editor: JTextArea,
) : JPanel() {
    internal val consent = JCheckBox(MpSqlVisualizerBundle.message("share.review.consent"))
    private val bytes = JBLabel()
    internal val sections = linkedMapOf<String, JCheckBox>()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JBLabel(MpSqlVisualizerBundle.message("share.review.destination")))
        add(JBLabel(MpSqlVisualizerBundle.message("share.review.sectionReset")))
        draft.sections.forEach { section ->
            val box = JCheckBox(section.title, true)
            sections[section.id] = box
            box.addActionListener {
                draft.include(section.id, box.isSelected)
                editor.text = draft.body
                revokeConsent()
            }
            add(box)
        }
        editor.text = draft.body
        editor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
            private fun changed() {
                draft.edit(editor.text)
                revokeConsent()
            }
        })
        consent.addActionListener { draft.approve(consent.isSelected) }
        add(bytes)
        add(consent)
        refreshBytes()
    }

    fun revokeConsent() {
        consent.isSelected = false
        draft.approve(false)
        refreshBytes()
    }

    fun validationKey(): String? = when {
        draft.byteSize > ShareContentProjection.MAX_OUTPUT_BYTES -> "share.review.tooLarge"
        !draft.approved -> "share.review.needConsent"
        else -> null
    }

    fun takeSubmission(): DiagnosticShareDraft.Snapshot? {
        val snapshot = draft.takeSubmission()
        consent.isSelected = false
        return snapshot
    }

    private fun refreshBytes() {
        bytes.text = MpSqlVisualizerBundle.message("share.review.bytes", draft.byteSize, ShareContentProjection.MAX_OUTPUT_BYTES)
    }
}
