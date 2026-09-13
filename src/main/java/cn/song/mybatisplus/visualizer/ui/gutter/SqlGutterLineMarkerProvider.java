package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.log.LogSqlEntry;
import cn.song.mybatisplus.visualizer.log.SqlLogManager;
import cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetector;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiElement;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.List;
import javax.swing.Icon;
import javax.swing.SwingUtilities;

/**
 * 代码左侧预览 SQL 图标（M10-02，F-04/05/07 新增 gutter 入口）。
 *
 * <p>在 Wrapper 调用链锚点行显示预览图标。
 *
 * <p><b>交互约定（M38.2 调整为更贴合调试直觉）</b>：
 * <ul>
 *  <li>左键（单击）→ 直接预览 SQL（一键路径最短，对齐原行为）；</li>
 *  <li>右键 → 弹出菜单：预览 SQL / 最近执行记录。</li>
 * </ul>
 *
 * <p><b>性能红线（M10 开发计划 §2.4，硬性约束）</b>：
 * <ul>
 *   <li>快速路径 {@link #getLineMarkerInfo} 逐元素回调，只做 {@link WrapperAnchorDetector} 的 O(1) 纯语法判定，
 *       零 resolve、零遍历、零转换；负例立即返回 null；</li>
 *   <li>tooltip 返回静态文案，不计算 SQL 内容；</li>
 *   <li>转换一律经 {@link SqlPreviewService#previewAsync} 后台执行（EDT 禁止 PSI 慢操作）；</li>
 *   <li>{@code PluginSettings.state.showGutterIcon} 可一键关闭（性能/视觉兜底）。</li>
 * </ul>
 */
public class SqlGutterLineMarkerProvider implements LineMarkerProvider {

    @Override
    @Nullable
    public LineMarkerInfo<PsiElement> getLineMarkerInfo(@NotNull PsiElement element) {
        // T-Perf-23：开关判断前置——用户关闭图标后不再对每个 leaf 跑锚点判定
        if (!gutterEnabled()) return null;
        PsiElement anchor = WrapperAnchorDetector.anchorElement(element);
        if (anchor == null) return null;
        // 七参构造器（2020.3+）：末参 accessibleNameProvider 供屏幕阅读器使用
        return new LineMarkerInfo<PsiElement>(
                anchor,
                anchor.getTextRange(),
                SQL_PREVIEW_ICON,
                elt -> MpSqlVisualizerBundle.message("gutter.tooltip"),
                SqlGutterNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.accessible.name")
        );
    }

