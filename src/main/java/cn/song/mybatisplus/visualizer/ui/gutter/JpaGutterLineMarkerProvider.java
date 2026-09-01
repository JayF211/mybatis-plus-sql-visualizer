package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.psi.jpa.DerivedMethodLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.JpqlQueryLocator;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import kotlin.Unit;

import java.awt.event.MouseEvent;
import javax.swing.Icon;

/**
 * JPA Repository 推导查询 gutter 预览图标（F-14 阶段四）：Repository 接口中的推导方法名
 * （如 `findByLastName`）所在行显示预览图标，点击弹出 SQL 预览。
 *
 * <p><b>性能红线</b>：快速路径只做 O(1) 语法判定——leaf 锚点为 PsiMethod 名称 identifier，
 * 检查 containing class 是否为 Repository 接口 + 方法名是否包含 `By` 关键字。
 * 完整解析（PartTree 转换）仅在点击时走 {@link SqlPreviewService#previewAsync} 后台线程。
 */
public class JpaGutterLineMarkerProvider implements LineMarkerProvider {

    private static final Icon JPA_SQL_PREVIEW_ICON =
            IconLoader.getIcon("/icons/sqlGutter.svg", JpaGutterLineMarkerProvider.class);

    @Override
    @Nullable
    public LineMarkerInfo<PsiElement> getLineMarkerInfo(@NotNull PsiElement element) {
        // 【去重关键】LineMarker 框架对 PSI 树的每个叶子元素回调本方法。
        // 必须锚定在方法名 PsiIdentifier 上——仅当 element 是方法的名称 token 时才创建 marker，
        // 避免方法声明内的每个叶子（返回类型、参数名、关键字等）都产生重复图标。
        if (!(element instanceof PsiIdentifier)) return null;
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (method == null) return null;
        // 确认当前 leaf 就是方法名（而非方法体内某个变量名 / 类型名）
        if (method.getNameIdentifier() != element) return null;
        if (!gutterEnabled()) return null;
        if (!DerivedMethodLocator.isDerivedQuery(method)) return null;

        return new LineMarkerInfo<PsiElement>(
                element,
                element.getTextRange(),
                JPA_SQL_PREVIEW_ICON,
                elt -> MpSqlVisualizerBundle.message("gutter.jpa.tooltip"),
                JpaGutterNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.jpa.accessible.name")
        );
    }

    private boolean gutterEnabled() {
        try {
            return PluginSettings.getInstance().getState().getShowGutterIcon();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 导航处理：@Query 方法走 JPQL 路径，纯派生查询走 PartTree 路径。
     * 注意：某些方法可能同时满足两者（如 findByMinAmount 有 @Query 且方法名匹配派生查询），
     * 此时必须走 JPQL 路径——@Query 注解的 SQL 才是用户意图。
     */
    private static final class JpaGutterNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        static final JpaGutterNavigationHandler INSTANCE = new JpaGutterNavigationHandler();

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            Project project = elt.getProject();
            PsiMethod method = PsiTreeUtil.getParentOfType(elt, PsiMethod.class, false);
            if (method == null) return;
            SqlPreviewService service = project.getService(SqlPreviewService.class);
            if (JpqlQueryLocator.hasQueryAnnotation(method)) {
                SqlPreviewService.Companion.previewAsync(project, () -> service.previewJpql(method),
                        result -> { SqlPreviewService.Companion.showResult(project, result, null); return kotlin.Unit.INSTANCE; });
            } else {
                SqlPreviewService.Companion.previewAsync(project, () -> service.previewDerivedQuery(method),
                        result -> { SqlPreviewService.Companion.showResult(project, result, null); return kotlin.Unit.INSTANCE; });
            }
        }
    }
}
