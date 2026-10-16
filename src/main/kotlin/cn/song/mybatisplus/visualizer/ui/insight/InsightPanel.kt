package cn.song.mybatisplus.visualizer.ui.insight

import cn.song.mybatisplus.visualizer.sql.insight.SqlSourceRange
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** JDK-only tree and directed graph; never interprets SQL/name/error text as HTML. */
internal class InsightPanel(
    private val text: (String) -> String,
    refresh: () -> Unit,
    cancel: () -> Unit,
    close: () -> Unit,
    private val navigate: (SqlSourceRange?) -> Unit,
    clearMetadata: () -> Unit = {},
) : JPanel(BorderLayout()) {
    private val status = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; rows = 2 }
    private val summary = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; rows = 4 }
    internal val tree = JTree(DefaultMutableTreeNode(text("structure"))).apply {
        cellRenderer = object : DefaultTreeCellRenderer() {
            init { putClientProperty("html.disable", true) }
            override fun getTreeCellRendererComponent(tree: JTree?, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, focus: Boolean): Component {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus)
                putClientProperty("html.disable", true)
                val node = value as? DefaultMutableTreeNode
                this.text = (node?.userObject as? InsightItem)?.text ?: node?.userObject?.toString().orEmpty()
                return this
            }
        }
        addTreeSelectionListener { (lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject.let { if (it is InsightItem) navigate(it.range) } }
    }
    internal val graph = InsightGraph(navigate)
    private val content = JTabbedPane().apply {
        addTab(text("tree"), JScrollPane(tree))
        addTab(text("graph"), JScrollPane(graph))
    }
    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEADING)).apply {
            add(JButton(text("refresh")).apply { addActionListener { refresh() } })
            add(JButton(text("cancel")).apply { addActionListener { cancel() } })
            add(JButton(text("metadata.clear")).apply { addActionListener { clearMetadata() } })
            add(JButton(text("close")).apply { addActionListener { close() } })
        }
        add(JPanel(BorderLayout()).apply { add(toolbar, BorderLayout.NORTH); add(summary, BorderLayout.CENTER) }, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
    }
    fun status(key: String) { status.text = text(key) }
    fun present(presentation: InsightPresentation) {
        summary.text = presentation.summary
        fun node(item: InsightItem): DefaultMutableTreeNode = DefaultMutableTreeNode(item).apply { item.children.forEach { add(node(it)) } }
        tree.model = DefaultTreeModel(DefaultMutableTreeNode(text("structure")).apply { presentation.items.forEach { add(node(it)) } })
        for (i in 0 until minOf(5, tree.rowCount)) tree.expandRow(i)
        graph.present(presentation.vertices, presentation.arrows)
        status(if (presentation.graphLimited) "limited" else "ready")
    }
    fun markStale() {
        tree.model = DefaultTreeModel(DefaultMutableTreeNode(text("stale")))
        graph.present(emptyList(), emptyList())
        summary.text = ""
        status("stale")
    }
    internal fun statusText(): String = status.text
}

internal class InsightGraph(private val navigate: (SqlSourceRange?) -> Unit) : JComponent() {
    private var vertices = emptyList<InsightVertex>()
    private var arrows = emptyList<InsightArrow>()
    private var boxes = emptyMap<String, Rectangle>()
    init {
        isOpaque = true
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                vertices.firstOrNull { boxes[it.id]?.contains(e.point) == true }?.let { navigate(it.range) }
            }
        })
    }
    fun present(nodes: List<InsightVertex>, edges: List<InsightArrow>) {
        vertices = nodes.take(InsightPresenter.MAX_NODES)
        arrows = edges.take(InsightPresenter.MAX_EDGES)
        boxes = vertices.mapIndexed { index, v -> v.id to Rectangle(24 + (index % 3) * 290, 28 + (index / 3) * 116, 238, 64) }.toMap()
        preferredSize = Dimension(890, maxOf(180, ((vertices.size + 2) / 3) * 116 + 40))
        revalidate(); repaint()
    }
    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = UIManager.getColor("Panel.background") ?: Color.WHITE
            g.fillRect(0, 0, width, height)
            g.color = UIManager.getColor("Label.foreground") ?: Color.BLACK
            arrows.forEach { edge ->
                val a = boxes[edge.from] ?: return@forEach
                val b = boxes[edge.to] ?: return@forEach
                val x1 = a.x + a.width / 2; val y1 = a.y + a.height
                val x2 = b.x + b.width / 2; val y2 = b.y
                if (a == b) {
                    g.drawArc(a.x + a.width - 14, a.y - 16, 40, 44, -80, 280)
                    g.drawLine(a.x + a.width, a.y, a.x + a.width + 7, a.y - 5)
                } else {
                    g.drawLine(x1, y1, x2, y2)
                    val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
                    for (delta in listOf(-0.45, 0.45)) g.drawLine(x2, y2,
                        (x2 - 11 * cos(angle + delta)).toInt(), (y2 - 11 * sin(angle + delta)).toInt())
                }
                g.drawString(edge.label.take(36), (x1 + x2) / 2 + 4, (y1 + y2) / 2)
            }
            vertices.forEach { vertex ->
                val b = boxes.getValue(vertex.id)
                g.color = UIManager.getColor("TextArea.background") ?: Color.WHITE
                g.fillRoundRect(b.x, b.y, b.width, b.height, 12, 12)
                g.color = UIManager.getColor("Label.foreground") ?: Color.BLACK
                g.drawRoundRect(b.x, b.y, b.width, b.height, 12, 12)
                vertex.text.take(56).chunked(28).forEachIndexed { index, line -> g.drawString(line, b.x + 8, b.y + 24 + index * 18) }
            }
        } finally { g.dispose() }
    }
}
