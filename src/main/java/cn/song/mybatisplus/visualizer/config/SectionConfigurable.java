package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.options.Configurable;
import java.util.Set;
import javax.swing.JComponent;

/**
 * 设置子页面基类（左侧树导航的叶节点）。
 *
 * 每个实例创建一个只包含对应分区的 {@link PluginSettingsPanel}，
 * 委托 isModified/apply/reset 给面板。
 */
public abstract class SectionConfigurable implements Configurable {

    private final Set<String> sections;
    private final String displayNameKey;
    private PluginSettingsPanel panel = null;

    protected SectionConfigurable(Set<String> sections, String displayNameKey) {
        this.sections = sections;
        this.displayNameKey = displayNameKey;
    }

    @Override
    public String getDisplayName() {
        return MpSqlVisualizerBundle.message(displayNameKey);
    }

    @Override
    public JComponent createComponent() {
        if (panel == null) {
            panel = new PluginSettingsPanel(PluginSettings.getInstance(), sections);
        }
        return panel.getRoot();
    }

    @Override
    public boolean isModified() {
        return panel != null && panel.isModified();
    }

    @Override
    public void apply() {
        if (panel != null && panel.apply()) {
            panel.rebuild();
        }
    }

    @Override
    public void reset() {
        if (panel != null) panel.reset();
    }

    @Override
    public void disposeUIResources() {
        // 清理面板资源
    }

    /** internal 测试访问器 */
    PluginSettingsPanel panelForTest() {
        return panel;
    }
}
