package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlLocator;
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
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;
import javax.swing.Icon;

/**
 * 自定义 DAO 方法 HQL gutter 预览图标（波次一）：识别 `commonDao.findByQuery(...)` / `baseDao.executeHql(...)`
 * 等自定义 DAO 调用行显示预览图标，点击弹出 SQL 预览。
 *
 * <p><b>性能红线</b>：快速路径只做 O(1) 语法判定——leaf 锚点为方法调用表达式中的方法名 PsiIdentifier，
 * 复用 {@link CustomDaoHqlLocator#locate} 非 null 判定确认该调用为自定义 DAO HQL 调用；
 * 完整解析（HQL 转换）仅在点击时走 {@link SqlPreviewService#previewAsync} 后台线程。
 *
 * <p><b>锚点策略</b>：与 {@link HqlGutterLineMarkerProvider} 锚定在宿主 PsiMethod 名不同，此处锚定在具体的方法调用
 * 方法名 PsiIdentifier 上。原因：一个方法体内可能有多个不同的 DAO HQL 调用，图标需精确到具体的那一行
 * `commonDao.findByQuery(...)` 调用。
 *
 * <p><b>去重保证</b>：同一个 PsiMethodCallExpression 只有一个方法名 identifier，天然去重。
 * 其他 provider（Hql / Jpql / Jpa / CriteriaQuery）的锚点策略与此不冲突。
 *
 * <p>注册见 plugin.xml {@code <codeInsight.lineMarkerProvider language="JAVA" .../>}。
 */
public class CustomDaoHqlGutterLineMarkerProvider implements LineMarkerProvider {

    private static final java.util.Set<String> STANDARD_JPA_METHODS =
            java.util.Set.of("createQuery", "createNamedQuery", "createNativeQuery");

    @Override
    @Nullable
    public LineMarkerInfo<PsiElement> getLineMarkerInfo(@NotNull PsiElement element) {
        // LineMarker 框架只传递 leaf 元素：锚点为 PsiIdentifier（变量名 / 方法名 leaf）。
        // 其它 leaf（关键字、括号、字面量等）不可能位于自定义 DAO 调用链上——早退。
        if (!(element instanceof PsiIdentifier)) return null;
        // 锚定在方法调用的方法名 PsiIdentifier 上：
        // element = "findByQuery" 等方法名 identifier
        // element.parent = PsiReferenceExpression（方法调用的方法名引用）
        // element.parent.parent = PsiMethodCallExpression
        PsiReferenceExpression parent = element.getParent() instanceof PsiReferenceExpression
                ? (PsiReferenceExpression) element.getParent() : null;
        if (parent == null) return null;
        PsiMethodCallExpression grandParent = parent.getParent() instanceof PsiMethodCallExpression
                ? (PsiMethodCallExpression) parent.getParent() : null;
        if (grandParent == null) return null;
        // 确认 element 是方法名而非 qualifier：方法调用的 referenceName 必须等于 element 的文本
        if (!java.util.Objects.equals(grandParent.getMethodExpression().getReferenceName(), element.getText())) return null;
        // 【关键排除】标准 JPA/Hibernate 方法（createQuery/createNamedQuery/createNativeQuery）由 HqlGutterLineMarkerProvider 负责，
        // 本 provider 仅处理自定义 DAO 方法（findByQuery/executeHql 等），避免同行双图标。
        if (STANDARD_JPA_METHODS.contains(element.getText())) return null;
        if (!gutterEnabled()) return null;
        if (CustomDaoHqlLocator.locate(grandParent) == null) return null;
        return new LineMarkerInfo<>(
                element,
                element.getTextRange(),
                CUSTOM_DAO_HQL_PREVIEW_ICON,
                elt -> MpSqlVisualizerBundle.message("gutter.customDaoHql.tooltip"),
                CustomDaoHqlGutterNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                elt -> MpSqlVisualizerBundle.message("gutter.customDaoHql.accessible.name")
        );
    }

    private boolean gutterEnabled() {
        try {
            return PluginSettings.getInstance().getState().isShowGutterIcon();
        } catch (Throwable t) {
            return true;
        }
    }

    private static final class CustomDaoHqlGutterNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        static final CustomDaoHqlGutterNavigationHandler INSTANCE = new CustomDaoHqlGutterNavigationHandler();

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            Project project = elt.getProject();
            // 直接从锚点向上查找 PsiMethodCallExpression（不依赖 locate() 反查）
            PsiReferenceExpression parent = elt.getParent() instanceof PsiReferenceExpression
                    ? (PsiReferenceExpression) elt.getParent() : null;
            if (parent == null) return;
            PsiMethodCallExpression call = parent.getParent() instanceof PsiMethodCallExpression
                    ? (PsiMethodCallExpression) parent.getParent() : null;
            if (call == null) return;
            SqlPreviewService service = project.getService(SqlPreviewService.class);
            SqlPreviewService.previewAsync(project, () -> service.previewCustomDaoHql(call),
                    result -> SqlPreviewService.showResult(project, result));
        }
    }

    private static final Icon CUSTOM_DAO_HQL_PREVIEW_ICON =
            IconLoader.getIcon("/icons/sqlGutter.svg", CustomDaoHqlGutterLineMarkerProvider.class);
}
