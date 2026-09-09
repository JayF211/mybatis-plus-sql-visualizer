package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import org.jetbrains.annotations.NotNull;

/**
 * ToolWindow 可见性变化监听器（T-M51-02）：独立 Java 类，覆写单参版 stateChanged。
 * 单参版是公开 API（非 experimental/internal），三平台（243/253/262）可用。
 */
public class ToolWindowVisibilityListener implements ToolWindowManagerListener {
    private static final Logger LOG = Logger.getInstance(ToolWindowVisibilityListener.class);

    private final Project project;
    private final ToolWindow targetTw;
    private final SqlLogManager manager;
    private boolean wasVisible;

    public ToolWindowVisibilityListener(Project project, ToolWindow targetTw, SqlLogManager manager) {
        this.project = project;
        this.targetTw = targetTw;
        this.manager = manager;
        this.wasVisible = targetTw.isVisible();
    }

    @Override
    public void stateChanged(@NotNull ToolWindowManager toolWindowManager) {
        boolean visible = targetTw.isVisible();
        if (wasVisible && !visible) {
            PluginSettings settings = PluginSettings.getInstance();
            if (settings.getState().getLogStopOnToolWindowClose() && manager.getCapturing()) {
                manager.stopCapture();
                LOG.info("ToolWindow 隐藏：已停止捕捉（project=" + project.getName() + "）");
            }
        }
        wasVisible = visible;
    }
}
