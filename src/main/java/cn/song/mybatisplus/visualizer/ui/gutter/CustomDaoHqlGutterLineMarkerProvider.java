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
import kotlin.Unit;

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

    private static final java.util.Set<String> EXCLUDED_JPA_METHODS =
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
        if (EXCLUDED_JPA_METHODS.contains(element.getText())) return null;
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
        // T-Perf-02：纯文本预筛（O(1)，零 resolve、零正则）——qualifier 名与方法名均无 DAO/HQL
        // 特征的调用直接排除，不进 locate()。locate 内部会跑接收者 classify（名字启发失败时
        // resolve 类型）与 4 条 HQL 内容正则；daemon 每趟对项目内每个方法调用都执行本 provider，
        // 无预筛时构成热路径开销。预筛是 locate 认领路径的保守超集（宁可放行，覆盖不回退）。
        if (!passesTextPrefilter(grandParent)) return null;
        if (CustomDaoHqlLocator.locate(grandParent) == null) return null;
        return new LineMarkerInfo<PsiElement>(
                element,
                element.getTextRange(),
                CUSTOM_DAO_HQL_PREVIEW_ICON,
                elt -> MpSqlVisualizerBundle.message("gutter.customDaoHql.tooltip"),
                CustomDaoHqlGutterNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.customDaoHql.accessible.name")
        );
    }

    /**
     * T-Perf-02：纯文本预筛（O(1)，零 PSI resolve）——镜像 {@link CustomDaoHqlLocator} 的名字层启发
     * （CUSTOM_DAO 的 DAO qualifier 后缀 / 查询方法名关键字、HIBERNATE_TEMPLATE 的
     * {@code getHibernateTemplate().find(...)} 形态），任一命中即放行进 locate() 做完整判定。
     *
     * <p>对 find/iterate 等通用名：qualifier 名含 hibernate+template / *template / DAO 后缀均放行
     * （类型解析命中的怪名变量如 {@code HibernateTemplate template} 会被预筛漏掉，但名字层本就无法
     * 覆盖，与"宁可漏标不重复标"的既有口径一致且仅限极少数命名）。
     */
    private static boolean passesTextPrefilter(com.intellij.psi.PsiMethodCallExpression call) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) return false;
        String lowerName = methodName.toLowerCase(java.util.Locale.ROOT);
        // 查询方法名关键字（镜像 QUERY_METHOD_KEYWORDS 的 contains-ignoreCase 语义）
        if (lowerName.contains("query") || lowerName.contains("hql") || lowerName.contains("jpql")
                || lowerName.contains("findbysql")) return true;
        com.intellij.psi.PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        String qualifierName = null;
        if (qualifier instanceof PsiReferenceExpression) {
            qualifierName = ((PsiReferenceExpression) qualifier).getReferenceName();
        } else if (qualifier instanceof PsiMethodCallExpression) {
            qualifierName = ((PsiMethodCallExpression) qualifier).getMethodExpression().getReferenceName();
        }
        if (qualifierName != null) {
            String lowerQ = qualifierName.toLowerCase(java.util.Locale.ROOT);
            // DAO qualifier 后缀（镜像 DAO_QUALIFIER_SUFFIXES）
            for (String suffix : DAO_QUALIFIER_SUFFIXES) {
                if (qualifierName.endsWith(suffix)) return true;
            }
            // HibernateTemplate 形态（镜像 classifyMethodCallQualifier 的名字启发）
            for (String hqlMethod : HIBERNATE_HQL_METHOD_NAMES) {
                if (hqlMethod.toLowerCase(java.util.Locale.ROOT).equals(lowerName) && lowerQ.contains("template")) return true;
            }
        }
        return false;
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
                    result -> { SqlPreviewService.showResult(project, result, null); return kotlin.Unit.INSTANCE; });
        }
    }

    private static final Icon CUSTOM_DAO_HQL_PREVIEW_ICON =
            IconLoader.getIcon("/icons/sqlGutter.svg", CustomDaoHqlGutterLineMarkerProvider.class);

    /** DAO qualifier 名后缀（镜像 CustomDaoHqlLocator.DAO_QUALIFIER_SUFFIXES，预筛用）。 */
    private static final java.util.List<String> DAO_QUALIFIER_SUFFIXES =
            java.util.List.of("Dao", "DaoImpl", "Mapper", "Repository", "CommonDao", "DaoHelper", "BaseDao");

    /** HibernateTemplate HQL 方法名（镜像 CustomDaoHqlLocator.HIBERNATE_HQL_METHODS，预筛用）。 */
    private static final java.util.Set<String> HIBERNATE_HQL_METHOD_NAMES =
            java.util.Set.of("find", "findByNamedParam", "findByValueBean", "iterate");
}
