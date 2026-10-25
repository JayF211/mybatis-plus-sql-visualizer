package cn.song.mybatisplus.visualizer.ui.insight

import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

internal data class InsightDetailRow(val cells: List<String>, val detail: String)

/** Reusable local snapshot viewer: cells and details are always plain text, never HTML. */
internal class InsightDetailPanel(headers: List<String>, notice: String) : JPanel(BorderLayout()) {
    val actions = JPanel(FlowLayout(FlowLayout.LEADING))
    val status = JTextArea(notice).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; rows = 3 }
    val detail = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    val table = JTable().apply {
        setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            init { putClientProperty("html.disable", true) }
        })
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
    }
    private val headers = headers.toTypedArray()
    private var rows = emptyList<InsightDetailRow>()
    init {
        // T-InsightDarkThemeFix：分隔条/滚动条必须用平台组件——原生 JSplitPane 的 Windows LAF
        // 分隔条与 JScrollPane 滚动条不跟随 Darcula，深色主题下在明细表底部呈现为一条浅色白条
        //（2026-10-25 沙盒实测）。OnePixelSplitter 的 1px 分隔条取主题色，JBScrollPane 同理。
        val split = OnePixelSplitter(true, 0.45f).apply {
            firstComponent = JBScrollPane(table)
            secondComponent = JBScrollPane(detail)
        }
        add(JPanel(BorderLayout()).apply { add(actions, BorderLayout.NORTH); add(status, BorderLayout.CENTER) }, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) detail.text = rows.getOrNull(table.selectedRow)?.detail.orEmpty()
        }
        showRows(emptyList())
    }
    fun button(label: String, action: () -> Unit): JButton = JButton(label).also { b -> b.addActionListener { action() }; actions.add(b) }
    fun showRows(values: List<InsightDetailRow>) {
        rows = values.take(2000)
        table.model = object : DefaultTableModel(rows.map { it.cells.toTypedArray() }.toTypedArray(), headers) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
        detail.text = ""
        if (rows.isNotEmpty()) table.setRowSelectionInterval(0, 0)
    }
    fun selectedRow(): Int = table.selectedRow
}
