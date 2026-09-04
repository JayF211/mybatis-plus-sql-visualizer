package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateMethodDetector;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.psi.PreviewReceiverResolver;
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
 * JdbcTemplate / NamedParameterJdbcTemplate SQL 字符串预览水印图标（JDBC 模板 SQL 字符串预览）。
 *
 * <p>在 `queryForList` / `update` / `queryForObject` / `execute` 等 JDBC 模板方法调用行显示预览图标，
 * 点击弹出 SQL 预览（复用 {@link SqlPreviewService} 弹窗链路，支持命名参数回填）。
 *
 * <p><b>性能红线</b>：快速路径只做 O(1) 语法判定——锚点为方法名 {@link PsiIdentifier}，方法名 ∈
 * JdbcTemplateMethodDetector.JDBC_TEMPLATE_METHODS 且第 1 实参存在；零 resolve、零聚合、
 * 零转换；完整解析（字符串聚合 / 命名参数识别）仅在点击时走 SqlPreviewService.previewAsync。
 *
 * <p><b>去重保证</b>：与既有 SqlGutterLineMarkerProvider（Wrapper 链）、
 * AnnotationSqlGutterLineMarkerProvider（@Select 注解）、
 * CustomDaoHqlGutterLineMarkerProvider（自定义 DAO HQL）互不冲突——方法名集合天然区分
 * （`query*` 是 Spring JDBC 模板方法名，`createQuery`/`findByQuery` 是 Hibernate / 自定义 DAO）。
 *
 * <p>注册见 plugin.xml `codeInsight.lineMarkerProvider language="JAVA"`。
 */
public class JdbcTemplateSqlGutterLineMarkerProvider implements LineMarkerProvider {

    private static final Icon SQL_PREVIEW_ICON =
            IconLoader.getIcon("/icons/sqlGutter.svg", JdbcTemplateSqlGutterLineMarkerProvider.class);

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        // LineMarker 框架只传递 leaf 元素：锚点为 PsiIdentifier（方法名 leaf）。
        if (!(element instanceof PsiIdentifier)) return null;

        // 锚定在方法调用的方法名 PsiIdentifier 上：
        // element = "queryForList" 等方法名 identifier
        // element.parent = PsiReferenceExpression（方法调用的方法名引用）
        // element.parent.parent = PsiMethodCallExpression
        PsiElement parentRaw = element.getParent();
        if (!(parentRaw instanceof PsiReferenceExpression)) return null;
        PsiReferenceExpression parent = (PsiReferenceExpression) parentRaw;
        PsiElement grandParentRaw = parent.getParent();
        if (!(grandParentRaw instanceof PsiMethodCallExpression)) return null;
        PsiMethodCallExpression grandParent = (PsiMethodCallExpression) grandParentRaw;

        // 确认 element 是方法名而非 qualifier
        String refName = grandParent.getMethodExpression().getReferenceName();
        if (!element.getText().equals(refName)) return null;

        // 方法名 ∈ JDBC 模板方法集合（O(1) 判定）
        if (!JdbcTemplateMethodDetector.isJdbcTemplateMethodReference(element.getText())) return null;

        // T-GutterDedup：接收者必须是 JDBC 模板家族（JdbcTemplate/NamedParameter/DbHelper），
        // 避免通用方法名 `execute` 在 commonDao / getHibernateTemplate() 上误标（重复图标）
        if (PreviewReceiverResolver.classify(grandParent) != PreviewReceiverResolver.Family.JDBC_TEMPLATE) {
            return null;
        }

        // T-GutterDedup：第 1 实参须为字符串 SQL（杜绝 execute(callback) 非字符串实参"有图标点击无效"）
        if (!PreviewReceiverResolver.isStringLikeFirstArg(grandParent)) return null;

        // 开关关闭时不显示
        if (!gutterEnabled()) return null;

        return new LineMarkerInfo<PsiElement>(
                element,
                element.getTextRange(),
                SQL_PREVIEW_ICON,
                elt -> MpSqlVisualizerBundle.message("gutter.jdbcTemplate.tooltip"),
                JdbcTemplateGutterNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.jdbcTemplate.accessible.name"));
    }

    /** 读取 gutter 图标开关 + JDBC 预览开关（测试环境 service 未注册时按默认 true 处理）。 */
    private boolean gutterEnabled() {
        try {
            PluginSettings.State state = PluginSettings.getInstance().getState();
            return state.getShowGutterIcon() && state.getJdbcSqlPreviewEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 点击处理：在 EDT 捕获锚点元素，转换走后台线程 + ReadAction（EDT 禁止 PSI 慢操作），
     * 结果按显示方式配置展示。
     */
    private enum JdbcTemplateGutterNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        INSTANCE;

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            Project project = elt.getProject();
            SqlPreviewService.previewAsync(project, () -> {
                JdbcTemplateSqlLocator.JdbcTemplateSqlCall call = JdbcTemplateSqlLocator.INSTANCE.locate(elt);
                if (call != null) {
                    return JdbcTemplateSqlPreviewService.preview(call, elt);
                }
                return null;
            }, (PreviewResult result) -> {
                SqlPreviewService.showResult(project, result);
                return kotlin.Unit.INSTANCE;
            });
        }
    }
}
