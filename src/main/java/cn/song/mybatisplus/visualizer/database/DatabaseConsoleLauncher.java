package cn.song.mybatisplus.visualizer.database;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;

/**
 * 数据库执行器（M17 隔离实现，M19 去弹框直建临时文件）。
 *
 * <p><b>隔离约定</b>：本类经 {@code cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport}
 * <b>反射</b>调用——仅当 Database 插件已加载（Ultimate/DataGrip）时才被 JVM 解析，
 * Community 下永不加载。M19 起已无 {@code com.intellij.database} 直接引用（纯平台 API），
 * 反射调用结构保持不变。
 *
 * <p>流程（M19，用户实测数据源弹框无内容——{@code ChooseByNamePopup} 空搜索词且未开启
 * {@code setShowListForEmptyPattern} 时列表被清空隐藏、元素计算不执行；按用户要求移除选择弹框，
 * 改由 SQL 编辑器<b>原生数据源选择器</b>承载）：
 * <ol>
 *   <li>临时文件：系统临时目录 {@code mpsql-exec-*.sql}，写入当前 SQL；</li>
 *   <li>打开编辑器：Ultimate + Database 插件的 SQL 文件编辑器右上角自带数据源选择器，
 *       用户<b>自行选择数据源</b>后 Ctrl+Enter 执行（IDE 原生行为）。</li>
 * </ol>
 */
public final class DatabaseConsoleLauncher {

    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(DatabaseConsoleLauncher.class);

    /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
    private static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

    /** Kotlin object 单例等价（保留 INSTANCE 以支持反射按对象表达式访问）。 */
    public static final DatabaseConsoleLauncher INSTANCE = new DatabaseConsoleLauncher();

    private DatabaseConsoleLauncher() {
    }

    /**
     * 执行入口（经反射调用）：创建临时 .sql 文件并打开（数据源由用户在编辑器右上角选择）。
     * 任何一步失败 → 错误提示（不抛异常到调用方）。
     *
     * <p>T-M23.6：成功提示提供「关闭 SQL 面板」按钮——{@code onClosePanel} 非空时点击触发
     * （经反射由 ExecuteSqlSupport 传入，闭包内回调预览弹窗 dispose）。
     */
    public static void execute(@NotNull Project project, @NotNull String sql) {
        execute(project, sql, null);
    }

    /** 带可选「关闭 SQL 面板」回调的执行入口（默认参数 {@code onClosePanel = null} 的显式重载）。 */
    public static void execute(@NotNull Project project, @NotNull String sql, @Nullable Runnable onClosePanel) {
        try {
            VirtualFile vf = createTempFile(sql);
            FileEditorManager.getInstance(project).openFile(vf, true);
            showInfo(project, MpSqlVisualizerBundle.message("execute.done"), onClosePanel);
        } catch (Throwable error) {
            // T-Perf-01：临时文件/打开编辑器失败改 LOG.warn 静默降级，不再弹模态框（低频异常路径，
            // 用户可从日志定位；原生 Database 编辑器自身的错误仍由平台提示）
            LOG.warn("[M17] launcher execute FAILED: " + error.getMessage(), error);
        }
    }

    /**
     * 创建临时 .sql 文件（系统临时目录）并写入 SQL，返回其虚拟文件。
     * public 替代原 internal（迁移期 internal→public，供测试引用；测试沙盒无 database 插件，仅编译期可引用）。
     */
    @NotNull
    public static VirtualFile createTempFile(@NotNull String sql) throws IOException {
        File ioFile = createTempFile();
        writeSqlToFile(ioFile, sql);
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile);
        if (vf == null) {
            throw new IllegalStateException("临时文件创建失败: " + ioFile.getAbsolutePath());
        }
        return vf;
    }

    /**
     * 创建空的临时 .sql 文件（系统临时目录，测试缝）：纯平台 API，不依赖 Database 插件。
     * 文件名格式 {@code mpsql-exec-*.sql}。
     *
     * @return 创建的临时文件
     */
    @NotNull
    public static File createTempFile() throws IOException {
        return FileUtil.createTempFile("mpsql-exec-", ".sql", true);
    }

    /**
     * 将 SQL 文本写入指定文件（测试缝）：纯平台 I/O，不依赖 Database 插件。
     *
     * @param file 目标文件
     * @param sql  SQL 文本
     */
    public static void writeSqlToFile(@NotNull File file, @NotNull String sql) throws IOException {
        FileUtil.writeToFile(file, sql);
    }

    /**
     * T-M23.6：成功提示带「关闭 SQL 面板」动作 → 触发 {@code onClosePanel}（预览弹窗直接关闭）；
     * 失败提示维持无动作（用户需留在面板修改 SQL）。
     * T-UiNative T-UN-02：原双按钮模态改轻通知 + expiring 动作（非模态、点动作即过期的平台标准形态）；
     * 测试模式跳过 + runCatching 兜底（headless 无通知系统依赖，原 Messages 亦被 runCatching 吞掉）。
     */
    private static void showInfo(@NotNull Project project, @NotNull String message, @Nullable Runnable onClosePanel) {
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        try {
            Notification notification = new Notification(
                    NOTIFICATION_GROUP_ID,
                    MpSqlVisualizerBundle.message("execute.title"),
                    message,
                    NotificationType.INFORMATION
            );
            if (onClosePanel != null) {
                notification.addAction(NotificationAction.createSimpleExpiring(
                        MpSqlVisualizerBundle.message("dialog.closeSqlPanel"),
                        () -> onClosePanel.run()
                ));
            }
            notification.notify(project);
        } catch (Throwable ignored) {
            // headless 无通知系统依赖，与原 runCatching 等价
        }
    }
}
