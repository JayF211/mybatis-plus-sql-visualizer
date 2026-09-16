package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/**
 * T-ModPick：多模块工程注入前的模块勾选弹框。
 *
 * 检测到多个支持模块时不再全量注入（用户反馈：应让用户选择而非全部生成）——
 * 逐模块列勾选框（模块名 + 基础包 + 旧版待升级标注），默认全选，附全选/全不选；
 * 未勾选任何模块时确定按钮禁用（{@link DialogWrapper#setOKActionEnabled}，243 javap 实证 API）。
 *
 * 仅承载纯展示与选择态：候选清单由调用方（检测阶段产物）预组装，本弹框不触碰 PSI/VFS
 * （家规「UI 只做编排 + 纯数据渲染」，同 EntitySelectionDialog 原则）。
 */
public class InjectionModulePickerDialog extends DialogWrapper {

    /** 一个候选模块行。 */
    public static final class Row {
        private final String key;
        private final String label;
        private final boolean upgradable;

        public Row(String key, String label, boolean upgradable) {
            this.key = key;
            this.label = label;
            this.upgradable = upgradable;
        }

        public String getKey() {
            return key;
        }

        public String getLabel() {
            return label;
        }

        public boolean isUpgradable() {
            return upgradable;
        }
    }

    private static final int DIALOG_WIDTH = 480;
    private static final int ROW_HEIGHT = 28;
    private static final int MAX_HEIGHT = 320;

    private final List<Row> rows;
    private final Map<String, JBCheckBox> checkBoxes = new LinkedHashMap<>();

    public InjectionModulePickerDialog(Project project, List<Row> rows) {
        super(project, true);
        this.rows = rows;
        setTitle(MpSqlVisualizerBundle.message("log.inject.pick.title"));
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        for (Row row : rows) {
            JBCheckBox cb = new JBCheckBox(row.label, true);
            cb.addItemListener(e -> setOKActionEnabled(!selectedKeys().isEmpty()));
            checkBoxes.put(row.key, cb);
            JPanel line = new JPanel(new BorderLayout());
            line.setOpaque(false);
            line.setBorder(JBUI.Borders.empty(2, 4));
            line.add(cb, BorderLayout.WEST);
            if (row.upgradable) {
                JBLabel tag = new JBLabel(MpSqlVisualizerBundle.message("log.inject.pick.upgradeTag"));
                tag.setForeground(UIUtil.getInactiveTextColor());
                tag.setBorder(JBUI.Borders.emptyLeft(8));
                line.add(tag, BorderLayout.EAST);
            }
            list.add(line);
        }
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(
                DIALOG_WIDTH, Math.min(rows.size() * ROW_HEIGHT + 16, MAX_HEIGHT)));
        scroll.setBorder(JBUI.Borders.empty());
        JButton selectAll = new JButton(MpSqlVisualizerBundle.message("log.inject.pick.selectAll"));
        JButton selectNone = new JButton(MpSqlVisualizerBundle.message("log.inject.pick.selectNone"));
        selectAll.addActionListener(e -> setAllSelected(true));
        selectNone.addActionListener(e -> setAllSelected(false));
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        toolbar.setOpaque(false);
        toolbar.add(selectAll);
        toolbar.add(selectNone);
        toolbar.add(Box.createHorizontalGlue());
        JPanel root = new JPanel(new BorderLayout());
        root.add(new JBLabel(MpSqlVisualizerBundle.message("log.inject.pick.message")), BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(toolbar, BorderLayout.SOUTH);
        root.setBorder(JBUI.Borders.empty(4));
        return root;
    }

    private void setAllSelected(boolean selected) {
        for (JBCheckBox cb : checkBoxes.values()) {
            cb.setSelected(selected);
        }
    }

    /** 勾选的模块键集合（测试缝与 doOKAction 共用）。 */
    public Set<String> selectedKeys() {
        Set<String> keys = new HashSet<>();
        for (Map.Entry<String, JBCheckBox> e : checkBoxes.entrySet()) {
            if (e.getValue().isSelected()) {
                keys.add(e.getKey());
            }
        }
        return keys;
    }

    /** 测试缝：不经 show() 直取中心面板（同 ShareDialog.centerPanelForTest 惯例）。 */
    public JComponent centerPanelForTest() {
        return createCenterPanel();
    }
}
