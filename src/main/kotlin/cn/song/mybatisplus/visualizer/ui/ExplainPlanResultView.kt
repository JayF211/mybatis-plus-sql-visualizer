package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.table.TableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeModel

/**
 * R33：EXPLAIN 结构化渲染视图（自建执行链的结果面，双通道并存——不替换原生控制台通道）。
 *
 * 布局（循仓内既有形态）：
 *  - NORTH 头部：`EXPLAIN · 方言 · 数据源 · 耗时`（数据源/耗时段缺省省略，不虚标）＋
 *    「回到原生控制台」入口（[onBackToConsole] 非 null 才出现——双通道并存的手动入口，
 *    自动回退走调用方的 fail-closed 链，不经此处）；
 *  - 索引建议条：[hints] 非空才占位（ExplainIndexAdvisor 规则集的面板侧消费位——
 *    T-EI-01 定案的「IDE 面建议条」挂账由本条落地；只提示不执行）；
 *  - CENTER：TABLE 格式 → 只读 [JBTable]（循 buildColumnInfoPanel 既有表格形态）；
 *    JSON 格式 → 可折叠 [JTree]（[ExplainJsonPlanTree] 纯逻辑构建）；其余格式调用方不应到达
 *    （ExplainPanelChannel 判定已挡，兜底给如实占位文案）。
 *  - SOUTH 尾部：notices（驱动 WARNING/NOTICE，T-NoticeChannel 同源数据）非空时灰字逐条透出。
 */
class ExplainPlanResultView(
    dialectName: String,
    dataSourceName: String?,
    elapsedMs: Long,
    raw: McpExplainFormatter.RawResult,
    format: ExplainOutputFormat,
    hints: List<ExplainIndexAdvisor.Hint> = emptyList(),
    onBackToConsole: (() -> Unit)? = null,
) : JBPanel<ExplainPlanResultView>(BorderLayout()) {

    /** TABLE 格式的表格模型（其他格式 null）。 */
    private val tableModel: TableModel?

    /** JSON 格式的树模型（其他格式 null）。 */
    private val treeModel: TreeModel?

    private val hintsLabel: JBLabel
    private val backButton: JButton
    private val headerLabel: JBLabel

    init {
        border = JBUI.Borders.empty()
        isOpaque = false

        // 头部：EXPLAIN · 方言 · 数据源 · 耗时（＋截断标记——诚实呈现行预算边界）
        headerLabel = JBLabel(
            buildString {
                append("<html><b>").append(MpSqlVisualizerBundle.message("panel.explain.structured.title"))
                append("</b> · ").append(dialectName)
                dataSourceName?.let { append(" · ").append(it) }
                if (elapsedMs >= 0) append(" · ").append(elapsedMs).append(" ms")
                if (raw.truncated) append(" · ").append(MpSqlVisualizerBundle.message("panel.explain.structured.truncated"))
                append("</html>")
            },
        ).apply { border = JBUI.Borders.empty(2, 4, 2, 4) }

        backButton = JButton(MpSqlVisualizerBundle.message("panel.explain.structured.backToConsole")).apply {
            isOpaque = false
            isContentAreaFilled = false
            border = JBUI.Borders.empty(1, 6)
            font = font.deriveFont(11f)
            foreground = JBColor.namedColor("Link.activeForeground", JBColor.GRAY)
            margin = JBUI.insets(0, 6, 0, 6)
            toolTipText = MpSqlVisualizerBundle.message("panel.explain.structured.backToConsole.tip")
            val action = onBackToConsole
            if (action == null) {
                isVisible = false
            } else {
                addActionListener { action() }
            }
        }

        // 索引建议条（只提示不执行；空清单不占位——与 MCP formatHints 的零追加同口径）
        hintsLabel = JBLabel(
            if (hints.isEmpty()) "" else "<html>" + hints.joinToString("<br>") { hint ->
                "[${hint.rule}] <b>${escapeHtml(hint.target)}</b>: ${escapeHtml(hint.advice)}"
            } + "</html>",
        ).apply {
            foreground = JBColor.namedColor("WarningForeground", JBColor.ORANGE)
            border = JBUI.Borders.empty(1, 4)
            isVisible = hints.isNotEmpty()
        }

        val north = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty()
            add(headerLabel, BorderLayout.NORTH)
            add(hintsLabel, BorderLayout.CENTER)
            add(backButton, BorderLayout.EAST)
        }
        add(north, BorderLayout.NORTH)

        // 中央内容：按格式分派（结构化档只有 TABLE/JSON 会到达——ExplainPanelChannel 判定已挡）
        var table: TableModel? = null
        var tree: TreeModel? = null
        val content: JComponent = when (format) {
            ExplainOutputFormat.TABLE -> {
                val model = object : javax.swing.table.DefaultTableModel(
                    raw.rows.map { it.toTypedArray() }.toTypedArray(),
                    raw.headers.toTypedArray(),
                ) {
                    override fun isCellEditable(row: Int, column: Int): Boolean = false
                }
                table = model
                JBTable(model).apply {
                    autoResizeMode = JBTable.AUTO_RESIZE_ALL_COLUMNS
                    tableHeader.reorderingAllowed = false
                }
            }
            ExplainOutputFormat.JSON -> {
                val roots = ExplainJsonPlanTree.build(raw.rows).orEmpty()
                val rootNode = DefaultMutableTreeNode("EXPLAIN")
                roots.forEach { r -> rootNode.add(toSwingNode(r)) }
                tree = DefaultTreeModel(rootNode)
                JTree(tree).apply {
                    isRootVisible = false
                    showsRootHandles = true
                }
            }
            else -> JBLabel(MpSqlVisualizerBundle.message("panel.explain.structured.unsupportedFormat"))
        }
        add(JBScrollPane(content).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)

        // 驱动 notices 尾部透出（T-NoticeChannel 同源数据；空清单零追加）
        if (raw.notices.isNotEmpty()) {
            add(
                JBLabel("<html>" + raw.notices.joinToString("<br>") { escapeHtml(it) } + "</html>").apply {
                    foreground = JBColor.GRAY
                    border = JBUI.Borders.empty(1, 4)
                },
                BorderLayout.SOUTH,
            )
        }

        tableModel = table
        treeModel = tree
    }

    private fun toSwingNode(node: ExplainJsonPlanTree.Node): DefaultMutableTreeNode {
        val swing = DefaultMutableTreeNode(node.label)
        node.children.forEach { swing.add(toSwingNode(it)) }
        return swing
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ---------- 测试探针（internal，同 SqlResultPanel.currentErrorForTest 惯例） ----------

    internal fun tableModelForTest(): TableModel? = tableModel

    internal fun treeModelForTest(): TreeModel? = treeModel

    internal fun hintsTextForTest(): String? = if (hintsLabel.isVisible) hintsLabel.text else null

    internal fun hintsComponentForTest(): JComponent = hintsLabel

    internal fun backButtonForTest(): JButton = backButton

    internal fun headerTextForTest(): String = headerLabel.text
}
