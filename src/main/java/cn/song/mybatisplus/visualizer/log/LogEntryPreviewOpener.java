package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 反查条目打开预览弹窗（M37.1）——与 SqlLogPanel.openPreview 同链路的独立实现
 * （gutter 无面板实例时可用；SqlLogPanel.openPreview 保持不动避免回归）。
 */
public final class LogEntryPreviewOpener {

    public static final LogEntryPreviewOpener INSTANCE = new LogEntryPreviewOpener();

    private LogEntryPreviewOpener() {
    }

    public static void open(@NotNull Project project, @NotNull LogSqlEntry entry) {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        RecognizedLogPreview recognized = LogEntryPreviewRecognizerKt.toRecognizedPreview(entry);
        PreviewResult result;
        if (!recognized.recognized()) {
            // 回退：restoredSql + 未解析警告（参数缺失/数量不匹配/纯静态 SQL）
            String base = SqlLogPanelKt.withTerminator(entry.restoredSql());
            String sql = state.getLogFormatSql() ? SqlFormatter.format(base) : base;
            result = new PreviewResult(
                    sql,
                    entry.restoredSql(),
                    java.util.List.of(),
                    null,
                    null,
                    null,
                    entry.restoredSql().indexOf('?') >= 0
                            ? MpSqlVisualizerBundle.message("log.preview.unresolvedWarning")
                            : null);
        } else {
            // 识别成功：参数回填形态（param1..N + 日志值预填 + 类型引号提示）
            String base = SqlLogPanelKt.withTerminator(recognized.sql());
            String sql = state.getLogFormatSql() ? SqlFormatter.format(base) : base;
            result = new PreviewResult(
                    sql,
                    SqlLogPanelKt.withTerminator(recognized.compactSql()),
                    recognized.unresolvedParams(),
                    null,
                    null,
                    null,
                    null,
                    recognized.paramHints(),
                    recognized.initialValues());
        }
        // M37.1：携带来源位置——预览面板可感知/回显 SQL 出处
        SqlPreviewService.showResult(project, result, entry.sourceFile());
    }
}