    /** 读取 gutter 图标开关（测试环境 service 未注册时按默认 true 处理）。 */
    private boolean gutterEnabled() {
        try {
            return PluginSettings.getInstance().getState().getShowGutterIcon();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 点击处理（M38.2 重构：左键直接预览 + 右键弹菜单）。
     *
     * <p>防抖（200ms 全局窗口）：连续点击同一 / 不同 gutter 图标时，仅首次触发，避免多后台线程并发 / 多弹窗叠加。
     *
     * <p>左右键语义：
     * <ul>
     *  <li>左键 → 直接预览 SQL（一键路径最短）；</li>
     *  <li>右键 → 弹出两选项菜单（预览 SQL / 最近执行记录）。</li>
     * </ul>
     *
     * <p>NPE 修复：M37.1 方案在 BaseListPopupStep.onChosen 的 finalChoice=false（悬停预览）分支也执行动作并返回
     * FINAL_CHOICE，导致 ListPopupImpl 抛 NPE("FINAL_CHOICE must not be null")。现仅在 finalChoice=true 时执行。
     */
    private static final class SqlGutterNavigationHandler implements GutterIconNavigationHandler<PsiElement> {

        /** 防抖窗口（毫秒）。 */
        private static final long DEBOUNCE_MS = 200L;

        /** 最近一次成功导航的时间戳（volatile 保证多线程可见性）。 */
        private volatile long lastNavigateTime = 0L;

        static final SqlGutterNavigationHandler INSTANCE = new SqlGutterNavigationHandler();

        /** 菜单项。 */
        private enum GutterAction {
            PREVIEW("gutter.popup.preview"),
            RECENT("gutter.popup.recent");

            final String labelKey;

            GutterAction(String labelKey) {
                this.labelKey = labelKey;
            }
        }

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            long now = System.currentTimeMillis();
            // 防抖：200ms 内重复点击丢弃
            if (now - lastNavigateTime < DEBOUNCE_MS) return;
            lastNavigateTime = now;

            Project project = elt.getProject();

            // 右键（或 popupTrigger）→ 弹出两选项菜单。
            if (e != null && (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger())) {
                showMenuPopup(project, elt, e);
                return;
            }

            // 左键 → 直接预览 SQL（一键路径最短）。
            previewSource(project, elt);
        }

        /** 右键菜单：预览 SQL / 最近执行记录。 */
        private void showMenuPopup(Project project, PsiElement elt, MouseEvent e) {
            List<GutterAction> actions = Arrays.asList(GutterAction.PREVIEW, GutterAction.RECENT);
            com.intellij.openapi.ui.popup.util.BaseListPopupStep<GutterAction> step =
                    new com.intellij.openapi.ui.popup.util.BaseListPopupStep<GutterAction>(null, actions) {
                        @Override
                        public String getTextFor(GutterAction value) {
                            return MpSqlVisualizerBundle.message(value.labelKey);
                        }

                        @Override
                        public com.intellij.openapi.ui.popup.PopupStep<?> onChosen(
                                GutterAction selectedValue, boolean finalChoice) {
                            // 仅 finalChoice=true 时执行；finalChoice=false 是悬停预览，不响应（避免 M37.1 的 NPE）。
                            if (!finalChoice) return null;
                            if (selectedValue == GutterAction.RECENT) {
                                showRecentEntriesPopup(project, elt);
                            } else {
                                previewSource(project, elt);
                            }
                            return com.intellij.openapi.ui.popup.PopupStep.FINAL_CHOICE;
                        }
                    };
            com.intellij.openapi.ui.popup.JBPopup popup =
                    com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createListPopup(step);
            popup.show(new com.intellij.ui.awt.RelativePoint(e));
        }

        /** 左键原行为：锚点转 SQL 并弹预览（后台转换，F-16 展示配置）。 */
        private void previewSource(Project project, PsiElement elt) {
            SqlPreviewService.previewAsync(project,
                    () -> project.getService(SqlPreviewService.class).preview(elt),
                    result -> {
                        SqlPreviewService.showResult(project, result);
                        return Unit.INSTANCE;
                    });
        }

        /** 反查该锚点最近的日志执行记录并弹二级列表（匹配在后台：PSI 读包 ReadAction）。 */
        private void showRecentEntriesPopup(Project project, PsiElement elt) {
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
                String[] anchorHolder = new String[1];
                int[] lineHolder = new int[1];
                try {
                    com.intellij.openapi.application.ReadAction.compute(() -> {
                        if (elt.getContainingFile() == null || elt.getContainingFile().getVirtualFile() == null) {
                            anchorHolder[0] = null;
                            lineHolder[0] = 0;
                            return null;
                        }
                        com.intellij.openapi.vfs.VirtualFile file = elt.getContainingFile().getVirtualFile();
                        String rel = relativeToProject(project, file.getPath());
                        com.intellij.openapi.editor.Document doc =
                                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file);
                        int line = doc != null
                                ? doc.getLineNumber(elt.getTextRange().getStartOffset()) + 1 : 0;
                        anchorHolder[0] = rel;
                        lineHolder[0] = line;
                        return null;
                    });
                } catch (Throwable t) {
                    anchorHolder[0] = null;
                    lineHolder[0] = 0;
                }
                String anchorPath = anchorHolder[0];
                int anchorLine = lineHolder[0];
                SqlLogManager manager = project.getService(SqlLogManager.class);
                java.util.List<LogSqlEntry> matched = cn.song.mybatisplus.visualizer.log.EntrySourceMatcher.match(
                        manager.getEntries().snapshot(), anchorPath, anchorLine);
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    if (matched.isEmpty()) {
                        com.intellij.openapi.ui.Messages.showInfoMessage(
                                project,
                                MpSqlVisualizerBundle.message("gutter.popup.recent.empty"),
                                MpSqlVisualizerBundle.message("gutter.popup.recent"));
                        return;
                    }
                    com.intellij.openapi.ui.popup.util.BaseListPopupStep<LogSqlEntry> entryStep =
                            new com.intellij.openapi.ui.popup.util.BaseListPopupStep<LogSqlEntry>(
                                    MpSqlVisualizerBundle.message("gutter.popup.recent"), matched) {
                                @Override
                                public String getTextFor(LogSqlEntry value) {
                                    return value.summary();
                                }

                                @Override
                                public com.intellij.openapi.ui.popup.PopupStep<?> onChosen(
                                        LogSqlEntry selectedValue, boolean finalChoice) {
                                    // 仅 finalChoice=true 时执行；避免悬停预览分支误触发 NPE。
                                    if (!finalChoice) return null;
                                    // 20260907-1500：open 会同步 show SqlPreviewDialog，须挂 doFinalStep
                                    //（popup 选中路径内同步弹 dialog 触发平台焦点告警，与生成测试资产同根因）。
                                    if (selectedValue != null) {
                                        return doFinalStep(() ->
                                                cn.song.mybatisplus.visualizer.log.LogEntryPreviewOpener.open(project, selectedValue));
                                    }
                                    return com.intellij.openapi.ui.popup.PopupStep.FINAL_CHOICE;
                                }
                            };
                    com.intellij.openapi.ui.popup.JBPopup popup =
                            com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createListPopup(entryStep);
                    popup.showInFocusCenter();
                });
            });
        }

        /** 绝对路径 → 相对项目根路径（项目外文件返回 null）。 */
        private static String relativeToProject(Project project, String absolutePath) {
            String base = project.getBasePath();
            if (base == null) return null;
            String normAbs = absolutePath.replace('\\', '/');
            String normBase = base.replace('\\', '/');
            while (normBase.endsWith("/")) {
                normBase = normBase.substring(0, normBase.length() - 1);
            }
            String prefix = normBase + "/";
            return normAbs.startsWith(prefix) ? normAbs.substring(prefix.length()) : null;
        }
    }

    private static final Icon SQL_PREVIEW_ICON =
            IconLoader.getIcon("/icons/sqlGutter.svg", SqlGutterLineMarkerProvider.class);
}
