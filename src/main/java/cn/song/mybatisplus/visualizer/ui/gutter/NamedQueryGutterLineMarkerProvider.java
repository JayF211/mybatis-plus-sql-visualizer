package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.psi.jpa.NamedQueryInfo;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.psi.jpa.NamedQueryLocator;
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
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaToken;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;
import javax.swing.Icon;

/**
 * JPA @NamedQuery 实体级 gutter 预览图标（T-PreviewShortcut）。
 *
 * <p>在每个实体类的 `@NamedQuery(...)` 注解（含 `@NamedQueries({...})` 容器内的子注解）行显示预览图标，
 * 点击弹出该命名查询 JPQL 转换后的 SQL。
 *
 * <p><b>锚点策略</b>：与 JpqlGutterLineMarkerProvider 一致——leaf 锚点为注解的 `@` token
 * （{@link JavaTokenType#AT}）。父链是 {@link PsiAnnotation} 且短名 = `NamedQuery`；
 * `@NamedQueries` 容器注解的 `@` token 短名为 `NamedQueries` 被跳过（容器内每个 `@NamedQuery`
 * 子注解自带的 `@` token 会各自锚定，逐个预览）。
 *
 * <p><b>去重保证</b>：命名查询名默认 `&lt;实体简单名&gt;.&lt;方法名&gt;`，与 Jpql/Hql/CustomDao 等
 * 方法名锚点天然区分，不与既有其它 provider 冲突。
 *
 * <p>注册见 plugin.xml `codeInsight.lineMarkerProvider language="JAVA"`。
 */
public class NamedQueryGutterLineMarkerProvider implements LineMarkerProvider {

    private static final Icon NAMED_QUERY_SQL_PREVIEW_ICON =
            IconLoader.getIcon("/icons/sqlGutter.svg", NamedQueryGutterLineMarkerProvider.class);

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        // LineMarker 必须注册在 leaf 元素：注解的 `@` token
        if (!(element instanceof PsiJavaToken)) return null;
        PsiJavaToken token = (PsiJavaToken) element;
        if (token.getTokenType() != JavaTokenType.AT) return null;
        PsiAnnotation annotation = PsiTreeUtil.getParentOfType(token, PsiAnnotation.class, false);
        if (annotation == null) return null;
        // 注解短名 = NamedQuery（@NamedQueries 容器本身跳过——其内部 @NamedQuery 子注解自带 @ token）
        String qualified = annotation.getQualifiedName();
        String simpleName = qualified != null
                ? qualified.substring(qualified.lastIndexOf('.') + 1)
                : (annotation.getNameReferenceElement() != null
                        ? annotation.getNameReferenceElement().getReferenceName() : null);
        if (!"NamedQuery".equals(simpleName)) return null;
        // 校验确属实体类上的命名查询（NamedQueryLocator 能定位到非空）
        PsiClass cls = PsiTreeUtil.getParentOfType(annotation, PsiClass.class, false);
        if (cls == null) return null;
        boolean found = false;
        for (NamedQueryInfo info : NamedQueryLocator.locate(cls)) {
            if (info.getAnnotation() == annotation) {
                found = true;
                break;
            }
        }
        if (!found) return null;
        if (!gutterEnabled()) return null;
        return new LineMarkerInfo<PsiElement>(
                element,
                element.getTextRange(),
                NAMED_QUERY_SQL_PREVIEW_ICON,
                elt -> MpSqlVisualizerBundle.message("gutter.namedQuery.tooltip"),
                NamedQueryGutterNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.namedQuery.accessible.name"));
    }

    private boolean gutterEnabled() {
        try {
            return PluginSettings.getInstance().getState().getShowGutterIcon();
        } catch (Throwable t) {
            return true;
        }
    }

    private enum NamedQueryGutterNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        INSTANCE;

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            Project project = elt.getProject();
            PsiAnnotation annotation = PsiTreeUtil.getParentOfType(elt, PsiAnnotation.class, false);
            if (annotation == null) return;
            SqlPreviewService service = project.getService(SqlPreviewService.class);
            SqlPreviewService.previewAsync(project, () -> service.previewNamedQuery(annotation),
                    (PreviewResult result) -> {
                        SqlPreviewService.showResult(project, result);
                        return kotlin.Unit.INSTANCE;
                    });
        }
    }
}
