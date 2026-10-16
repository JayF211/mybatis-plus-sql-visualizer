package cn.song.mybatisplus.visualizer.ui

import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane

/**
 * 预览附加页的唯一所有者。所有操作在 EDT 执行，后台只携带 [RefreshTicket]，回 EDT 后提交结果。
 * ID 不依赖标题或插入顺序；SQL 组件始终复用。没有附加页时恢复原布局，不创建占位功能。
 * onRemove 负责该次挂载的资源释放；beginRefresh 的取消回调负责在途任务。
 */
internal class PreviewTabManager(
    private val host: JPanel,
    private val sqlContent: JComponent,
    private val sqlTitle: String,
) {
    private class Entry(
        val component: JComponent,
        val onRemove: () -> Unit,
        val onSelected: () -> Unit,
        var refresh: RefreshTicket? = null,
    )

    internal class RefreshTicket internal constructor(
        internal val id: String,
        internal val component: JComponent,
        internal var cancel: (() -> Unit)?,
    )

    private val entries = linkedMapOf<String, Entry>()
    private var closed = false
    var pane: JTabbedPane? = null
        private set

    fun component(id: String): JComponent? = entries[id]?.component

    fun put(
        id: String,
        title: String,
        component: JComponent,
        onRemove: () -> Unit = {},
        onSelected: () -> Unit = {},
    ): Boolean {
        if (closed) return false
        require(component !== sqlContent && entries.none { it.key != id && it.value.component === component })
        val old = entries[id]
        if (old?.component === component) {
            pane?.let { it.setTitleAt(it.indexOfComponent(component), title) }
            return true
        }
        val tabs = ensurePane()
        val selected = tabs.selectedComponent
        val index = old?.let { tabs.indexOfComponent(it.component) } ?: tabs.tabCount
        if (old != null) {
            entries.remove(id)
            invalidate(old)
        }
        entries[id] = Entry(component, onRemove, onSelected)
        if (old != null) {
            tabs.setComponentAt(index, component)
            tabs.setTitleAt(index, title)
        } else {
            tabs.addTab(title, component)
        }
        tabs.selectedComponent = if (selected === old?.component) component else selected
        old?.onRemove?.invoke()
        repaintHost()
        return true
    }

    fun remove(id: String) {
        val entry = entries.remove(id) ?: return
        invalidate(entry) // 先作废，再触发 Swing 选择事件或资源释放。
        val tabs = pane ?: return
        val selected = tabs.selectedComponent
        if (selected === entry.component) tabs.selectedComponent = sqlContent
        tabs.remove(entry.component)
        if (entries.isEmpty()) {
            host.remove(tabs)
            tabs.remove(sqlContent)
            pane = null
            host.add(sqlContent, BorderLayout.CENTER)
        } else if (selected !== entry.component) {
            tabs.selectedComponent = selected
        }
        try {
            entry.onRemove()
        } finally {
            repaintHost()
        }
    }

    fun beginRefresh(id: String, cancel: () -> Unit = {}): RefreshTicket? {
        if (closed) return null
        val entry = entries[id] ?: return null
        invalidate(entry)
        return RefreshTicket(id, entry.component, cancel).also { entry.refresh = it }
    }

    fun applyIfCurrent(ticket: RefreshTicket, action: (JComponent) -> Unit): Boolean {
        val entry = entries[ticket.id] ?: return false
        if (closed || entry.refresh !== ticket || entry.component !== ticket.component) return false
        action(entry.component)
        return true
    }

    fun invalidateRefreshes() = entries.values.forEach(::invalidate)

    fun clear() {
        // 一个页面释放失败也不能阻止其余页面释放；最终仍向调用方报告失败。
        var failure: Throwable? = null
        entries.keys.toList().forEach {
            try {
                remove(it)
            } catch (t: Throwable) {
                if (failure == null) failure = t else failure!!.addSuppressed(t)
            }
        }
        failure?.let { throw it }
    }

    fun close() {
        if (closed) return
        closed = true
        clear()
    }

    private fun invalidate(entry: Entry) {
        val ticket = entry.refresh
        entry.refresh = null
        val cancel = ticket?.cancel
        ticket?.cancel = null
        cancel?.invoke()
    }

    private fun ensurePane(): JTabbedPane = pane ?: JTabbedPane().also { tabs ->
        host.remove(sqlContent)
        tabs.addTab(sqlTitle, sqlContent)
        var lastSelected = tabs.selectedComponent
        tabs.addChangeListener {
            val selected = tabs.selectedComponent
            if (selected !== lastSelected) {
                lastSelected = selected
                if (!closed) entries.values.firstOrNull { it.component === selected }?.onSelected?.invoke()
            }
        }
        pane = tabs
        host.add(tabs, BorderLayout.CENTER)
    }

    private fun repaintHost() {
        host.revalidate()
        host.repaint()
    }
}
