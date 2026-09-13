package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.TreeUIHelper;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * 多 SQL 选择弹窗（T-M23.7/8；T-M25-01 泛化支持 XML 语句候选）。
 *
 * <p>选择代码块后右键、选择区内含<strong>多个</strong> SQL 候选时，弹出列表让用户选择要预览哪一个。
 * 泛型 {@code T}：Wrapper 链首（PsiMethodCallExpression）与 XML mapper 语句（XmlTag）均可用。
 *
 * <p>列表每项两行展示——第一行摘要（{@code 行号 + Wrapper 类型/方法名 + 表名}），第二行代码行内容；
 * 选中确定 → {@link SelectCallback} 回调（调用方负责编辑器定位跳转 + 预览 SQL）。
 *
 * <p>T-CrudSql 原生化改造（用户反馈"样式丑"）：弃用 HTML 字符串渲染，改为 {@link RowRenderer}
 * 主题色原生双行渲染（JBList + 加粗摘要 + 次要色详情 + 行内边距，明暗主题自适应）+
 * IDEA 原生速度搜索（弹窗内直接打字过滤定位）；双击候选直接确认。
 *
 * <p>{@code rows} 由调用方在<strong>后台 ReadAction</strong> 中预计算（摘要/行文本含 PSI 解析，EDT 上会被
 * 2025.3+ SlowOperations 守卫禁止）——构造只做纯 UI 渲染，不再触碰 PSI。
 */
public class SqlSelectionDialog<T> extends DialogWrapper {

    /** 选中确定回调。 */
    public interface SelectCallback<T> {
        void select(T value);
    }

    /** 列表行（纯数据，可后台预计算与单测）：加粗摘要行 + 次要色详情行。 */
    public static final class Row {
        private final String summary;
        private final String detail;

        public Row(@NotNull String summary, @NotNull String detail) {
            this.summary = summary;
            this.detail = detail;
        }

        public String getSummary() { return summary; }
        public String getDetail() { return detail; }

        /** 工厂：详情超 {@link SqlSelectionDialog#MAX_LINE_LENGTH} 截断加省略号（纯文本渲染，无需 HTML 转义）。 */
        public static Row of(@NotNull String summary, @NotNull String detail) {
            return new Row(summary,
                    detail.length() > MAX_LINE_LENGTH ? detail.substring(0, MAX_LINE_LENGTH) + "…" : detail);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Row)) return false;
            Row row = (Row) o;
            return java.util.Objects.equals(summary, row.summary) && java.util.Objects.equals(detail, row.detail);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(summary, detail);
        }

        @Override
        public String toString() {
            return "Row[summary=" + summary + ", detail=" + detail + "]";
        }
    }

    private final List<T> candidates;
    private final JBList<Row> list;
    private final SelectCallback<T> onSelect;

    public SqlSelectionDialog(@NotNull Project project,
                              @NotNull List<T> candidates,
                              @NotNull List<Row> rows,
                              @NotNull SelectCallback<T> onSelect) {
        super(project, true);
        this.candidates = candidates;
        this.onSelect = onSelect;
        this.list = new JBList<>(rows);
        this.list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.list.setCellRenderer(new RowRenderer());
        // IDEA 原生速度搜索：弹窗内直接打字按摘要/详情过滤定位（Recent Files 同款体验）
        TreeUIHelper.getInstance().installListSpeedSearch(this.list);
        // 双击候选直接确认（对齐 IDEA 原生弹窗交互），不必移步 OK
        this.list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int idx = SqlSelectionDialog.this.list.locationToIndex(e.getPoint());
                    if (idx >= 0) {
                        SqlSelectionDialog.this.list.setSelectedIndex(idx);
                        doOKAction();
                    }
                }
            }
        });
        setTitle(MpSqlVisualizerBundle.message("dialog.selectSql.title"));
        this.list.setSelectedIndex(0);
        init();
    }

    @NotNull
    @Override
    protected JComponent createCenterPanel() {
        JScrollPane pane = new JScrollPane(list);
        pane.setBorder(JBUI.Borders.empty());
        pane.setPreferredSize(new Dimension(DIALOG_WIDTH, DIALOG_HEIGHT));
        pane.setMinimumSize(new Dimension(DIALOG_MIN_WIDTH, DIALOG_MIN_HEIGHT));
        return pane;
    }

    @Override
    protected void doOKAction() {
        int selected = list.getSelectedIndex();
        if (selected >= 0 && selected < candidates.size()) {
            onSelect.select(candidates.get(selected));
        }
        super.doOKAction();
    }

    /** 测试访问器（断言渲染模型与预计算行一致，仿 panelForTest）。 */
    public JList<Row> listForTest() {
        return list;
    }

    public static final int DIALOG_WIDTH = 560;
    public static final int DIALOG_HEIGHT = 280;
    public static final int DIALOG_MIN_WIDTH = 420;
    public static final int DIALOG_MIN_HEIGHT = 160;

    /** 代码行预览最大长度（字符；超出截断加省略号，防撑爆弹窗宽度）。 */
    public static final int MAX_LINE_LENGTH = 80;

    /**
     * 列表行渲染器：原生主题色双行（选中态随平台列表配色，明暗主题自适应）。
     * 详情行禁用 HTML 解析（"html.disable" client property）——代码行可能以 {@code <} 开头（如 XML 片段）。
     */
    private static final class RowRenderer extends JPanel implements ListCellRenderer<Row> {
        private final JLabel summaryLabel = new JLabel();
        private final JLabel detailLabel = new JLabel();

        private RowRenderer() {
            super(new BorderLayout());
            setOpaque(true);
            setBorder(JBUI.Borders.empty(6, 12));
            summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD));
            // 详情行禁用 HTML 解析（Swing 约定键 "html.disable"，BasicHTML.propertyKey 是 View 缓存键勿用）——
            // 代码行可能以 `<` 开头（如 XML 片段），纯文本渲染
            detailLabel.putClientProperty("html.disable", true);
            detailLabel.setForeground(UIUtil.getContextHelpForeground());
            add(summaryLabel, BorderLayout.NORTH);
            add(detailLabel, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends Row> list,
                Row value,
                int index,
                boolean selected,
                boolean focused) {
            summaryLabel.setText(value.getSummary());
            detailLabel.setText(value.getDetail());
            setBackground(selected ? UIUtil.getListSelectionBackground(true) : UIUtil.getListBackground());
            summaryLabel.setForeground(UIUtil.getListForeground());
            detailLabel.setForeground(selected ? UIUtil.getListForeground() : UIUtil.getContextHelpForeground());
            return this;
        }
    }
}
