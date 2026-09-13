package cn.song.mybatisplus.visualizer.ui.highlight;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.util.Key;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 自动折叠公共应用器（T-AutoFold F0）：六条 SQL 预览编辑器支链的唯一收口。
 *
 * <p>按 {@link SqlAutoFoldPlanner} 的规划对目标编辑器<strong>全量重建本方折叠区域</strong>（先摘自己上一轮清单、
 * 再按当前文本添加）——每次程序性写入后调用即覆盖平台可能的重算抹除（防御策略，见计划 §2.3）。
 *
 * <p>区域清单挂 Editor UserData（{@link #KEY_LAST_FOLD_REGIONS}）：跨类共享、随编辑器释放自然消失；
 * 清除时<strong>只摘清单内的</strong>，绝不 {@link com.intellij.openapi.editor.FoldingModel#getAllFoldRegions()}
 * 全清——原生控制台/平台可能挂有 ConsoleFolding 等他人区域，越权摘除即事故。
 *
 * <p>开关关闭/任何平台异常 → 清除本方区域后静默返回（写入管线零折叠操作，行为与关闭前逐字节
 * 一致；折叠是锦上添花，不因平台异常影响内容展示）。全部调用点已在 EDT，
 * {@link com.intellij.openapi.editor.FoldingModel#runBatchFoldingOperation} 为 UI 层批量刷新，
 * 无 read/write 锁问题。
 *
 * <p>外观最小接线：开启态下若 {@link com.intellij.openapi.editor.EditorSettings#isFoldingOutlineShown()}
 * 为 false 仅补设该一项（双击弹窗紧凑外观、原生控制台需要；关闭态不动任何外观设置）。
 */
public final class SqlAutoFolding {

    /** Kotlin object 单例等价。 */
    public static final SqlAutoFolding INSTANCE = new SqlAutoFolding();

    private SqlAutoFolding() {
    }

    /** 本方折叠区域清单（Editor UserData；批操作外更新，批操作内只读）。 */
    private static final Key<FoldRegion[]> KEY_LAST_FOLD_REGIONS =
            Key.create("MP_SQL_AUTO_FOLD_REGIONS");

    private static final Logger LOG = Logger.getInstance(SqlAutoFolding.class);

    /** 按当前设置对 editor 重建折叠区域；text = 当前全文。关/异常 → 清除本方区域后静默返回。 */
    public static void applyTo(Editor editor, String text) {
        if (editor.isDisposed()) return;
        PluginSettings.State state = null;
        try {
            state = PluginSettings.getInstance().getState();
        } catch (Throwable t) {
            state = null;
        }
        boolean enabled = (state != null) ? state.getAutoFoldEnabled() : false;
        if (!enabled) {
            clearOwnRegions(editor);
            return;
        }
        int threshold = (state != null)
                ? state.getAutoFoldFieldThreshold() : PluginSettings.DEFAULT_AUTO_FOLD_FIELD_THRESHOLD;
        try {
            if (!editor.getSettings().isFoldingOutlineShown()) {
                editor.getSettings().setFoldingOutlineShown(true);
            }
        } catch (Throwable ignored) {
        }
        String template;
        try {
            template = MpSqlVisualizerBundle.message("preview.fold.placeholder");
        } catch (Throwable t) {
            template = "… {0} columns";
        }
        List<SqlAutoFoldPlanner.FoldSpec> specs;
        try {
            specs = SqlAutoFoldPlanner.plan(text, threshold, template);
        } catch (Throwable t) {
            LOG.warn("[T-AutoFold] plan failed, skip folding: " + t.getMessage());
            specs = List.of();
        }
        final List<SqlAutoFoldPlanner.FoldSpec> finalSpecs = specs;
        try {
            editor.getFoldingModel().runBatchFoldingOperation(() -> {
                clearOwnRegions(editor);
                List<FoldRegion> added = new ArrayList<>(finalSpecs.size());
                for (SqlAutoFoldPlanner.FoldSpec spec : finalSpecs) {
                    if (spec.getStartOffset() < 0 || spec.getEndOffset() > text.length()
                            || spec.getStartOffset() >= spec.getEndOffset()) continue;
                    // 相交即拒（返回 null）：规划器自保证互斥，此处跳过即可
                    FoldRegion region = editor.getFoldingModel()
                            .addFoldRegion(spec.getStartOffset(), spec.getEndOffset(), spec.getPlaceholder());
                    if (region != null) {
                        region.setExpanded(false);
                        added.add(region);
                    }
                }
                editor.putUserData(KEY_LAST_FOLD_REGIONS, added.toArray(new FoldRegion[0]));
            });
        } catch (Throwable t) {
            LOG.warn("[T-AutoFold] apply failed: " + t.getMessage());
        }
    }

    /** 清除本方上一轮区域（UserData 清单内逐个摘除；他人区域不碰）。 */
    private static void clearOwnRegions(Editor editor) {
        FoldRegion[] old = editor.getUserData(KEY_LAST_FOLD_REGIONS);
        if (old == null) return;
        editor.putUserData(KEY_LAST_FOLD_REGIONS, null);
        try {
            editor.getFoldingModel().runBatchFoldingOperation(() -> {
                for (FoldRegion region : old) {
                    if (region.isValid()) {
                        editor.getFoldingModel().removeFoldRegion(region);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
