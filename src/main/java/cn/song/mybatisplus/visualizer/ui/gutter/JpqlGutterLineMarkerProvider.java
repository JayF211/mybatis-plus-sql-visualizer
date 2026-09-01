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
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaToken;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import kotlin.Unit;

import java.awt.event.MouseEvent;
import javax.swing.Icon;

/**
 * JPA JPQL/@Query 注解 gutter 预览图标（波次一）：Repository 接口中标注 `@Query` 的方法
 * 行显示预览图标，点击弹出 SQL 预览（原生 SQL 直接展示；JPQL 经
 * {@code cn.song.mybatisplus.visualizer.converter.jpa.JpqlConverter} 转换）。
 *
 * <p><b>性能红线</b>：快速路径只做 O(1) 语法判定——leaf 锚点为注解的 `@` token（{@link JavaTokenType#AT}），
 * 父链是 PsiAnnotation 且 qualifiedName 末段 = `Query`，再校验所属方法含 @Query 注解；
 * 完整解析（JPQL 转换）仅在点击时走 {@link SqlPreviewService#previewAsync} 后台线程。
 *
 * <p>注册见 plugin.xml {@code <codeInsight.lineMarkerProvider language="JAVA" .../>}。
 */
public class JpqlGutterLineMarkerProvider implements LineMarkerProvider {

    @Override
    @Nullable
    public LineMarkerInfo<PsiElement> getLineMarkerInfo(@NotNull PsiElement element) {
        // LineMarker 必须注册在 leaf 元素：注解的 `@` token
        if (!(element instanceof PsiJavaToken)) return null;
        PsiJavaToken token = (PsiJavaToken) element;
        if (token.getTokenType() != JavaTokenType.AT) return null;
        PsiAnnotation annotation = PsiTreeUtil.getParentOfType(token, PsiAnnotation.class, false);
        if (annotation == null) return null;
        // 注解名末段 = Query（Spring Data @Query / 短名兜底）
        String annotationName = annotation.getQualifiedName();
        if (annotationName == null) {
            annotationName = annotation.getNameReferenceElement() != null
                    ? annotation.getNameReferenceElement().getReferenceName() : null;
        } else {
            int dot = annotationName.lastIndexOf('.');
            annotationName = dot < 0 ? annotationName : annotationName.substring(dot + 1);
        }
        if (!"Query".equals(annotationName)) return null;
        // 校验所属方法含 @Query 注解（排除普通 import 的 Query 类型引用等误判）
        PsiMethod method = PsiTreeUtil.getParentOfType(annotation, PsiMethod.class, false);
        if (method == null) return null;
        // 【去重关键】派生查询（findByXxx）已由 JpaGutterLineMarkerProvider 负责图标；
        // 本 provider 仅处理非派生查询的 @Query 方法，避免同行双图标。
        // 注意：isDerivedQuery 必须在 hasQueryAnnotation 之前检查——某些方法可能同时满足两者。
        if (DerivedMethodLocator.isDerivedQuery(method)) return null;
        if (!JpqlQueryLocator.hasQueryAnnotation(method)) return null;
        if (!gutterEnabled()) return null;
        return new LineMarkerInfo<PsiElement>(
                element,
                element.getTextRange(),
                JPQL_SQL_PREVIEW_ICON,
                elt -> MpSqlVisualizerBundle.message("gutter.jpql.tooltip"),
                JpqlGutterNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.jpql.accessible.name")
        );
    }

    private boolean gutterEnabled() {
        try {
            return PluginSettings.getInstance().getState().getShowGutterIcon();
        } catch (Throwable t) {
            return true;
        }
    }

    private static final class JpqlGutterNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        static final JpqlGutterNavigationHandler INSTANCE = new JpqlGutterNavigationHandler();

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            Project project = elt.getProject();
            PsiMethod method = PsiTreeUtil.getParentOfType(elt, PsiMethod.class, false);
            if (method == null) return;
            SqlPreviewService service = project.getService(SqlPreviewService.class);
            SqlPreviewService.previewAsync(project, () -> service.previewJpql(method),
                    result -> { SqlPreviewService.showResult(project, result, null); return kotlin.Unit.INSTANCE; });
        }
    }

    private static final Icon JPQL_SQL_PREVIEW_ICON =
            IconLoader.getIcon("/icons/sqlGutter.svg", JpqlGutterLineMarkerProvider.class);
}
