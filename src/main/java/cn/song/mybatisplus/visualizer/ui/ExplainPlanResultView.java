// T-SyncK2JMaster482 波D2 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ExplainPlanResultView.kt
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter;
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat;
import cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeModel;
import java.awt.BorderLayout;
import java.util.List;

/**
 * R33：EXPLAIN 结构化渲染视图（自建执行链的结果面，双通道并存——不替换原生控制台通道）。
 *
 * <p>布局（循仓内既有形态）：</p>
 * <ul>
 *  <li>NORTH 头部：{@code EXPLAIN · 方言 · 数据源 · 耗时}（数据源/耗时段缺省省略，不虚标）＋
 *    「回到原生控制台」入口（{@code onBackToConsole} 非 null 才出现——双通道并存的手动入口，
 *    自动回退走调用方的 fail-closed 链，不经此处）；</li>
 *  <li>索引建议条：{@code hints} 非空才占位（ExplainIndexAdvisor 规则集的面板侧消费位——
 *    T-EI-01 定案的「IDE 面建议条」挂账由本条落地；只提示不执行）；</li>
 *  <li>CENTER：TABLE 格式 → 只读 {@link JBTable}（循 buildColumnInfoPanel 既有表格形态）；
 *    JSON 格式 → 可折叠 {@link JTree}（{@link ExplainJsonPlanTree} 纯逻辑构建）；其余格式调用方不应到达
 *    （ExplainPanelChannel 判定已挡，兜底给如实占位文案）。</li>
 *  <li>SOUTH 尾部：notices（驱动 WARNING/NOTICE，T-NoticeChannel 同源数据）非空时灰字逐条透出。</li>
 * </ul>
 */
public class ExplainPlanResultView extends JBPanel<ExplainPlanResultView> {

    /** TABLE 格式的表格模型（其他格式 null）。 */
    private final TableModel tableModel;

    /** JSON 格式的树模型（其他格式 null）。 */
    private final TreeModel treeModel;

    private final JBLabel hintsLabel;
    private final JButton backButton;
    private final JBLabel headerLabel;

    /** Kotlin 默认参数 {@code hints = emptyList(), onBackToConsole = null} 的等价重载。 */
    public ExplainPlanResultView(@NotNull String dialectName,
                                 @Nullable String dataSourceName,
                                 long elapsedMs,
                                 @NotNull McpExplainFormatter.RawResult raw,
                                 @NotNull ExplainOutputFormat format) {
        this(dialectName, dataSourceName, elapsedMs, raw, format, List.of(), null);
    }

    /** Kotlin 默认参数 {@code onBackToConsole = null} 的等价重载。 */
    public ExplainPlanResultView(@NotNull String dialectName,
                                 @Nullable String dataSourceName,
                                 long elapsedMs,
                                 @NotNull McpExplainFormatter.RawResult raw,
                                 @NotNull ExplainOutputFormat format,
                                 @NotNull List<ExplainIndexAdvisor.Hint> hints) {
        this(dialectName, dataSourceName, elapsedMs, raw, format, hints, null);
    }

    public ExplainPlanResultView(@NotNull String dialectName,
                                 @Nullable String dataSourceName,
                                 long elapsedMs,
                                 @NotNull McpExplainFormatter.RawResult raw,
                                 @NotNull ExplainOutputFormat format,
                                 @NotNull List<ExplainIndexAdvisor.Hint> hints,
                                 @Nullable Runnable onBackToConsole) {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty());
        setOpaque(false);

        // 头部：EXPLAIN · 方言 · 数据源 · 耗时（＋截断标记——诚实呈现行预算边界）
        StringBuilder header = new StringBuilder();
        header.append("<html><b>").append(MpSqlVisualizerBundle.message("panel.explain.structured.title"));
        header.append("</b> · ").append(dialectName);
        if (dataSourceName != null) header.append(" · ").append(dataSourceName);
        if (elapsedMs >= 0) header.append(" · ").append(elapsedMs).append(" ms");
        if (raw.truncated()) header.append(" · ").append(MpSqlVisualizerBundle.message("panel.explain.structured.truncated"));
        header.append("</html>");
        headerLabel = new JBLabel(header.toString());
        headerLabel.setBorder(JBUI.Borders.empty(2, 4, 2, 4));

        backButton = new JButton(MpSqlVisualizerBundle.message("panel.explain.structured.backToConsole"));
        backButton.setOpaque(false);
        backButton.setContentAreaFilled(false);
        backButton.setBorder(JBUI.Borders.empty(1, 6));
        backButton.setFont(backButton.getFont().deriveFont(11f));
        backButton.setForeground(JBColor.namedColor("Link.activeForeground", JBColor.GRAY));
        backButton.setMargin(JBUI.insets(0, 6, 0, 6));
        backButton.setToolTipText(MpSqlVisualizerBundle.message("panel.explain.structured.backToConsole.tip"));
        if (onBackToConsole == null) {
            backButton.setVisible(false);
        } else {
            backButton.addActionListener(e -> onBackToConsole.run());
        }

