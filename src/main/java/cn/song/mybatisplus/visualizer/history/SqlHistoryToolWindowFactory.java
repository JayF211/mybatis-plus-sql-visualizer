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

    @SuppressWarnings("UnnecessaryFullyQualifiedName")
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        String title = MpSqlVisualizerBundle.message("history.toolwindow.title");
        toolWindow.setStripeTitle(title);

        SqlHistoryPanel panel = new SqlHistoryPanel(project);
        var content = toolWindow.getContentManager().getFactory().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
