package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.sql.ParamHint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code LogEntryPreviewRecognizer.kt} 顶层扩展函数（{@code LogSqlEntry.toRecognizedPreview()}）的 Java 承载类。
 */
public final class LogEntryPreviewRecognizerKt {
    private LogEntryPreviewRecognizerKt() {
    }

    /**
     * 识别日志条目为预览弹窗参数形态。
     *
     * 参数命名：日志无参数名，用序号 {@code param1..N}（对齐 MP 生成器 {@code param1} 风格）；
     * 值预填 {@link LogSqlParam#value()}（null → 空串，回填显示 {@code ?}——与还原 {@code NULL} 不一致的边界见开发计划 §7）。
     */
    public static RecognizedLogPreview toRecognizedPreview(LogSqlEntry entry) {
        long placeholderCount = entry.rawSql().chars().filter(c -> c == '?').count();
        boolean recognized = placeholderCount > 0 && placeholderCount == entry.params().size();
        if (!recognized) {
            return new RecognizedLogPreview(
                    entry.rawSql(),
                    entry.rawSql(),
                    List.of(),
                    Map.of(),
                    List.of(),
                    false);
        }
        List<String> sources = new ArrayList<>();
        for (int i = 1; i <= placeholderCount; i++) {
            sources.add("param" + i);
        }
        List<String> values = entry.params().stream().map(p -> p.value() != null ? p.value() : "").toList();
        Map<String, String> initialValues = new HashMap<>();
        for (int i = 0; i < sources.size(); i++) {
            initialValues.put(sources.get(i), values.get(i));
        }
        List<ParamHint> paramHints = entry.params().stream().map(LogSqlRestorerKt::toHint).toList();
        return new RecognizedLogPreview(
                entry.rawSql(),
                entry.rawSql(),
                sources,
                initialValues,
                paramHints,
                true);
    }
}
