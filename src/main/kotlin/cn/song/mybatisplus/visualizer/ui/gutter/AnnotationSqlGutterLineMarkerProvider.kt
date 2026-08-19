package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.ui.AnnotationSqlPreviewService
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaToken
import com.intellij.psi.util.PsiTreeUtil
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * MyBatis SQL 注解预览图标（T-M25-03）：`@Select/@Insert/@Update/@Delete` 及 Provider 族注解
 * 所在行显示预览图标，点击弹出动态 SQL 预览（复用 [SqlPreviewService.showResult] 链路）。
 *
 * **性能红线**：快速路径只做 O(1) 语法判定——leaf 锚点为注解的 `@` token（[JavaTokenType.AT]），
 * 父链是 PsiAnnotation 且 qualifiedName 末段 ∈ SQL 注解集合（纯文本匹配，零 resolve）；
 * tooltip 静态文案；点击转换走 [SqlPreviewService.previewAsync] 后台线程。
 *
 * 注册见 plugin.xml `<codeInsight.lineMarkerProvider language="JAVA" .../>`（与 Wrapper gutter 共存）。
 */
class AnnotationSqlGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // LineMarker 必须注册在 leaf 元素：注解的 `@` token
        val token = element as? PsiJavaToken ?: return null
        if (token.tokenType != JavaTokenType.AT) return null
        val annotation = PsiTreeUtil.getParentOfType(token, PsiAnnotation::class.java, false) ?: return null
        if (!AnnotationSqlPreviewService.isSqlAnnotation(annotation)) return null
        if (!gutterEnabled()) return null
        // 七参构造器（2020.3+）：末参 accessibleNameProvider 供屏幕阅读器使用
        return LineMarkerInfo(
            element,
            element.textRange,
            SQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.annotation.tooltip") },
            AnnotationGutterNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.annotation.accessible.name") },
        )
    }

    /** 读取 gutter 图标开关（测试环境 service 未注册时按默认 true 处理）。 */
    private fun gutterEnabled(): Boolean = runCatching {
        PluginSettings.getInstance().state.showGutterIcon
    }.getOrDefault(true)

    /** 点击处理：后台解析 + 渲染（EDT 禁止 PSI 慢操作），结果走统一展示入口。 */
    private object AnnotationGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project: Project = elt.project
            val annotation = PsiTreeUtil.getParentOfType(elt, PsiAnnotation::class.java, false) ?: return
            SqlPreviewService.previewAsync(project, {
                AnnotationSqlPreviewService.preview(annotation, project)
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }
    }

    companion object {
        private val SQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", AnnotationSqlGutterLineMarkerProvider::class.java)
    }
}
