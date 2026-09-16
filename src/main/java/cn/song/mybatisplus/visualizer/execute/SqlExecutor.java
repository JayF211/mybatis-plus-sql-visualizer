package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.util.function.Consumer;

/**
 * SQL 执行引擎（M36，反射隔离）：弹出原生数据源选择 → Database 控制台内嵌执行。
 *
 * M36.5 执行链拆分：
 *  - prepareConsole：准备控制台（选数据源 → 建 session → attachConsole → 拼合组件回调），不执行。
 *  - executePrepared：在已准备的控制台上执行；未就绪返回 false（调用方回退全链）。
 *  - execute：准备 + 执行合一（原语义，等价原生 Ctrl+Enter）。
 *
 * 反射隔离约定：本类仅当 Database 插件已加载时被调用，Community 下永不加载。
 */
public final class SqlExecutor {

    /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
    private static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

    /** 准备 + 执行合一（M36 原语义）：选数据源 → 建控制台 → 直接执行。 */
    public static void execute(
            @NotNull Project project,
            @NotNull String sql,
            @Nullable Object dataSource,
            boolean isQuery,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError) {
        Consumer<Boolean> callback = success -> {
            if (!success) {
                // Community 或失败时提示
                if (!ExecuteSqlSupport.isAvailable()) {
                    showInfo(project, MpSqlVisualizerBundle.message("execute.noDatabase"));
                }
            }
        };
        DataSourceSelector.selectAndExecute(project, sql, onConsole, onEditor, onError, callback);
    }

    /** M36.5：准备控制台（不执行）。Community 下静默失败（callback(false)）。 */
    public static void prepareConsole(
            @NotNull Project project,
            @NotNull String sql,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback) {
        if (!ExecuteSqlSupport.isAvailable()) {
            // 社区版：静默回退（不弹提示——自研编辑器已是回退态）
            if (callback != null) callback.accept(false);
            return;
        }
        DataSourceSelector.prepareConsole(project, sql, onConsole, onEditor, onError, callback);
    }

    /** M36.5：在已准备的控制台上执行。 */
    public static boolean executePrepared(
            @NotNull Project project,
            @Nullable Consumer<JComponent> onResultsReady,
            @Nullable Consumer<String> onError) {
        if (!ExecuteSqlSupport.isAvailable()) return false;
        return DataSourceSelector.executePrepared(project, onResultsReady, onError);
    }

    /** 控制台是否已就绪（prepareConsole 成功后 true）。 */
    public static boolean isPrepared() {
        return ExecuteSqlSupport.isAvailable() && DataSourceSelector.isPrepared();
    }

    /** 释放已准备控制台引用（面板清空/关闭时调用）。 */
    public static void clearPrepared() {
        DataSourceSelector.clearPrepared();
    }

    /** T-Perf-16：面板 dispose 释放全部单例滞留引用（prepared/shared/pendingOnConsole）。 */
    public static void releaseForPanel() {
        DataSourceSelector.releaseForPanel();
    }

    /**
     * T-UiNative T-UN-02：Community 降级提示改轻通知（原模态弹窗——纯告知无需打断）。
     * 测试模式跳过 + runCatching 兜底（headless 无通知系统依赖，原 Messages 亦被 runCatching 吞掉）。
     */
    private static void showInfo(@NotNull Project project, @NotNull String message) {
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        try {
            new Notification(
                    NOTIFICATION_GROUP_ID,
                    MpSqlVisualizerBundle.message("execute.title"),
                    message,
                    NotificationType.INFORMATION
            ).notify(project);
        } catch (Throwable ignored) {
            // headless 无通知系统依赖，与原 runCatching 等价
        }
    }
}
