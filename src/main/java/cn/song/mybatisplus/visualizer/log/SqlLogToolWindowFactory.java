package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import org.jetbrains.annotations.NotNull;

import cn.song.mybatisplus.visualizer.log.SqlLogPanel;

/**
 * MyBatis SQL 日志工具窗口（T-M31-04）：三标签页（SQL 日志列表 / 日志转 SQL / SQL → Java），
 * 全部使用 IDEA 原生组件（JBTabbedPane 主题化标签页 + ActionToolbar 扁平图标工具栏 + tooltip）。
 *
 * <p>改写为 Java 以消除 Kotlin 编译器为 ToolWindowFactory 接口全部默认方法生成的 synthetic bridge
 * （getAnchor / getIcon / manage / isDoNotActivateOnStart / isApplicable），
 * Java 编译器只为源码中显式声明的方法生成 override，避免 Plugin Verifier Internal API 违规。
 */
public class SqlLogToolWindowFactory implements ToolWindowFactory {

    private static final String TITLE_KEY = "log.toolwindow.title";

    @Override
    public void init(@NotNull ToolWindow toolWindow) {
        // F-41：侧边栏按钮 tooltip 显示正确名称（非内部 ID）
        // 根因：ActivateToolWindowAction.updatePresentation() 读取 toolWindow.getStripeTitleProvider()，
        // 空值回退到 tool window ID。工厂的 createToolWindowContent 在 init 后才执行，
        // 导致首次 hover 时 provider 未设置，tooltip 显示 ID。
        // 修复：在 init() 中立即设置 title，早于 createToolWindowContent。
        toolWindow.setStripeTitle(MpSqlVisualizerBundle.message(TITLE_KEY));
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // 窗口标题（stripe 标签）：显示 bundle 文案而非窗口 ID
        String title = MpSqlVisualizerBundle.message(TITLE_KEY);
        toolWindow.setStripeTitle(title);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!toolWindow.isDisposed()) {
                toolWindow.setStripeTitle(title);
            }
        });

        SqlLogManager manager = project.getService(SqlLogManager.class);
        SqlLogPanel panel = new SqlLogPanel(project, manager);
        // content 标题留空（标题栏由 stripe 标题唯一承担，避免重复显示）
        var content = toolWindow.getContentManager().getFactory().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
