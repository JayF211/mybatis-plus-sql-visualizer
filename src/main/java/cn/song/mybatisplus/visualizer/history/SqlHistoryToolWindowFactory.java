package cn.song.mybatisplus.visualizer.history;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import org.jetbrains.annotations.NotNull;

/**
 * SQL 历史记录工具窗口工厂（F-7）。
 *
 * <p>改写为 Java 以消除 Kotlin 编译器为 ToolWindowFactory 接口全部默认方法生成的 synthetic bridge
 * （getAnchor / getIcon / isApplicable 等），Java 编译器只为源码中显式声明的方法生成 override，
 * 避免 Plugin Verifier Internal API 违规（沿 [cn.song.mybatisplus.visualizer.log.SqlLogToolWindowFactory] 同模式）。
 */
public class SqlHistoryToolWindowFactory implements ToolWindowFactory {

    private static final String TITLE_KEY = "history.toolwindow.title";

    @SuppressWarnings("UnnecessaryFullyQualifiedName")
    @Override
    public void init(@NotNull ToolWindow toolWindow) {
        // F-41：侧边栏按钮 tooltip 显示正确名称（非内部 ID）
        // 根因：ActivateToolWindowAction.updatePresentation() 读取 toolWindow.getStripeTitleProvider()，
        // 空值回退到 tool window ID。工厂的 createToolWindowContent 在 init 后才执行，
        // 导致首次 hover 时 provider 未设置，tooltip 显示 ID。
        // 修复：在 init() 中立即设置 title，早于 createToolWindowContent。
        toolWindow.setStripeTitle(MpSqlVisualizerBundle.message(TITLE_KEY));
    }

    @SuppressWarnings("UnnecessaryFullyQualifiedName")
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        String title = MpSqlVisualizerBundle.message(TITLE_KEY);
        toolWindow.setStripeTitle(title);

        SqlHistoryPanel panel = new SqlHistoryPanel(project);
        var content = toolWindow.getContentManager().getFactory().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
