package cn.song.mybatisplus.visualizer.actions;

import cn.song.mybatisplus.visualizer.generator.CrudStatement;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Pair;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;

/**
 * CRUD 生成结果 → 现有预览窗口的展示流（T-CrudSql-04，展示层零新弹窗）。
 *
 * <p>候选选择用 <b>JBPopup 浮层</b>（T-CrudSql 样式反馈二次定案：模态对话框带 OK/Cancel 与
 * 底部留白不符合 IDEA 原生风格；Recent Files/搜索式浮层才是原生态——无按钮、Enter/双击
 * 选择、ESC 关闭、打字过滤定位），选中后走 {@link SqlPreviewService#showResult} 打开既有
 * SqlPreviewDialog——渲染/格式化/执行/复制按钮全部复用，INSERT/UPDATE 的列勾选重建由
 * PreviewResult.batchModel 承载。候选 0/1 条时不弹选择（0 条由调用方兜底提示）。
 */
public final class CrudSqlPreviewFlow {

    /** Kotlin object 单例等价。 */
    public static final CrudSqlPreviewFlow INSTANCE = new CrudSqlPreviewFlow();

    private CrudSqlPreviewFlow() {
    }

    /** 候选项：表名（标签前缀）+ 语句。 */
    public static final class Candidate {
        private final String tableName;
        private final CrudStatement statement;

        public Candidate(String tableName, CrudStatement statement) {
            this.tableName = tableName;
            this.statement = statement;
        }

        public String getTableName() {
            return tableName;
        }

        public CrudStatement getStatement() {
            return statement;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Candidate)) {
                return false;
            }
            Candidate that = (Candidate) o;
            return tableName.equals(that.tableName) && statement.equals(that.statement);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(tableName, statement);
        }

        @Override
        public String toString() {
            return "Candidate(tableName=" + tableName + ", statement=" + statement + ")";
        }
    }

    /**
     * 展示候选语句。多表多语句合并进同一列表（标签带表名前缀）。
     *
     * @param tables 表名 → 该表生成的语句列表（空列表的表已由调用方过滤）
     */
    public static void showCandidates(Project project, List<Pair<String, List<CrudStatement>>> tables) {
        List<Candidate> candidates = new ArrayList<>();
        for (Pair<String, List<CrudStatement>> table : tables) {
            for (CrudStatement statement : table.getSecond()) {
                candidates.add(new Candidate(table.getFirst(), statement));
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        if (candidates.size() == 1) {
            // 调用方（action invokeLater）已在 EDT 非模态上下文——直接开预览
            SqlPreviewService.showResult(project, candidates.get(0).getStatement().getResult(), null);
            return;
        }
        JBPopupFactory.getInstance()
                .createPopupChooserBuilder(candidates)
                .setTitle(MpSqlVisualizerBundle.message("dialog.selectSql.title"))
                .setRenderer(new CandidateCellRenderer())
                .setNamerForFiltering(CrudSqlPreviewFlow::summary)
                .setItemChosenCallback((Candidate selected) ->
                        // 预览打开延后到选择浮层关闭之后（T-CrudSql 真机修复：不在选择 UI 的
                        // 事件栈内同步开 modeless 预览弹窗，262 实测会被吞且零异常零日志）
                        ApplicationManager.getApplication().invokeLater(() ->
                                SqlPreviewService.showResult(project, selected.getStatement().getResult(), null)))
                .createPopup()
                .showInFocusCenter();
    }

    /** 兜底提示（update 已拦截可见性，竞态/解析异常路径才会到达）。 */
    public static void showNoneFound(Project project) {
        com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                MpSqlVisualizerBundle.message("crudSql.noneFound.message"),
                MpSqlVisualizerBundle.message("crudSql.noneFound.title"));
    }

    /** 候选摘要：{@code 表名 · 语句种类}（无主键降级追加标注）。纯函数可单测。 */
    public static String summary(Candidate candidate) {
        String kind = MpSqlVisualizerBundle.message(candidate.getStatement().getKind().getLabelKey());
        String base = candidate.getTableName() + " · " + kind;
        if (candidate.getStatement().isMissingPrimaryKey()) {
            return base + MpSqlVisualizerBundle.message("crudSql.candidate.noPrimaryKey");
        }
        return base;
    }

    /** 候选第二行：SQL 首行预览（渲染时截断 80 字符）。 */
    public static String lineText(Candidate candidate) {
        return candidate.getStatement().getResult().getSql().lines().findFirst().orElse("");
    }

    /**
     * 候选行渲染器：主题色双行（加粗摘要 + 次要色详情，选中态随平台列表配色），
     * 与 {@link SqlSelectionDialog} 行渲染同款范式；详情行禁 HTML 解析（代码行可能以 {@code <} 开头）。
     */
    public static final class CandidateCellRenderer extends JPanel implements ListCellRenderer<Candidate> {
        private final JLabel summaryLabel = new JLabel();
        private final JLabel detailLabel = new JLabel();

        public CandidateCellRenderer() {
            super(new BorderLayout());
            setOpaque(true);
            setBorder(JBUI.Borders.empty(6, 12));
            summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD));
            detailLabel.putClientProperty("html.disable", true);
            detailLabel.setForeground(UIUtil.getContextHelpForeground());
            add(summaryLabel, BorderLayout.NORTH);
            add(detailLabel, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends Candidate> list,
                Candidate value,
                int index,
                boolean selected,
                boolean focused) {
            summaryLabel.setText(summary(value));
            String line = lineText(value);
            detailLabel.setText(line.length() > SqlSelectionDialog.MAX_LINE_LENGTH
                    ? line.substring(0, SqlSelectionDialog.MAX_LINE_LENGTH) + "…"
                    : line);
            setBackground(selected ? UIUtil.getListSelectionBackground(true) : UIUtil.getListBackground());
            summaryLabel.setForeground(UIUtil.getListForeground());
            detailLabel.setForeground(selected ? UIUtil.getListForeground() : UIUtil.getContextHelpForeground());
            return this;
        }
    }
}
