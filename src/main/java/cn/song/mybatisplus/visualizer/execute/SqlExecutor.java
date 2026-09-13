package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
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

    private static void showInfo(@NotNull Project project, @NotNull String message) {
        try {
            Messages.showInfoMessage(project, message, MpSqlVisualizerBundle.message("execute.title"));
        } catch (Throwable ignored) {
            // 测试/headless 环境不抛异常
        }
    }
}
