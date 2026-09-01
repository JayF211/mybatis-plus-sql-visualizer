package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryLocator;
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
 * JPA CriteriaQuery gutter 预览图标（波次一）：识别 JPA CriteriaBuilder 强类型查询调用链
 * （`cq.from(...)` / `cb.equal(...)` 等）所在行显示预览图标，点击弹出 SQL 预览。
 *
 * <p><b>性能红线</b>：快速路径只做 O(1) 语法判定——leaf 锚点为 PsiIdentifier（变量名）或 PsiMethodCallExpression，
 * 复用 {@link CriteriaQueryLocator#locate} 非 null 判定确认光标在 CriteriaQuery 调用链上；
 * 完整解析（CriteriaQueryModel 转换）仅在点击时走 {@link SqlPreviewService#previewAsync} 后台线程。
 *
 * <p>注册见 plugin.xml {@code <codeInsight.lineMarkerProvider language="JAVA" .../>}。
 */
public class CriteriaQueryGutterLineMarkerProvider implements LineMarkerProvider {

    @Override
    @Nullable
    public LineMarkerInfo<PsiElement> getLineMarkerInfo(@NotNull PsiElement element) {
        // LineMarker 框架只传递 leaf 元素：锚点为 PsiIdentifier（变量名 / 方法名 leaf）。
        // 其它 leaf（关键字、括号、字面量等）不可能位于 CriteriaQuery 调用链上——早退。
        if (!(element instanceof PsiIdentifier)) return null;
        if (!gutterEnabled()) return null;
        // 【去重关键】锚定在包含 CriteriaQuery 调用的方法名上——仅当 element 是方法名 token 时才创建 marker，
        // 避免方法体内每个 PsiIdentifier（变量名、字段名等）都产生重复图标。
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (method == null) return null;
        if (method.getNameIdentifier() != element) return null;
        // 复用 CriteriaQueryLocator.locate 非 null 判定（完整扫描代码块，开销可控）
        if (CriteriaQueryLocator.locate(element) == null) return null;
        return new LineMarkerInfo<PsiElement>(
                element,
                element.getTextRange(),
                CRITERIA_SQL_PREVIEW_ICON,
                elt -> MpSqlVisualizerBundle.message("gutter.criteria.tooltip"),
                CriteriaQueryGutterNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.criteria.accessible.name")
        );
    }

    private boolean gutterEnabled() {
        try {
            return PluginSettings.getInstance().getState().getShowGutterIcon();
        } catch (Throwable t) {
            return true;
        }
    }

    private static final class CriteriaQueryGutterNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        static final CriteriaQueryGutterNavigationHandler INSTANCE = new CriteriaQueryGutterNavigationHandler();

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            Project project = elt.getProject();
            SqlPreviewService service = project.getService(SqlPreviewService.class);
            SqlPreviewService.Companion.previewAsync(project, () -> service.previewCriteriaQuery(elt),
                    result -> { SqlPreviewService.Companion.showResult(project, result, null); return kotlin.Unit.INSTANCE; });
        }
    }

    private static final Icon CRITERIA_SQL_PREVIEW_ICON =
            IconLoader.getIcon("/icons/sqlGutter.svg", CriteriaQueryGutterLineMarkerProvider.class);
}
