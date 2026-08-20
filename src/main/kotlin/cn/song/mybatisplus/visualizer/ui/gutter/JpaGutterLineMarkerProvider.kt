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
        // LineMarker 回调的 element 是叶子元素（PsiIdentifier / PsiMethod 名称 token），
        // 向上查找 PsiMethod——与 AnnotationSqlGutterLineMarkerProvider 同模式（ getParentOfType）。
        val method = element as? PsiMethod
            ?: PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
            ?: return null
        if (!gutterEnabled()) return null
        if (!DerivedMethodLocator.isDerivedQuery(method)) return null

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

    private object JpaGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project: Project = elt.project
            val method = PsiTreeUtil.getParentOfType(elt, PsiMethod::class.java, false) ?: return
            val service = project.getService(SqlPreviewService::class.java)
            SqlPreviewService.previewAsync(project, {
                service.previewDerivedQuery(method)
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }
    }
}
