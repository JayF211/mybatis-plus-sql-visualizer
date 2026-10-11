package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.converter.jpa.JpaCrudOperationConverter;
import cn.song.mybatisplus.visualizer.converter.jpa.JpaQueryEnhancer;
import cn.song.mybatisplus.visualizer.converter.jpa.QueryByExampleConverter;
import cn.song.mybatisplus.visualizer.converter.jpa.SpecificationQueryConverter;
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
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceList;
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
 *
 * <p>T-J5/T-J20/T-J16 增补锚点（最小改动，同图标同导航）：
 * <ul>
 *  <li><b>executor/QBE 调用锚点</b>：{@code userRepository.findAll(spec)} / {@code findOne(example)} 等
 *      JpaSpecificationExecutor / QueryByExampleExecutor 家族调用处——仅补
 *      {@link JpaCrudOperationConverter#crudAnchorMethodNames} <b>不在</b>名单的方法名
 *      （findOne/exists/findBy；findAll/count/delete 已由 SqlGutterLineMarkerProvider
 *      场景 E 承载且点击统一落 preview() 的 Specification/QBE 前置分支，此处重复标注会产生双图标，
 *      T-GutterDedup 教训）；receiver 家族判定与转换同源（宁可无图标，不可死图标）。</li>
 *  <li><b>@Procedure 方法声明锚点</b>（T-J16）：Repository 接口中标注 @Procedure 的方法名。</li>
 *  <li>T-J9 调用点反查（派生/@Query 方法的 service 层调用处）<b>刻意不做</b> gutter——反查需
 *      resolve + 类型链判定，超出快速路径预算，动作/快捷键路径（ConvertToSqlAction）承载。</li>
 * </ul>
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

        // T-J16：@Procedure 方法声明锚点（Repository 接口方法名 identifier）
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (method != null && method.getNameIdentifier() == element
                && JpaQueryEnhancer.procedureInfo(method) != null) {
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

        // 既有：Repository 接口内的派生方法名锚点——避免普通类手写方法（如业务 DAO 的
        // `deleteByDbHelper`）被误判为 JPA 派生查询而显示错误图标（触发 previewDerivedQuery 返回 null）
        // M38.1（负例回归集实证）：派生查询只存在于 Repository 接口——DerivedMethodLocator.isRepositoryMethod
        // 的宿主判定含「接口名以 Repository 结尾」启发式路径，未 extends 任何 Repository 基类型的同名接口
        // 会被放行，故此处再加一层文本级守卫 isInRepositoryInterface 收紧（普通类里 PartTree 形态的方法名
        // `findByXxx` 与 `UserRepository` 裸接口均不得标锚——Flex-Helper「图标误显示」同款差评源）。
        // **不做全锚点前置守卫**：T-J5/T-J20 executor/QBE 锚点锚在 service 层调用处，宿主类本就不是
        // Repository 接口，前置守卫会把它们一并打死。
        if (method != null && method.getNameIdentifier() == element
                && isInRepositoryInterface(method)
                && DerivedMethodLocator.isRepositoryMethod(method)) {
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

        // T-J5/T-J20：executor/QBE 调用锚点（`userRepository.findAll(spec)` 调用处的方法名 identifier）。
        // 先按方法名做 O(1) 门控（且排除场景 E 已覆盖的 crud 名单，防双图标），再做 receiver 家族判定
        // （场景 E 同款微秒级 resolve 例外）。
        PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression.class, false);
        if (call != null && call.getMethodExpression().getReferenceNameElement() == element
                && isExecutorAnchorMethodName(call.getMethodExpression().getReferenceName())
                && (SpecificationQueryConverter.isSpecificationExecutorCall(call)
                || QueryByExampleConverter.isExampleCall(call))) {
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
        return null;
    }

    /**
     * executor/QBE 调用锚点的方法名门控：
     * 在 Specification/QBE 家族方法名并集内、且<b>不在</b>
     * {@link JpaCrudOperationConverter#crudAnchorMethodNames}
     * （后者由 SqlGutterLineMarkerProvider 场景 E 承载，重复标注=双图标）。
     */
    private static boolean isExecutorAnchorMethodName(@Nullable String name) {
        if (name == null) return false;
        if (JpaCrudOperationConverter.crudAnchorMethodNames.contains(name)) return false;
        return SpecificationQueryConverter.EXECUTOR_METHODS.contains(name)
                || QueryByExampleConverter.EXAMPLE_METHODS.contains(name);
    }

    /**
     * 方法所在类是否为 Repository 接口（M38.1，文本级 O(1)——无 resolve，符合性能红线）：
     * 接口 + extends 类型名含 "Repository"（JpaRepository/CrudRepository/PagingAndSortingRepository 等）。
     * fixture 中的红色声明（接口 extends 未 resolve 的类）同样按文本命中。
     */
    private static boolean isInRepositoryInterface(PsiMethod method) {
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null || !containingClass.isInterface()) return false;
        PsiReferenceList extendsList = containingClass.getExtendsList();
        if (extendsList == null) return false;
        for (PsiClassType type : extendsList.getReferencedTypes()) {
            String className = type.getClassName();
            if (className != null && className.contains("Repository")) return true;
        }
        return false;
    }

    /**
     * 导航处理：@Query 方法走 JPQL 路径，纯派生查询走 PartTree 路径；
     * @Procedure 方法走存储过程路径（T-J16）；executor/QBE 调用锚点走对应转换器（T-J5/T-J20）。
     * 注意：某些方法可能同时满足两者（如 findByMinAmount 有 @Query 且方法名匹配派生查询），
     * 此时必须走 JPQL 路径——@Query 注解的 SQL 才是用户意图。
     */
    private static final class JpaGutterNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        static final JpaGutterNavigationHandler INSTANCE = new JpaGutterNavigationHandler();

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            Project project = elt.getProject();
            SqlPreviewService service = project.getService(SqlPreviewService.class);

            // T-J5/T-J20：executor/QBE 调用锚点优先（锚点在调用处，无声明方法上下文）
            PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(elt, PsiMethodCallExpression.class, false);
            if (call != null && call.getMethodExpression().getReferenceNameElement() == elt) {
                SqlPreviewService.previewAsync(project,
                        () -> {
                            cn.song.mybatisplus.visualizer.model.PreviewResult spec = service.previewSpecification(elt);
                            return spec != null ? spec : service.previewQueryByExample(elt);
                        },
                        result -> {
                            SqlPreviewService.showResult(project, result);
                            return Unit.INSTANCE;
                        });
                return;
            }

            PsiMethod method = PsiTreeUtil.getParentOfType(elt, PsiMethod.class, false);
            if (method == null) return;
            // T-J16：@Procedure 方法 → 存储过程 CALL 预览
            if (JpaQueryEnhancer.procedureInfo(method) != null) {
                SqlPreviewService.previewAsync(project, () -> service.previewProcedure(method),
                        result -> {
                            SqlPreviewService.showResult(project, result);
                            return Unit.INSTANCE;
                        });
                return;
            }
            if (JpqlQueryLocator.hasQueryAnnotation(method)) {
                SqlPreviewService.previewAsync(project, () -> service.previewJpql(method),
                        result -> {
                            SqlPreviewService.showResult(project, result);
                            return Unit.INSTANCE;
                        });
            } else {
                SqlPreviewService.previewAsync(project, () -> service.previewDerivedQuery(method),
                        result -> {
                            SqlPreviewService.showResult(project, result);
                            return Unit.INSTANCE;
                        });
            }
        }
    }
}
