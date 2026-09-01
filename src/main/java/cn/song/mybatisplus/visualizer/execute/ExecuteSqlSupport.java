package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * 执行 SQL 支持（M17）：SQL 预览面板「执行 SQL」按钮的 core 入口。
 *
 * Community 兼容（M17-R5）：`com.intellij.database`（数据源/控制台）仅 IntelliJ IDEA Ultimate /
 * DataGrip 捆绑——本类与面板零直接引用 database 类；实际实现
 * [cn.song.mybatisplus.visualizer.database.DatabaseConsoleLauncher] 经反射加载：
 * launcher 字节码位于主 jar，但仅当 database 插件已加载时才被 JVM 解析（Community 下永不触碰）。
 *
 * 流程（Ultimate，M19）：创建临时 .sql 文件（含当前 SQL）→ 打开编辑器（Database 插件
 * SQL 编辑器右上角数据源选择器由用户自行选择数据源，Ctrl+Enter 执行——IDE 原生行为）。
 */
public final class ExecuteSqlSupport {

    /** Database 插件标记类（插件加载时必在 classpath；探测用，版本无关）。 */
    private static final String DATABASE_MARKER_CLASS = "com.intellij.database.dataSource.LocalDataSourceManager";

    /** 隔离实现类（仅 database 插件存在时反射加载）。 */
    private static final String LAUNCHER_CLASS = "cn.song.mybatisplus.visualizer.database.DatabaseConsoleLauncher";

    public static final ExecuteSqlSupport INSTANCE = new ExecuteSqlSupport();
    private ExecuteSqlSupport() {}

    /**
     * database 插件是否已加载（Ultimate/DataGrip）。
     */
    public static boolean isAvailable() {
        try {
            Class.forName(DATABASE_MARKER_CLASS);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 执行入口：不可用 → 提示升级 Ultimate；可用 → 反射调用 launcher。
     * 所有 UI 提示均 runCatching 兜底（测试/headless 环境不抛异常）。
     *
     * @param project      当前项目（面板无项目时不调用本方法）
     * @param sql          当前编辑器 SQL（含参数回填与手动编辑）
     * @param onClosePanel 成功提示「关闭 SQL 面板」按钮回调（预览弹窗关闭）；null = 成功提示维持单 OK
     */
    public static void execute(@NotNull Project project, @NotNull String sql) {
        execute(project, sql, null);
    }

    public static void execute(@NotNull Project project, @NotNull String sql, @Nullable Runnable onClosePanel) {
        if (!isAvailable()) {
            showInfo(project, MpSqlVisualizerBundle.message("execute.noDatabase"));
            return;
        }
        try {
            // 第三参 Runnable 透传「关闭 SQL 面板」回调（反射方法签名与 launcher 一致）
            Runnable closeRunnable = onClosePanel;
            Method method = Class.forName(LAUNCHER_CLASS)
                    .getMethod("execute", Project.class, String.class, Runnable.class);
            method.invoke(null, project, sql, closeRunnable);
        } catch (Throwable error) {
            showError(project, MpSqlVisualizerBundle.message("execute.failed", error.getMessage() != null ? error.getMessage() : error.toString()));
        }
    }

    private static void showInfo(@NotNull Project project, @NotNull String message) {
        try {
            Messages.showInfoMessage(project, message, MpSqlVisualizerBundle.message("execute.title"));
        } catch (Throwable ignored) {
            // 测试/headless 环境不抛异常
        }
    }

    private static void showError(@NotNull Project project, @NotNull String message) {
        try {
            Messages.showErrorDialog(project, message, MpSqlVisualizerBundle.message("execute.title"));
        } catch (Throwable ignored) {
            // 测试/headless 环境不抛异常
        }
    }
}
