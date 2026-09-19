package cn.song.mybatisplus.visualizer.ui.components;

import cn.song.mybatisplus.visualizer.sql.ParameterReplacer;
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 一键复制可执行 SQL（O-4 Phase A）。
 *
 * <p>取当前面板 SQL（含参数回填与手动编辑），将 {@code ?} 替换为带类型处理的字面值
 * （复用 {@link ParameterReplacer#buildExecutableSql}），复制到系统剪贴板并 toast 提示。
 *
 * <p>复制统一走 {@link UiTextClipboard}（平台 CopyPasteManager）：在 headless 测试环境下
 * 剪贴板写入不影响复制逻辑本身。
 */
public final class CopyExecutableSqlAction {

    /** 通知分组 id（plugin.xml 注册）。 */
    private static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

    /**
     * T-InjectionGate T-IG-03：产物含 `${}` raw 段时的头部标记注释。
     *
     * <p>复制不是执行——这条链不弹确认（弹窗克制红线：模态确认只留给执行），改让风险随产物走：
     * 产物粘到别处时读者能看到 raw 段带的是模拟值。SQL 注释行（`--`）不改产物可执行性。
     */
    public static final String DOLLAR_RAW_MARKER = "-- contains ${} raw segments (mock values)";

    private final Project project;

    public CopyExecutableSqlAction(@Nullable Project project) {
        this.project = project;
    }

    /**
     * 执行复制：取面板当前 SQL + 参数值 → 替换占位符 → 剪贴板 → toast。
     *
     * @param panel 预览面板（取 SQL / 参数值 / 回填提示）
     * @return 是否成功复制
     */
    public boolean execute(@NotNull SqlPreviewPanel panel) {
        String raw = panel.editorDocumentText();
        if (raw == null || raw.isBlank()) {
            showToast(MpSqlVisualizerBundle.message("preview.copyExecutableSql.nothing"));
            return false;
        }
        // 复制时剥离 SQL 注释（与 copySql 对齐）
        String sql = SqlCommentStripper.strip(raw);
        List<String> sources = panel.unresolvedParams();
        Map<String, String> paramValues = panel.paramValuesSnapshot();

        String executableSql;
        if (sources.isEmpty()) {
            executableSql = sql;
        } else {
            // hints 默认值 emptyList()：显式传入（Java 调用 Kotlin 带默认参数方法须全参）
            executableSql = ParameterReplacer.buildExecutableSql(sql, sources, paramValues, List.of());
        }

        // T-InjectionGate T-IG-03：含 `${}` raw 段（解析期事实，见 PreviewResult.hasDollarRawSegments）
        // 时产物头部加标记注释；无 raw 段产物逐字节不变
        String product = panel.hasDollarRawSegments() ? DOLLAR_RAW_MARKER + "\n" + executableSql : executableSql;

        copyToClipboard(product);

        int replacedCount = countReplaced(sql, executableSql);
        String message;
        if (sources.isEmpty()) {
            message = MpSqlVisualizerBundle.message("preview.copyExecutableSql.noParams");
        } else {
            message = MpSqlVisualizerBundle.message("preview.copyExecutableSql.done", replacedCount);
        }
        showToast(message);
        return true;
    }

    /** 统计被替换的 {@code ?} 数量（原始 SQL 中 {@code ?} 数 - 结果中 {@code ?} 数）。 */
    private int countReplaced(@NotNull String original, @NotNull String result) {
        int originalCount = (int) original.chars().filter(c -> c == '?').count();
        int resultCount = (int) result.chars().filter(c -> c == '?').count();
        return Math.max(originalCount - resultCount, 0);
    }

    /** 复制文本到系统剪贴板（统一走 UiTextClipboard 收口）。 */
    private void copyToClipboard(@NotNull String text) {
        UiTextClipboard.copy(text);
    }

    /** Toast 通知（仅在非测试模式下显示，测试模式跳过避免通知系统依赖）。 */
    private void showToast(@NotNull String message) {
        if (ApplicationManager.getApplication().isUnitTestMode()) {
            return;
        }
        if (project == null) {
            return;
        }
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(message, NotificationType.INFORMATION)
                    .notify(project);
        } catch (Throwable ignored) {
            // 与原 runCatching 等价
        }
    }
}