        // 索引建议条（只提示不执行；空清单不占位——与 MCP formatHints 的零追加同口径）
        String hintsText = "";
        if (!hints.isEmpty()) {
            StringBuilder hintsHtml = new StringBuilder("<html>");
            for (int i = 0; i < hints.size(); i++) {
                ExplainIndexAdvisor.Hint hint = hints.get(i);
                if (i > 0) hintsHtml.append("<br>");
                hintsHtml.append("[").append(hint.rule()).append("] <b>")
                        .append(escapeHtml(hint.target())).append("</b>: ")
                        .append(escapeHtml(hint.advice()));
            }
            hintsHtml.append("</html>");
            hintsText = hintsHtml.toString();
        }
        hintsLabel = new JBLabel(hintsText);
        hintsLabel.setForeground(JBColor.namedColor("WarningForeground", JBColor.ORANGE));
        hintsLabel.setBorder(JBUI.Borders.empty(1, 4));
        hintsLabel.setVisible(!hints.isEmpty());

        JBPanel<JBPanel<?>> north = new JBPanel<JBPanel<?>>(new BorderLayout());
        north.setOpaque(false);
        north.setBorder(JBUI.Borders.empty());
        north.add(headerLabel, BorderLayout.NORTH);
        north.add(hintsLabel, BorderLayout.CENTER);
        north.add(backButton, BorderLayout.EAST);
        add(north, BorderLayout.NORTH);

        // 中央内容：按格式分派（结构化档只有 TABLE/JSON 会到达——ExplainPanelChannel 判定已挡）
        TableModel table = null;
        TreeModel tree = null;
        JComponent content;
        if (format == ExplainOutputFormat.TABLE) {
            Object[][] data = new Object[raw.rows().size()][];
            for (int i = 0; i < raw.rows().size(); i++) {
                data[i] = raw.rows().get(i).toArray();
            }
            DefaultTableModel model = new DefaultTableModel(data, raw.headers().toArray()) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
            table = model;
            JBTable jbTable = new JBTable(model);
            jbTable.setAutoResizeMode(JBTable.AUTO_RESIZE_ALL_COLUMNS);
            jbTable.getTableHeader().setReorderingAllowed(false);
            content = jbTable;
        } else if (format == ExplainOutputFormat.JSON) {
            List<ExplainJsonPlanTree.Node> roots = ExplainJsonPlanTree.build(raw.rows());
            if (roots == null) roots = List.of();
            DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("EXPLAIN");
            for (ExplainJsonPlanTree.Node r : roots) rootNode.add(toSwingNode(r));
            tree = new DefaultTreeModel(rootNode);
            JTree jTree = new JTree(tree);
            jTree.setRootVisible(false);
            jTree.setShowsRootHandles(true);
            content = jTree;
        } else {
            content = new JBLabel(MpSqlVisualizerBundle.message("panel.explain.structured.unsupportedFormat"));
        }
        JBScrollPane scroll = new JBScrollPane(content);
        scroll.setBorder(JBUI.Borders.empty());
        add(scroll, BorderLayout.CENTER);

        // 驱动 notices 尾部透出（T-NoticeChannel 同源数据；空清单零追加）
        if (!raw.notices().isEmpty()) {
            StringBuilder noticesHtml = new StringBuilder("<html>");
            for (int i = 0; i < raw.notices().size(); i++) {
                if (i > 0) noticesHtml.append("<br>");
                noticesHtml.append(escapeHtml(raw.notices().get(i)));
            }
            noticesHtml.append("</html>");
            JBLabel noticesLabel = new JBLabel(noticesHtml.toString());
            noticesLabel.setForeground(JBColor.GRAY);
            noticesLabel.setBorder(JBUI.Borders.empty(1, 4));
            add(noticesLabel, BorderLayout.SOUTH);
        }

        tableModel = table;
        treeModel = tree;
    }

    private DefaultMutableTreeNode toSwingNode(ExplainJsonPlanTree.Node node) {
        DefaultMutableTreeNode swing = new DefaultMutableTreeNode(node.label());
        for (ExplainJsonPlanTree.Node child : node.children()) {
            swing.add(toSwingNode(child));
        }
        return swing;
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------- 测试探针（internal，同 SqlResultPanel.currentErrorForTest 惯例） ----------

    @Nullable
    public TableModel tableModelForTest() {
        return tableModel;
    }

    @Nullable
    public TreeModel treeModelForTest() {
        return treeModel;
    }

    @Nullable
    public String hintsTextForTest() {
        return hintsLabel.isVisible() ? hintsLabel.getText() : null;
    }

    @NotNull
    public JComponent hintsComponentForTest() {
        return hintsLabel;
    }

    @NotNull
    public JButton backButtonForTest() {
        return backButton;
    }

    @NotNull
    public String headerTextForTest() {
        return headerLabel.getText();
    }
}
