package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryLocator
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
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * JPA CriteriaQuery gutter 预览图标（波次一）：识别 JPA CriteriaBuilder 强类型查询调用链
 * （`cq.from(...)` / `cb.equal(...)` 等）所在行显示预览图标，点击弹出 SQL 预览。
 *
 * **性能红线**：快速路径只做 O(1) 语法判定——leaf 锚点为 PsiIdentifier（变量名）或 PsiMethodCallExpression，
 * 复用 [CriteriaQueryLocator.locate] 非 null 判定确认光标在 CriteriaQuery 调用链上；
 * 完整解析（CriteriaQueryModel 转换）仅在点击时走 [SqlPreviewService.previewAsync] 后台线程。
 *
 * 注册见 plugin.xml `<codeInsight.lineMarkerProvider language="JAVA" .../>`。
 */
class CriteriaQueryGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // LineMarker 框架只传递 leaf 元素：锚点为 PsiIdentifier（变量名 / 方法名 leaf）。
        // 其它 leaf（关键字、括号、字面量等）不可能位于 CriteriaQuery 调用链上——早退。
        if (element !is PsiIdentifier) return null
        if (!gutterEnabled()) return null
        // 复用 CriteriaQueryLocator.locate 非 null 判定（完整扫描代码块，开销可控）
        if (CriteriaQueryLocator.locate(element) == null) return null
        return LineMarkerInfo(
            element,
            element.textRange,
            CRITERIA_SQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.criteria.tooltip") },
            CriteriaQueryGutterNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.criteria.accessible.name") },
        )
    }

    private fun gutterEnabled(): Boolean = runCatching {
        PluginSettings.getInstance().state.showGutterIcon
    }.getOrDefault(true)

    private object CriteriaQueryGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project: Project = elt.project
            val service = project.getService(SqlPreviewService::class.java)
            SqlPreviewService.previewAsync(project, {
                service.previewCriteriaQuery(elt)
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }
    }

    companion object {
        private val CRITERIA_SQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", CriteriaQueryGutterLineMarkerProvider::class.java)
    }
}
