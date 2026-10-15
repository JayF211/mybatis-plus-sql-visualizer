package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

internal class SqlListPreviewHost(
    private val list: JList<LogSqlEntry>,
    private val listPane: JComponent,
    private val preview: SqlDockedPreview,
    private val splitterKey: String = "mpvs.sqlList.preview.split",
    private val showIconHint: Boolean = false,
    private val onOpenChanged: (Boolean) -> Unit = {},
) {
    val component = JPanel(BorderLayout()).apply { add(listPane, BorderLayout.CENTER) }
    private val hint = JPanel(BorderLayout()).apply {
        val message = JTextArea(MpSqlVisualizerBundle.message(
            if (showIconHint) "log.dockedPreview.migration.icon" else "log.dockedPreview.migration",
        )).apply {
            columns = 20
            rows = 3
            minimumSize = java.awt.Dimension(0, 0)
            lineWrap = true
            wrapStyleWord = true
            isEditable = false
            isFocusable = false
            isOpaque = false
            border = javax.swing.BorderFactory.createEmptyBorder(4, 6, 0, 6)
        }
        add(message, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(JButton(MpSqlVisualizerBundle.message("log.dockedPreview.open")).apply {
                addActionListener { toggle() }
            })
            add(JButton(MpSqlVisualizerBundle.message("log.dockedPreview.acknowledge")).apply {
                addActionListener { hideHint() }
            })
        }, BorderLayout.SOUTH)
        isVisible = false
    }
    private val split by lazy {
        OnePixelSplitter(true, splitterKey, 0.65f).apply {
            firstComponent = listPane
            secondComponent = preview.component
            background = UIUtil.getPanelBackground()
            isShowDividerControls = false
            setHonorComponentsMinimumSize(true)
            listPane.minimumSize = java.awt.Dimension(100, 80)
            preview.component.minimumSize = java.awt.Dimension(100, 80)
        }
    }

    private var pendingHintEntry: LogSqlEntry? = null
    private val hintTimer = javax.swing.Timer(300) { showPendingHint() }.apply { isRepeats = false }

    internal val hintVisibleForTest: Boolean get() = hint.isVisible
    internal val hintScheduledForTest: Boolean get() = hintTimer.isRunning
    internal fun showPendingHintForTest() = showPendingHint()
    internal val previewOpenForTest: Boolean get() = preview.isOpen

    init {
        component.add(hint, BorderLayout.SOUTH)
    }

    fun toggle() {
        if (preview.isOpen) {
            preview.close()
            split.firstComponent = null
            component.remove(split)
            component.add(listPane, BorderLayout.CENTER)
        } else {
            hideHint()
            preview.open()
            component.remove(listPane)
            split.firstComponent = listPane
            component.add(split, BorderLayout.CENTER)
            SwingUtilities.invokeLater {
                if (preview.isOpen) list.requestFocusInWindow()
            }
        }
        onOpenChanged(preview.isOpen)
        component.revalidate()
        component.repaint()
    }

    fun onRowClick(event: MouseEvent) {
        if (event.clickCount >= 2) {
            hideHint()
            return
        }
        if (preview.isOpen || hint.isVisible || event.clickCount != 1 ||
            !SwingUtilities.isLeftMouseButton(event) || isMultiSelectIntent(event.modifiersEx) ||
            list.selectedIndices.size != 1 || PluginSettings.getInstance().state.sqlListPreviewHintSeen
        ) return
        val index = list.locationToIndex(event.point)
        if (index < 0 || list.getCellBounds(index, index)?.contains(event.point) != true) return
        pendingHintEntry = list.model.getElementAt(index)
        hintTimer.restart()
    }

    private fun showPendingHint() {
        hintTimer.stop()
        val entry = pendingHintEntry ?: return
        pendingHintEntry = null
        if (preview.isOpen || list.selectedIndices.size != 1 || list.selectedValue !== entry ||
            PluginSettings.getInstance().state.sqlListPreviewHintSeen
        ) return
        PluginSettings.getInstance().let { it.loadState(it.state.copy(sqlListPreviewHintSeen = true)) }
        hint.isVisible = true
        component.revalidate()
        component.repaint()
    }

    fun hideHint() {
        hintTimer.stop()
        pendingHintEntry = null
        hint.isVisible = false
        component.revalidate()
        component.repaint()
    }
}
