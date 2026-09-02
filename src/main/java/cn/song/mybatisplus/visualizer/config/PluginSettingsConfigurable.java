package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.log.SqlLogManager;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.swing.JComponent;

/**
 * 插件设置页（T-M5-02，F-16）。
 *
 * 接入 IDE Settings → Tools → SQL Visualizer（application-level，无项目依赖）。
 * 表单读写委托 {@link PluginSettingsPanel}，保存即生效（PersistentStateComponent 自动持久化）。
 *
 * M14.7：保存后按变更类型联动——
 *  - 界面语言变化 → 重建表单（设置页自身文案即时切换）；
 *  - gutter 图标开关变化 → 遍历所有打开项目的编辑器触发重分析（line marker 立即刷新，无需重开文件）。
 *
 * 启动自动监听（logAutoCaptureOnStart）仅由 {@code cn.song.mybatisplus.visualizer.log.LogStartupCapture}
 * （postStartupActivity）在项目打开时触发——设置页勾选后不立即监听，项目关闭后自动停止（Disposable）。
 */
public class PluginSettingsConfigurable implements Configurable {

    private static final Logger LOG = Logger.getInstance(PluginSettingsConfigurable.class);

    private PluginSettingsPanel panel;

    @Override
    public String getDisplayName() {
        return MpSqlVisualizerBundle.message("plugin.name");
    }

    @Override
    public JComponent createComponent() {
        panel = new PluginSettingsPanel(PluginSettings.getInstance());
        return panel.getRoot();
    }

    @Override
    public boolean isModified() {
        return panel.isModified();
    }

    /**
     * 保存配置；按变更类型联动（语言 → 重建表单；gutter 开关 → 全局刷新行标记）。
     * 自动监听（logAutoCaptureOnStart）由 LogStartupCapture（postStartupActivity）在项目打开时进入 armed 态——
     * 但用户在项目已打开后才开启设置时，LogStartupCapture 不会重新触发，
     * 故 apply 时检测「关→开」变更，立即进入 armed 态（受 suppressed 标志抑制）。
     */
    @Override
    public void apply() {
        PluginSettings settings = PluginSettings.getInstance();
        boolean gutterIconBefore = settings.getState().getShowGutterIcon();
        boolean autoCaptureBefore = settings.getState().getLogAutoCaptureOnStart();
        if (panel.apply()) panel.rebuild();
        LOG.info("PluginSettingsConfigurable.apply: logAutoCaptureOnStart="
            + settings.getState().getLogAutoCaptureOnStart()
            + ", suppressed=" + settings.getState().getLogAutoCaptureSuppressed());
        // 自动监听「关→开」→ 清除抑制标志 + 已打开项目立即进入 armed 态
        if (!autoCaptureBefore && settings.getState().getLogAutoCaptureOnStart()) {
            settings.getState().setLogAutoCaptureSuppressed(false);
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                SqlLogManager manager = project.getService(SqlLogManager.class);
                try {
                    manager.startCapture();
                } catch (Throwable t) {
                    LOG.warn("apply: startCapture 跳过（项目关闭/服务不可用）", t);
                }
            }
        }
        // M14.7：gutter 图标开关变化 → 触发已打开编辑器的重分析（line marker 立即生效，无需重开文件）
        if (settings.getState().getShowGutterIcon() != gutterIconBefore) refreshGutterIcons();
    }

    /** 遍历所有打开项目中的编辑器文件，触发 Daemon 重分析（重新计算 line markers）。 */
    @SuppressWarnings("deprecation") // T-M52：restart(PsiFile) 在 253/262 标 deprecated，243 安全；无跨版本非废弃替代
    private void refreshGutterIcons() {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            Set<PsiFile> seen = new LinkedHashSet<>();
            for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
                if (editor.getProject() == project) {
                    PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
                    if (psiFile != null) seen.add(psiFile);
                }
            }
            for (PsiFile psiFile : seen) {
                DaemonCodeAnalyzer.getInstance(project).restart(psiFile);
            }
        }
    }

    @Override
    public void reset() {
        panel.reset();
    }

    /** M23.9：internal 测试访问器（仿 SqlPreviewDialog.panelForTest——createComponent 返回
     * 的是 getRoot() 的 JComponent，测试需经此取回表单面板断言 apply 幂等）。 */
    public PluginSettingsPanel panelForTest() {
        return panel;
    }
}
