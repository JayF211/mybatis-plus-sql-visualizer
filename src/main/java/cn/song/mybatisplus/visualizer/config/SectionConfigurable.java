package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.log.SqlLogManager;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import java.util.List;
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
            linkageAutoStartForProjects(List.of(ProjectManager.getInstance().getOpenProjects()));
        }
    }

    /**
     * 「关→开」边沿联动：对每个打开项目调 {@link SqlLogManager#onAutoStartSettingEnabled} 按模式分流。
     *
     * <p><b>T-ST-02</b>：{@code getService} 与 {@code onAutoStartSettingEnabled()} 在同一个降级面内——正在关闭 /
     * 测试框架 temporarily-disposed 窗口内的项目，{@code getService} 会抛 {@code AlreadyDisposedException}
     * （项目服务构造期订阅 {@code project.messageBus}，{@code MessageBusImpl.checkOwnerDisposed} 判
     * {@code owner.isDisposed} 为真，容器服务创建链再归一成 "Container is already disposed"）。
     * 原先只包住 {@code onAutoStartSettingEnabled()}，该异常会直接穿出 {@code apply()} 打断设置写回。
     *
     * <p><b>⚠️ 为什么必须在 {@code getService} 之前先判 {@link Project#isDisposed()}（T-ST-02b2，勿「优化」回去）</b>
     *
     * <p>{@code runCatching} 只吞掉这一次的结果，<b>阻止不了中毒</b>——它仍然发起了那次注定失败的创建。
     * 平台服务容器<b>一次创建失败即把该服务在该项目实例上永久毒化</b>（独立复核者字节码级结论）：
     * <ul>
     *   <li>{@code LazyInstanceHolder.initialize$1} 的异常表是 {@code catch java.lang.Throwable}（<b>无
     *       {@code CancellationException} 过滤</b>），catch 后无条件 {@code new CannotInitialize(instanceClass, throwable)}
     *       + {@code access$complete}；</li>
     *   <li>此后 {@code LazyInstanceHolder.tryGetInstance()} 只要见 {@code _state instanceof CannotInitialize}
     *       就 {@code throw getInitializationError()}——<b>每次 {@code getService} 都重抛同一个缓存异常</b>
     *       （每次再被归一成新的 "Container is already disposed"）。</li>
     * </ul>
     *
     * <p>旁证：本仓 {@code AlreadyDisposedDiagnostics} 采到的 8 条 WARN 全部打印
     * {@code project.isDisposed=false}，而消息是 "Container is already disposed"——抛出时刻
     * {@code owner.isDisposed} 必为真、catch 时刻同一对象为假，只有「缓存重抛」能自洽。
     *
     * <p>而 {@link Project#isDisposed()} 在 temporarily-disposed 窗口内为真（平台 {@code ProjectImpl.isDisposed()}
     * = {@code super.isDisposed() || isTemporarilyDisposed}），故「窗口内不取服务」既避开中毒，
     * 又不会误伤正常项目。<b>平台侧的毒化机制与 temporarily-disposed 窗口本身未触及</b>——
     * 本条只保证本调用点不再发起那次失败创建，<b>不宣称根治</b>。
     *
     * <p><b>测试缝（internal）</b>：项目列表由调用方给定，便于以项目桩驱动「项目已 dispose」分支
     * （{@code ProjectManager.getInstance().openProjects} 是全局单例，测试无法注入）。
     */
    public void linkageAutoStartForProjects(List<Project> projects) {
        for (Project project : projects) {
            if (project.isDisposed()) {
                // 沿用既有 warn 文案（未取服务，无异常可挂）
                LOG.warn("apply: 自动启动分流跳过（项目关闭/服务不可用）");
                continue;
            }
            try {
                project.getService(SqlLogManager.class).onAutoStartSettingEnabled();
            } catch (Throwable e) {
                LOG.warn("apply: 自动启动分流跳过（项目关闭/服务不可用）", e);
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
