package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * M56 T-M56-08：增强捕获注入确认对话框（首次启用弹一次，确认记忆持久化）。
 *
 * 话术四件套（§4.5）：目的 / 采集范围 / 数据留本机不回传 + 回落路径 /
 * JDK21+ 目标控制台会出现动态挂载告警属预期（JEP 451，仅告警未禁止；未来硬禁止走
 * 回落路径并提示 {@code -XX:+EnableDynamicAgentLoading}，插件不自动改用户配置）。
 */
public class EnhancedCaptureConfirmDialog extends DialogWrapper {

    /** 模态确认；true = 用户同意注入。 */
    public static boolean confirm(Project parent) {
        return new EnhancedCaptureConfirmDialog(parent).showAndGet();
    }

    public EnhancedCaptureConfirmDialog(Project parent) {
        super(parent, true);
        setTitle(MpSqlVisualizerBundle.message("settings.enhancedCapture.confirm.title"));
        init();
        // Cancel 文案须在 init() 之后显式覆盖（createActions 默认文案先落地）
        setCancelButtonText(MpSqlVisualizerBundle.message("settings.enhancedCapture.confirm.cancel"));
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(JBUI.size(520, 240));
        JBLabel label = new JBLabel();
        label.setText(
            "<html><body style='width:480px'>"
                + MpSqlVisualizerBundle.message("settings.enhancedCapture.confirm.body")
                + "</body></html>");
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction(), getCancelAction()};
    }

    @Override
    protected Action getOKAction() {
        Action action = super.getOKAction();
        action.putValue(Action.NAME, MpSqlVisualizerBundle.message("settings.enhancedCapture.confirm.ok"));
        return action;
    }
}
