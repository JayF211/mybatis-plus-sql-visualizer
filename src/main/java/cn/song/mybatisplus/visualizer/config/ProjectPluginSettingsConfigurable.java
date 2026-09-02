package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.DialectRegistry;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import org.jetbrains.annotations.NotNull;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * 项目级设置页（O-5 Phase B）。
 *
 * 接入 IDE Settings → 当前项目的 SQL Visualizer（project-level）。
 * 展示「使用项目设置」开关 + 可覆盖项（方言/参数替换/逻辑删除/表名映射等）。
 *
 * 仅当「使用项目设置」开启时可编辑其它项；关闭时回退全局 {@link PluginSettings}。
 */
public class ProjectPluginSettingsConfigurable implements Configurable {

    private final Project project;
    private final ProjectPluginSettings projectSettings;
    private JCheckBox useProjectCheck = null;
    private JComboBox<String> dialectCombo = null;
    private boolean modified = false;

    public ProjectPluginSettingsConfigurable(Project project) {
        this.project = project;
        this.projectSettings = ProjectPluginSettings.getInstance(project);
    }

    @Override
    public String getDisplayName() {
        return MpSqlVisualizerBundle.message("plugin.name");
    }

    @NotNull
    @Override
    public JComponent createComponent() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        // 「使用项目设置」开关
        JCheckBox check = new JCheckBox(MpSqlVisualizerBundle.message("settings.project.useProjectSettings"));
        check.setSelected(projectSettings.getState().getUseProjectSettings());
        check.setToolTipText(MpSqlVisualizerBundle.message("settings.project.useProjectSettings.tip"));
        check.addActionListener(e -> {
            modified = true;
            // 开启/关闭时刷新可用性由 apply 处理
        });
        useProjectCheck = check;
        panel.add(check, gbc);

        gbc.gridy++;
        gbc.insets = new Insets(4, 24, 4, 6); // 缩进
        gbc.fill = GridBagConstraints.NONE; // 不拉伸，保持自然宽度

        // 方言下拉（示例项，后续可扩展更多）
        JPanel dialectPanel = new JPanel(new BorderLayout(8, 0));
        dialectPanel.add(new JBLabel(MpSqlVisualizerBundle.message("settings.dialect")), BorderLayout.WEST);
        @SuppressWarnings("unchecked")
        JComboBox<String> combo = new JComboBox<>(DialectRegistry.INSTANCE.getDialects().stream()
            .map(Dialect::getName)
            .toArray(String[]::new));
        String currentDialect = projectSettings.getState().getDialect();
        combo.setSelectedItem(currentDialect.isBlank()
            ? PluginSettings.getInstance().getState().getDialect()
            : currentDialect);
        combo.setEnabled(projectSettings.getState().getUseProjectSettings());
        combo.addActionListener(e -> modified = true);
        dialectPanel.add(combo, BorderLayout.CENTER);
        dialectCombo = combo;
        panel.add(dialectPanel, gbc);

        // 底部占位
        gbc.gridy++;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(new JPanel(), gbc);

        return panel;
    }

    @Override
    public boolean isModified() {
        return modified;
    }

    @Override
    public void apply() {
        JCheckBox check = useProjectCheck;
        if (check == null) return;
        JComboBox<String> combo = dialectCombo;
        if (combo == null) return;
        projectSettings.getState().setUseProjectSettings(check.isSelected());
        projectSettings.getState().setDialect(combo.getSelectedItem() instanceof String s ? s : "");
        modified = false;
    }

    @Override
    public void reset() {
        if (useProjectCheck != null) {
            useProjectCheck.setSelected(projectSettings.getState().getUseProjectSettings());
        }
        if (dialectCombo != null) {
            String d = projectSettings.getState().getDialect();
            dialectCombo.setSelectedItem(d.isBlank()
                ? PluginSettings.getInstance().getState().getDialect()
                : d);
        }
        modified = false;
    }

    @Override
    public void disposeUIResources() {
        useProjectCheck = null;
        dialectCombo = null;
    }
}
