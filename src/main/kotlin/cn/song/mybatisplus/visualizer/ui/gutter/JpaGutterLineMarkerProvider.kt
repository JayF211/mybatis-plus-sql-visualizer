package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.converter.jpa.JpaCrudOperationConverter
import cn.song.mybatisplus.visualizer.converter.jpa.JpaQueryEnhancer
import cn.song.mybatisplus.visualizer.converter.jpa.QueryByExampleConverter
import cn.song.mybatisplus.visualizer.converter.jpa.SpecificationQueryConverter
import cn.song.mybatisplus.visualizer.psi.jpa.DerivedMethodLocator
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import java.awt.event.MouseEvent
import javax.swing.Icon

private val JPA_SQL_PREVIEW_ICON: Icon =
    IconLoader.getIcon("/icons/sqlGutter.svg", JpaGutterLineMarkerProvider::class.java)

/**
 * JPA Repository 推导查询 gutter 预览图标（F-14 阶段四）：Repository 接口中的推导方法名
 * （如 `findByLastName`）所在行显示预览图标，点击弹出 SQL 预览。
 *
 * **性能红线**：快速路径只做 O(1) 语法判定——leaf 锚点为 PsiMethod 名称 identifier，
 * 检查 containing class 是否为 Repository 接口 + 方法名是否包含 `By` 关键字。
 * 完整解析（PartTree 转换）仅在点击时走 [SqlPreviewService.previewAsync] 后台线程。
 *
 * T-J5/T-J20/T-J16 增补锚点（最小改动，同图标同导航）：
 *  - **executor/QBE 调用锚点**：`userRepository.findAll(spec)` / `findOne(example)` 等
 *    JpaSpecificationExecutor / QueryByExampleExecutor 家族调用处——仅补 [JpaCrudOperationConverter.crudAnchorMethodNames]
 *    **不在**名单的方法名（findOne/exists/findBy；findAll/count/delete 已由 SqlGutterLineMarkerProvider
 *    场景 E 承载且点击统一落 preview() 的 Specification/QBE 前置分支，此处重复标注会产生双图标，
 *    T-GutterDedup 教训）；receiver 家族判定与转换同源（宁可无图标，不可死图标）。
 *  - **@Procedure 方法声明锚点**（T-J16）：Repository 接口中标注 @Procedure 的方法名。
 *  - T-J9 调用点反查（派生/@Query 方法的 service 层调用处）**刻意不做** gutter——反查需
 *    resolve + 类型链判定，超出快速路径预算，动作/快捷键路径（ConvertToSqlAction）承载。
 */
class JpaGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // 【去重关键】LineMarker 框架对 PSI 树的每个叶子元素回调本方法。
        // 必须锚定在方法名 PsiIdentifier 上——仅当 element 是方法的名称 token 时才创建 marker，
        // 避免方法声明内的每个叶子（返回类型、参数名、关键字等）都产生重复图标。
        if (element !is PsiIdentifier) return null
        if (!gutterEnabled()) return null

        // T-J16：@Procedure 方法声明锚点（Repository 接口方法名 identifier）
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        if (method != null && method.nameIdentifier === element &&
            JpaQueryEnhancer.procedureInfo(method) != null
        ) {
            return LineMarkerInfo(
                element,
                element.textRange,
                JPA_SQL_PREVIEW_ICON,
                { MpSqlVisualizerBundle.message("gutter.jpa.tooltip") },
                JpaGutterNavigationHandler,
                GutterIconRenderer.Alignment.CENTER,
                { MpSqlVisualizerBundle.message("gutter.jpa.accessible.name") },
            )
        }

        // 既有：Repository 接口内的派生方法名锚点——避免普通类手写方法（如业务 DAO 的
        // `deleteByDbHelper`）被误判为 JPA 派生查询而显示错误图标（触发 previewDerivedQuery 返回 null）
        if (method != null && method.nameIdentifier === element &&
            DerivedMethodLocator.isRepositoryMethod(method)
        ) {
            return LineMarkerInfo(
                element,
                element.textRange,
                JPA_SQL_PREVIEW_ICON,
                { MpSqlVisualizerBundle.message("gutter.jpa.tooltip") },
                JpaGutterNavigationHandler,
                GutterIconRenderer.Alignment.CENTER,
                { MpSqlVisualizerBundle.message("gutter.jpa.accessible.name") },
            )
        }

        // T-J5/T-J20：executor/QBE 调用锚点（`userRepository.findAll(spec)` 调用处的方法名 identifier）。
        // 先按方法名做 O(1) 门控（且排除场景 E 已覆盖的 crud 名单，防双图标），再做 receiver 家族判定
        // （场景 E 同款微秒级 resolve 例外）。
        val call = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression::class.java, false)
        if (call != null && call.methodExpression.referenceNameElement === element &&
            isExecutorAnchorMethodName(call.methodExpression.referenceName) &&
            (SpecificationQueryConverter.isSpecificationExecutorCall(call) || QueryByExampleConverter.isExampleCall(call))
        ) {
            return LineMarkerInfo(
                element,
                element.textRange,
                JPA_SQL_PREVIEW_ICON,
                { MpSqlVisualizerBundle.message("gutter.jpa.tooltip") },
                JpaGutterNavigationHandler,
                GutterIconRenderer.Alignment.CENTER,
                { MpSqlVisualizerBundle.message("gutter.jpa.accessible.name") },
            )
        }
        return null
    }

    /**
     * executor/QBE 调用锚点的方法名门控：
     * 在 Specification/QBE 家族方法名并集内、且**不在** [JpaCrudOperationConverter.crudAnchorMethodNames]
     * （后者由 SqlGutterLineMarkerProvider 场景 E 承载，重复标注=双图标）。
     */
    private fun isExecutorAnchorMethodName(name: String?): Boolean {
        if (name == null) return false
        if (name in JpaCrudOperationConverter.crudAnchorMethodNames) return false
        return name in SpecificationQueryConverter.EXECUTOR_METHODS || name in QueryByExampleConverter.EXAMPLE_METHODS
    }

    private fun gutterEnabled(): Boolean = runCatching {
        PluginSettings.getInstance().state.showGutterIcon
    }.getOrDefault(true)

    /**
     * 导航处理：@Query 方法走 JPQL 路径，纯派生查询走 PartTree 路径；
     * @Procedure 方法走存储过程路径（T-J16）；executor/QBE 调用锚点走对应转换器（T-J5/T-J20）。
     * 注意：某些方法可能同时满足两者（如 findByMinAmount 有 @Query 且方法名匹配派生查询），
     * 此时必须走 JPQL 路径——@Query 注解的 SQL 才是用户意图。
     */
    private object JpaGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project: Project = elt.project
            val service = project.getService(SqlPreviewService::class.java)

            // T-J5/T-J20：executor/QBE 调用锚点优先（锚点在调用处，无声明方法上下文）
            val call = PsiTreeUtil.getParentOfType(elt, PsiMethodCallExpression::class.java, false)
            if (call != null && call.methodExpression.referenceNameElement === elt) {
                val resultProvider = {
                    service.previewSpecification(elt) ?: service.previewQueryByExample(elt)
                }
                SqlPreviewService.previewAsync(project, resultProvider) { result ->
                    SqlPreviewService.showResult(project, result)
                }
                return
            }

            val method = PsiTreeUtil.getParentOfType(elt, PsiMethod::class.java, false) ?: return
            // T-J16：@Procedure 方法 → 存储过程 CALL 预览
            if (JpaQueryEnhancer.procedureInfo(method) != null) {
                SqlPreviewService.previewAsync(project, { service.previewProcedure(method) }) { result ->
                    SqlPreviewService.showResult(project, result)
                }
                return
            }
            if (cn.song.mybatisplus.visualizer.psi.jpa.JpqlQueryLocator.hasQueryAnnotation(method)) {
                SqlPreviewService.previewAsync(project, { service.previewJpql(method) }) { result ->
                    SqlPreviewService.showResult(project, result)
                }
            } else {
                SqlPreviewService.previewAsync(project, { service.previewDerivedQuery(method) }) { result ->
                    SqlPreviewService.showResult(project, result)
                }
            }
        }
    }
}
