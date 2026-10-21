// 自 master ui/annotate/PreviewSchemaHighlighter.kt 顶层函数移植
package cn.song.mybatisplus.visualizer.ui.annotate;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import org.jetbrains.annotations.NotNull;

/** Kotlin 顶层函数承载（{@code PreviewSchemaHighlighter.kt} → Java facade）。 */
public final class PreviewSchemaHighlighterKt {

    private PreviewSchemaHighlighterKt() {
    }

    /** 与 ExternalAnnotator 通道共用同一文案（前缀 + 表/列/建议）。 */
    @NotNull
    public static String previewUnknownColumnMessage(@NotNull PreviewUnknownColumnPlanner.Mark mark) {
        String body = mark.suggestion() != null
                ? MpSqlVisualizerBundle.message("schema.check.unknownColumnSuggestion",
                        mark.column(), mark.table(), mark.suggestion())
                : MpSqlVisualizerBundle.message("schema.check.unknownColumn", mark.column(), mark.table());
        return MpSqlVisualizerBundle.message("schema.check.notification.title") + ": " + body;
    }
}
