package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.IconScaler;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.icons.AllIcons;
import com.intellij.ide.util.gotoByName.ChooseByNamePopup;
import com.intellij.ide.util.gotoByName.ChooseByNamePopupComponent;
import com.intellij.ide.util.gotoByName.GotoClassModel2;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.BoxLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体类选择弹窗（SQL→Java 反向转换手动指定实体）。
 *
 * 自动推断未命中或多表 JOIN 时，按 SQL 涉及的表逐行列出，每行可从项目实体中指定一个实体。
 */
public class EntitySelectionDialog extends DialogWrapper {

    /** 选中确认回调。 */
    public interface ConfirmCallback {
        void confirm(Map<String, PsiClass> result);
    }

    /** 一行 = SQL 涉及的一张表（主表或 JOIN 副表）。 */
    public static final class TableRow {
        public final String key;
        public final String label;
        public final List<PsiClass> candidates;

        public TableRow(String key, String label, List<PsiClass> candidates) {
            this.key = key;
            this.label = label;
            this.candidates = candidates;
        }

        public String getKey() { return key; }
        public String getLabel() { return label; }
        public List<PsiClass> getCandidates() { return candidates; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TableRow)) return false;
            TableRow that = (TableRow) o;
            return key.equals(that.key) && label.equals(that.label) && candidates.equals(that.candidates);
        }

        @Override
        public int hashCode() {
            return key.hashCode() * 31 + label.hashCode() + candidates.hashCode();
        }

        @Override
        public String toString() {
            return "TableRow{key='" + key + "', label='" + label + "', candidates=" + candidates + '}';
        }
    }

    private final ConfirmCallback onConfirm;
    private final List<TableRow> rows;
    private final Map<String, PsiClass> selections = new HashMap<>();
    private final Map<String, JBLabel> selectionLabels = new HashMap<>();
    private final Project project;

    public EntitySelectionDialog(@NotNull Project project, @NotNull List<TableRow> rows, @NotNull ConfirmCallback onConfirm) {
        super(project, true);
        this.project = project;
        this.rows = rows;
        this.onConfirm = onConfirm;
        setTitle(MpSqlVisualizerBundle.message("dialog.selectEntity.title"));
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        for (TableRow row : rows) {
            panel.add(buildRow(row));
            selections.put(row.key, row.candidates.isEmpty() ? null : row.candidates.get(0));
        }
        int height = Math.min(rows.size() * ROW_HEIGHT + DIALOG_PADDING, DIALOG_MAX_HEIGHT);
        JScrollPane scroll = new JScrollPane(panel);
        scroll.setPreferredSize(new Dimension(DIALOG_WIDTH, height));
        return scroll;
    }

    private JPanel buildRow(TableRow row) {
        JBLabel selectedLabel = new JBLabel(" ");
        selectionLabels.put(row.key, selectedLabel);
        JPanel rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        rowPanel.setOpaque(false);
        JBLabel labelLabel = new JBLabel(row.label);
        labelLabel.setToolTipText(row.label);
        rowPanel.add(labelLabel);
        rowPanel.add(selectedLabel);
        JButton browse = new JButton(MpSqlVisualizerBundle.message("dialog.selectEntity.browse"));
        browse.addActionListener(e -> pickClass(row, selectedLabel));
        rowPanel.add(browse);
        JButton clear = new JButton(CANCEL_ICON);
        clear.setBorderPainted(false);
        clear.setContentAreaFilled(false);
        clear.setFocusPainted(false);
        clear.setMargin(JBUI.insets(0));
        clear.setBorder(JBUI.Borders.empty());
        clear.setIconTextGap(0);
        clear.setPreferredSize(new Dimension(12, 12));
        clear.setMinimumSize(new Dimension(12, 12));
        clear.setMaximumSize(new Dimension(12, 12));
        clear.setToolTipText(MpSqlVisualizerBundle.message("reverse.panel.clearClass"));
        clear.addActionListener(e -> clearSelection(row, selectedLabel));
        rowPanel.add(clear);
        refreshRow(row, selectedLabel);
        return rowPanel;
    }

    /** 弹 Goto Class 选择器（IDE 标准类搜索弹窗）。 */
    private void pickClass(TableRow row, JBLabel selectedLabel) {
        ChooseByNamePopup popup = ChooseByNamePopup.createPopup(project, new GotoClassModel2(project), (PsiElement) null);
        popup.invoke(new ChooseByNamePopupComponent.Callback() {
            @Override
            public void elementChosen(Object element) {
                if (!(element instanceof PsiClass)) return;
                selections.put(row.key, (PsiClass) element);
                refreshRow(row, selectedLabel);
            }

            @Override
            public void onClose() {
            }
        }, ModalityState.current(), false);
    }

    private void clearSelection(TableRow row, JBLabel selectedLabel) {
        selections.put(row.key, null);
        refreshRow(row, selectedLabel);
    }

    /** 行显示刷新：有选中 → 类名（tooltip 全限定名）；无 → 灰字占位提示。 */
    private void refreshRow(TableRow row, JBLabel selectedLabel) {
        PsiClass selected = selections.get(row.key);
        if (selected != null) {
            Pair<String, String> info = resolveQualifiedNameForTest(selected);
            selectedLabel.setText(info != null ? info.getFirst() : "?");
            selectedLabel.setToolTipText(info != null ? info.getSecond() : null);
            selectedLabel.setForeground(UIUtil.getLabelForeground());
        } else {
            selectedLabel.setText(MpSqlVisualizerBundle.message("dialog.selectEntity.none"));
            selectedLabel.setToolTipText(null);
            selectedLabel.setForeground(UIUtil.getInactiveTextColor());
        }
    }

    /**
     * 解析 PsiClass 的显示名与全限定名（测试缝）。
     */
    @Nullable
    // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
    @SuppressWarnings("deprecation")
    public Pair<String, String> resolveQualifiedNameForTest(PsiClass psiClass) {
        // Kotlin 无受检异常；Java 侧用 RuntimeException 作为 E，保持签名不向调用方传播受检异常
        return ReadAction.compute(new ThrowableComputable<Pair<String, String>, RuntimeException>() {
            @Override
            public Pair<String, String> compute() {
                String name = psiClass.getName();
                if (name == null) return null;
                return new Pair<>(name, psiClass.getQualifiedName());
            }
        });
    }

    @Override
    protected void doOKAction() {
        Map<String, PsiClass> result = new HashMap<>(selections);
        super.doOKAction();
        onConfirm.confirm(result);
    }

    /** 测试访问器：某行当前选中实体（无 → null）。 */
    public PsiClass selectedForTest(String key) {
        return selections.get(key);
    }

    /** 测试访问器：清除某行选择。 */
    public void clearSelectionForTest(String key) {
        TableRow row = null;
        for (TableRow r : rows) {
            if (r.key.equals(key)) { row = r; break; }
        }
        if (row == null) return;
        clearSelection(row, selectionLabels.getOrDefault(key, new JBLabel(" ")));
    }

    public static final String MAIN_KEY = "";
    private static final Icon CANCEL_ICON = IconScaler.scale(AllIcons.Actions.Cancel, 12f / 16f);
    private static final int DIALOG_WIDTH = 560;
    private static final int ROW_HEIGHT = 40;
    private static final int DIALOG_PADDING = 20;
    private static final int DIALOG_MAX_HEIGHT = 360;
}
