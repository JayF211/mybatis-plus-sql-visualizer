package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.log.SqlLogManager;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import java.util.Set;
import javax.swing.JComponent;

/**
 * 设置子页面基类（左侧树导航的叶节点）。
 *
 * 每个实例创建一个只包含对应分区的 {@link PluginSettingsPanel}，
 * 委托 isModified/apply/reset 给面板。
 *
 * 自动监听「关→开」边沿联动（20260907-1617 自 PluginSettingsConfigurable 迁入——
 * 该类未注册进 plugin.xml 是死类，用户实际勾选走本基类的 apply 链；踩坑第九轮修复回归）：
 * apply 前读旧值，panel.apply() 后比对——「关→开」时清 suppressed + 调
 * {@link SqlLogManager#onAutoStartSettingEnabled} 按模式分流（进程级监听可用=被动不预 armed，
 * 降级才进 armed；20260907-2045 第四轮：08:33 会话反馈"开启设置未 Run 就自动监听"实证被动语义）。
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
        if (panel == null) return;
        // 边沿检测须在 panel.apply() 写回前读旧值
        boolean autoCaptureBefore = PluginSettings.getInstance().getState().getLogAutoCaptureOnStart();
        boolean languageChanged = panel.apply();
        if (languageChanged) panel.rebuild();
        PluginSettings settings = PluginSettings.getInstance();
        LOG.info("SectionConfigurable.apply: logAutoCaptureOnStart=" + settings.getState().getLogAutoCaptureOnStart()
                + ", suppressed=" + settings.getState().getLogAutoCaptureSuppressed());
        if (!autoCaptureBefore && settings.getState().getLogAutoCaptureOnStart()) {
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                SqlLogManager manager = project.getService(SqlLogManager.class);
                try {
                    manager.onAutoStartSettingEnabled();
                } catch (Exception e) {
                    LOG.warn("apply: 自动启动分流跳过（项目关闭/服务不可用）", e);
                }
            }
        }
    }

    @Override
    public void reset() {
        if (panel != null) panel.reset();
    }

    @Override
    public void disposeUIResources() {
        if (panel != null) {
            // T-SettingsTruth P2：断开面板 messageBus 连接（增强捕获状态行订阅），防 IDE 退出 leak 告警
            panel.disposeResources();
        }
    }

    /** internal 测试访问器 */
    PluginSettingsPanel panelForTest() {
        return panel;
    }

    private static final Logger LOG = Logger.getInstance(SectionConfigurable.class);
}
