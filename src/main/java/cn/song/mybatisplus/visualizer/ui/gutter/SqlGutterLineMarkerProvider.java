package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import kotlin.Unit;

import java.awt.event.MouseEvent;
import javax.swing.Icon;

/**
 * 代码左侧预览 SQL 图标（M10-02，F-04/05/07 新增 gutter 入口）。
 *
 * <p>在 Wrapper 调用链锚点行显示预览图标，点击弹出 SQL 预览（复用 {@link SqlPreviewService} 链路）。
 *
 * <p><b>性能红线（M10 开发计划 §2.4，硬性约束）</b>：
 * <ul>
 *   <li>快速路径 {@link #getLineMarkerInfo} 逐元素回调，只做 {@link WrapperAnchorDetector} 的 O(1) 纯语法判定，
 *       零 resolve、零遍历、零转换；负例立即返回 null；</li>
 *   <li>tooltip 返回静态文案，不计算 SQL 内容；</li>
 *   <li>点击（EDT 回调）只捕获锚点元素，转换一律经 {@link SqlPreviewService#previewAsync} 后台执行；</li>
 *   <li>{@code PluginSettings.state.showGutterIcon} 可一键关闭（性能/视觉兜底）。</li>
 * </ul>
 *
 * <p>注册见 plugin.xml {@code <codeInsight.lineMarkerProvider language="JAVA" .../>}。
 */
public class SqlGutterLineMarkerProvider implements LineMarkerProvider {

    @Override
    @Nullable
    public LineMarkerInfo<PsiElement> getLineMarkerInfo(@NotNull PsiElement element) {
        PsiElement anchor = WrapperAnchorDetector.INSTANCE.anchorElement(element);
        if (anchor == null) return null;
        if (!gutterEnabled()) return null;
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
     * 点击处理：在 EDT 捕获锚点元素，转换走后台线程 + ReadAction（EDT 禁止 PSI 慢操作），
     * 结果按显示方式配置（F-16）展示，与 ConvertToSqlAction 完全同链路。
     *
     * <p>防抖（200ms 全局窗口）：连续点击同一 / 不同 gutter 图标时，仅首次触发 PSI resolve + 弹窗，
     * 窗口内后续点击静默丢弃，避免多后台线程并发执行同一解析 / 多弹窗叠加。
     */
    private static final class SqlGutterNavigationHandler implements GutterIconNavigationHandler<PsiElement> {

        /** 防抖窗口（毫秒）。 */
        private static final long DEBOUNCE_MS = 200L;

        /** 最近一次成功导航的时间戳（volatile 保证多线程可见性）。 */
        private volatile long lastNavigateTime = 0L;

        static final SqlGutterNavigationHandler INSTANCE = new SqlGutterNavigationHandler();

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            long now = System.currentTimeMillis();
            // 防抖：200ms 内重复点击丢弃
            if (now - lastNavigateTime < DEBOUNCE_MS) return;
            lastNavigateTime = now;

            Project project = elt.getProject();
            SqlPreviewService.previewAsync(project, () -> project.getService(SqlPreviewService.class).preview(elt),
                    result -> { SqlPreviewService.showResult(project, result, null); return kotlin.Unit.INSTANCE; });
        }
    }

    private static final Icon SQL_PREVIEW_ICON =
            IconLoader.getIcon("/icons/sqlGutter.svg", SqlGutterLineMarkerProvider.class);
}
