package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
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
import com.intellij.psi.PsiMethod
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
 */
class JpaGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // 【去重关键】LineMarker 框架对 PSI 树的每个叶子元素回调本方法。
        // 必须锚定在方法名 PsiIdentifier 上——仅当 element 是方法的名称 token 时才创建 marker，
        // 避免方法声明内的每个叶子（返回类型、参数名、关键字等）都产生重复图标。
        if (element !is com.intellij.psi.PsiIdentifier) return null
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return null
        // 确认当前 leaf 就是方法名（而非方法体内某个变量名 / 类型名）
        if (method.nameIdentifier !== element) return null
        if (!gutterEnabled()) return null
        // 必须同时是 Repository 接口内的派生方法名——避免普通类手写方法（如业务 DAO 的
        // `deleteByDbHelper`）被误判为 JPA 派生查询而显示错误图标（触发 previewDerivedQuery 返回 null）
        // M38.1（负例回归集实证）：派生查询只存在于 Repository 接口——普通类里 PartTree 形态的
        // 方法名（findByXxx 等）不得标锚（Flex-Helper「图标误显示」同款差评源）。文本级守卫：
        // 所在类必须是接口，且 extends 列表类型名含 Repository（Spring Data 全家桶均以 Repository 结尾）。
        if (!isInRepositoryInterface(method)) return null
        if (!DerivedMethodLocator.isRepositoryMethod(method)) return null

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

    private fun gutterEnabled(): Boolean = runCatching {
        PluginSettings.getInstance().state.showGutterIcon
    }.getOrDefault(true)

    /**
     * 方法所在类是否为 Repository 接口（M38.1，文本级 O(1)——无 resolve，符合性能红线）：
     * 接口 + extends 类型名含 "Repository"（JpaRepository/CrudRepository/PagingAndSortingRepository 等）。
     * fixture 中的红色声明（接口 extends 未 resolve 的类）同样按文本命中。
     */
    private fun isInRepositoryInterface(method: com.intellij.psi.PsiMethod): Boolean {
        val containingClass = method.containingClass ?: return false
        if (!containingClass.isInterface) return false
        val extendsList = containingClass.extendsList ?: return false
        return extendsList.referencedTypes.any {
            (it as? com.intellij.psi.PsiClassType)?.className?.contains("Repository") == true
        }
    }

    /**
     * 导航处理：@Query 方法走 JPQL 路径，纯派生查询走 PartTree 路径。
     * 注意：某些方法可能同时满足两者（如 findByMinAmount 有 @Query 且方法名匹配派生查询），
     * 此时必须走 JPQL 路径——@Query 注解的 SQL 才是用户意图。
     */
    private object JpaGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project: Project = elt.project
            val method = PsiTreeUtil.getParentOfType(elt, PsiMethod::class.java, false) ?: return
            val service = project.getService(SqlPreviewService::class.java)
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
