package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.log.LogSqlEntry;
import cn.song.mybatisplus.visualizer.log.SqlLogManager;
import cn.song.mybatisplus.visualizer.psi.PsiSnapshot;
import cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetector;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerInfo.LineMarkerGutterIconRenderer;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
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
        // T-Perf-23：开关判断前置——用户关闭图标后不再对每个 leaf 跑锚点判定。
        // F36（T-PerfSweep16）：单次取 state，总闸与组显隐共用（原 gutterEnabled + enabledFor
        // 双路径每到达元素 3 次 app 级查找收敛为 1 次）
        PluginSettings.State gutterState;
        try {
            gutterState = PluginSettings.getInstance().getState();
        } catch (Throwable e) { // Kotlin runCatching.getOrNull() 语义
            gutterState = null;
        }
        if (gutterState != null && !gutterState.getShowGutterIcon()) return null;
        if (!GutterGroups.enabledFor(getClass(), gutterState)) return null; // R2 分组显隐
        PsiElement anchor = WrapperAnchorDetector.anchorElement(element);
        if (anchor == null) return null;
        // 七参构造器（2020.3+）：末参 accessibleNameProvider 供屏幕阅读器使用。
        // T-MergeFollowup-02：覆写 createGutterRenderer——243 平台 LineMarkerGutterIconRenderer 不覆写
        // getRightButtonClickAction（基类字节码恒 aconst_null），GutterIconNavigationHandler 只接左键 click，
        // M37.1 的「navigate 里判右键」永远收不到右键事件（真机右键无反应根因）——右键经平台正解 API 接线。
        return new LineMarkerInfo<PsiElement>(
                anchor,
                anchor.getTextRange(),
                SQL_PREVIEW_ICON,
                elt -> MpSqlVisualizerBundle.message("gutter.tooltip"),
                SqlGutterNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.accessible.name")
        ) {
            @Override
            public GutterIconRenderer createGutterRenderer() {
                return new LineMarkerGutterIconRenderer<PsiElement>(this) {
                    @Override
                    public AnAction getRightButtonClickAction() {
                        return new SqlGutterRightClickAction(anchor);
                    }
                };
            }
        };
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
            if (!SqlGutterLineMarkerProviderKt.shouldNavigate(lastNavigateTime, now)) return;
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

        /**
         * 右键入口（T-MergeFollowup-02）：SqlGutterRightClickAction 经平台 getRightButtonClickAction
         * 分发到此处——与 navigate 共享 200ms 防抖窗口（同 lastNavigateTime），菜单逻辑复用。
         */
        void onRightClick(Project project, PsiElement elt, MouseEvent e) {
            long now = System.currentTimeMillis();
            if (!SqlGutterLineMarkerProviderKt.shouldNavigate(lastNavigateTime, now)) return;
            lastNavigateTime = now;
            showMenuPopup(project, elt, e);
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

        /**
         * 反查该锚点最近的日志执行记录并弹浏览对话框（T-RecentExecDialog O-118 换代：原二级
         * ListPopup 只有一行摘要，改弹 {@link cn.song.mybatisplus.visualizer.log.RecentExecutionsDialog}
         * 复用日志列表渲染器 + 只读高亮编辑器）。D86=A：空命中也弹（对话框状态行显示空态文案，
         * 原轻通知分支随换代删除）。
         *
         * <p>链路不变：EDT 侧捕获锚点快照（文件 + textRange + 文档 modificationStamp）；
         * 后台读取前由 {@code validatedRecentAnchor} 校验，失效即按「无记录」降级（不跳到错位置）；
         * 匹配留池化线程（{@link cn.song.mybatisplus.visualizer.log.EntrySourceMatcher#match} 纯函数）。
         */
        private void showRecentEntriesPopup(Project project, PsiElement elt) {
            // T-PCP-02：EDT 侧捕获锚点快照；后台读取前校验（见上）
            PsiSnapshot snapshot = PsiSnapshot.capture(elt);
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
                kotlin.Pair<String, Integer> anchorOrNull;
                try {
                    anchorOrNull = com.intellij.openapi.application.ReadAction.compute(
                            () -> SqlGutterLineMarkerProviderKt.validatedRecentAnchor(
                                    project.getBasePath(), snapshot));
                } catch (Throwable t) {
                    anchorOrNull = null;
                }
                final kotlin.Pair<String, Integer> anchor = anchorOrNull;
                SqlLogManager manager = project.getService(SqlLogManager.class);
                java.util.List<LogSqlEntry> matched = cn.song.mybatisplus.visualizer.log.EntrySourceMatcher.match(
                        manager.getEntries().snapshot(),
                        anchor != null ? anchor.getFirst() : null,
                        anchor != null ? anchor.getSecond() : 0);
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    // 锚点展示片段「文件名:行号」（标题参数；anchor=null = 快照失效，标题不带锚点）
                    String anchorLabel = null;
                    if (anchor != null) {
                        String path = anchor.getFirst();
                        int line = anchor.getSecond();
                        String name = path.substring(path.lastIndexOf('/') + 1);
                        anchorLabel = line > 0 ? name + ":" + line : name;
                    }
                    // 非模态对话框（构造+show 须 EDT——本 invokeLater 保证）
                    new cn.song.mybatisplus.visualizer.log.RecentExecutionsDialog(project, matched, anchorLabel).show();
                });
            });
        }

        // 绝对路径 → 项目根相对路径：T-PCP-02 起收口到 SqlGutterLineMarkerProviderKt.relativeToProjectPath（快照校验链共用）
    }

    /**
     * 右键动作（T-MergeFollowup-02）：挂在自定义 renderer 的 getRightButtonClickAction() 上——
     * 243 平台对 gutter 图标右键只认该 API（LineMarkerGutterIconRenderer 未覆写、基类恒 null，
     * 平台事件分发层直接分流，GutterIconNavigationHandler 只接左键 click）。
     * 防抖与菜单逻辑收口 {@link SqlGutterNavigationHandler#onRightClick}。
     */
    private static final class SqlGutterRightClickAction extends AnAction {

        private final PsiElement elt;

        SqlGutterRightClickAction(PsiElement elt) {
            this.elt = elt;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (!(e.getInputEvent() instanceof MouseEvent)) return;
            MouseEvent mouseEvent = (MouseEvent) e.getInputEvent();
            SqlGutterNavigationHandler.INSTANCE.onRightClick(elt.getProject(), elt, mouseEvent);
        }
    }

    private static final Icon SQL_PREVIEW_ICON =
            IconLoader.getIcon("/icons/sqlGutter.svg", SqlGutterLineMarkerProvider.class);

    // 「最近执行记录」轻提示通知组已随 T-RecentExecDialog 弹框化退役（空态改对话框状态行）。
}
