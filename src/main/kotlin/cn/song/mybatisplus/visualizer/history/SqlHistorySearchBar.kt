package cn.song.mybatisplus.visualizer.history

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.border.EmptyBorder

/**
 * SQL 历史记录搜索栏（F-7 Phase C）：搜索框 + 标签筛选下拉 + 收藏筛选。
 *
 * <p>搜索逻辑：子串匹配（SQL 文本，忽略大小写），输入即时触发 [onSearch] 回调。
 * 标签筛选与收藏筛选经回调驱动外层面板刷新。
 */
class SqlHistorySearchBar(
    private val onSearch: (String) -> Unit,
    private val onFilterChange: () -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)) {

    private val searchField = JBTextField(20).apply {
        emptyText.text = MpSqlVisualizerBundle.message("history.panel.search")
        border = BorderFactory.createCompoundBorder(
            border,
            JBUI.Borders.empty(2, 4),
        )
    }

    /** 筛选模式下拉：全部 / 收藏。 */
    private val filterCombo = JComboBox<String>()

    /** 标签下拉（动态填充）。 */
    private val tagCombo = JComboBox<String>()

    init {
        border = EmptyBorder(4, 4, 4, 4)

        // 搜索框：回车 / 即时输入触发
        searchField.addActionListener { emitSearch() }
        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = emitSearch()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = emitSearch()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = emitSearch()
        })
        // ESC 清空
        searchField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clear")
        searchField.actionMap.put("clear", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                searchField.text = ""
                emitSearch()
            }
        })

        // 筛选下拉
        filterCombo.addItem(MpSqlVisualizerBundle.message("history.filter.all"))
        filterCombo.addItem(MpSqlVisualizerBundle.message("history.filter.favorites"))
        filterCombo.addActionListener { onFilterChange() }

        // 标签下拉（占位，由 [refreshTags] 填充）
        tagCombo.addItem(MpSqlVisualizerBundle.message("history.filter.all"))
        tagCombo.addActionListener { onFilterChange() }

        add(searchField)
        add(JLabel(MpSqlVisualizerBundle.message("history.filter.all") + ":"))
        add(filterCombo)
        add(JLabel("Tag:"))
        add(tagCombo)
    }

    private fun emitSearch() {
        onSearch(searchField.text?.trim() ?: "")
    }

    /** 当前搜索词。 */
    val searchQuery: String get() = searchField.text?.trim() ?: ""

    /** 当前筛选模式：null = 全部，"__favorites__" = 收藏，其他 = 标签名。 */
    val activeFilter: String?
        get() = when (filterCombo.selectedIndex) {
            1 -> FAVORITES_FILTER
            else -> {
                val tagIdx = tagCombo.selectedIndex
                if (tagIdx <= 0) null else tagCombo.getItemAt(tagIdx)
            }
        }

    /** 刷新标签下拉选项（保留当前选中若仍存在）。 */
    fun refreshTags(allTags: Set<String>) {
        val current = tagCombo.selectedItem as? String
        tagCombo.removeAllItems()
        tagCombo.addItem(MpSqlVisualizerBundle.message("history.filter.all"))
        allTags.sorted().forEach { tagCombo.addItem(it) }
        if (current != null && allTags.contains(current)) {
            tagCombo.selectedItem = current
        }
    }

    // ------------------------------------------------------------------
    // 测试驱动口（T-TestGuard-07：搜索/筛选逻辑此前零覆盖）
    // ------------------------------------------------------------------

    /** 模拟用户输入搜索词（触发 document 监听 → onSearch）。 */
    internal fun searchTextForTest(text: String) {
        searchField.text = text
    }

    /** 直选筛选下拉（0=全部 / 1=收藏）。 */
    internal fun selectFilterIndexForTest(index: Int) {
        filterCombo.selectedIndex = index
    }

    /** 直选标签下拉（null=全部）。 */
    internal fun selectTagForTest(tag: String?) {
        tagCombo.selectedItem = tag
    }

    /** 读标签下拉指定索引的选项。 */
    internal fun tagItemAtForTest(index: Int): String? = tagCombo.getItemAt(index)

    companion object {
        /** 收藏筛选的内部标记值。 */
        const val FAVORITES_FILTER = "__favorites__"
    }
}
