package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.ui.AnnotationSqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaToken;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import kotlin.Unit;

import java.awt.event.MouseEvent;
import javax.swing.Icon;

/**
 * MyBatis SQL 注解预览图标（T-M25-03）：`@Select/@Insert/@Update/@Delete` 及 Provider 族注解
 * 所在行显示预览图标，点击弹出动态 SQL 预览（复用 {@link SqlPreviewService#showResult} 链路）。
 *
 * <p><b>性能红线</b>：快速路径只做 O(1) 语法判定——leaf 锚点为注解的 `@` token（{@link JavaTokenType#AT}），
 * 父链是 PsiAnnotation 且 qualifiedName 末段 ∈ SQL 注解集合（纯文本匹配，零 resolve）；
 * tooltip 静态文案；点击转换走 {@link SqlPreviewService#previewAsync} 后台线程。
 *
 * <p>注册见 plugin.xml {@code <codeInsight.lineMarkerProvider language="JAVA" .../>}（与 Wrapper gutter 共存）。
 */
public class AnnotationSqlGutterLineMarkerProvider implements LineMarkerProvider {

    @Override
    @Nullable
    public LineMarkerInfo<PsiElement> getLineMarkerInfo(@NotNull PsiElement element) {
        // F36（T-PerfSweep16）：组显隐门前置——组关时 isSqlAnnotation 重活零触达（原门在重活
        // 判定之后，组关省不掉重活）；单次取 state，总闸与组显隐共用（原双路径每到达元素
        // 3 次 app 级查找收敛为 1 次）
        PluginSettings.State gutterState;
        try {
            gutterState = PluginSettings.getInstance().getState();
        } catch (Throwable e) { // Kotlin runCatching.getOrNull() 语义
            gutterState = null;
        }
        if (gutterState != null && !gutterState.getShowGutterIcon()) return null;
        if (!GutterGroups.enabledFor(getClass(), gutterState)) return null; // R2 分组显隐
        // LineMarker 必须注册在 leaf 元素：注解的 `@` token
        if (!(element instanceof PsiJavaToken)) return null;
        PsiJavaToken token = (PsiJavaToken) element;
        if (token.getTokenType() != JavaTokenType.AT) return null;
        PsiAnnotation annotation = PsiTreeUtil.getParentOfType(token, PsiAnnotation.class, false);
        if (annotation == null) return null;
        if (!AnnotationSqlPreviewService.INSTANCE.isSqlAnnotation(annotation)) return null;
        // 七参构造器（2020.3+）：末参 accessibleNameProvider 供屏幕阅读器使用
        return new LineMarkerInfo<PsiElement>(
                element,
                element.getTextRange(),
                SQL_PREVIEW_ICON,
                elt -> MpSqlVisualizerBundle.message("gutter.annotation.tooltip"),
                AnnotationGutterNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.annotation.accessible.name")
        );
    }

    /** 点击处理：后台解析 + 渲染（EDT 禁止 PSI 慢操作），结果走统一展示入口。 */
    private static final class AnnotationGutterNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        static final AnnotationGutterNavigationHandler INSTANCE = new AnnotationGutterNavigationHandler();

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            Project project = elt.getProject();
            PsiAnnotation annotation = PsiTreeUtil.getParentOfType(elt, PsiAnnotation.class, false);
            if (annotation == null) return;
            SqlPreviewService.previewAsync(project, () -> AnnotationSqlPreviewService.INSTANCE.preview(annotation, project),
                    result -> { SqlPreviewService.showResult(project, result, null); return kotlin.Unit.INSTANCE; });
        }
    }

    private static final Icon SQL_PREVIEW_ICON =
            IconLoader.getIcon("/icons/sqlGutter.svg", AnnotationSqlGutterLineMarkerProvider.class);
}
