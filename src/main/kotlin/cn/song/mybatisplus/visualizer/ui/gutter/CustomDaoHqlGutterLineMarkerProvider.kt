package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlLocator
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
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * 自定义 DAO 方法 HQL gutter 预览图标（波次一）：识别 `commonDao.findByQuery(...)` / `baseDao.executeHql(...)`
 * 等自定义 DAO 调用行显示预览图标，点击弹出 SQL 预览。
 *
 * **性能红线**：快速路径只做 O(1) 语法判定——leaf 锚点为方法调用表达式中的方法名 PsiIdentifier，
 * 复用 [CustomDaoHqlLocator.locate] 非 null 判定确认该调用为自定义 DAO HQL 调用；
 * 完整解析（HQL 转换）仅在点击时走 [SqlPreviewService.previewAsync] 后台线程。
 *
 * **锚点策略**：与 [HqlGutterLineMarkerProvider] 锚定在宿主 PsiMethod 名不同，此处锚定在具体的方法调用
 * 方法名 PsiIdentifier 上。原因：一个方法体内可能有多个不同的 DAO HQL 调用，图标需精确到具体的那一行
 * `commonDao.findByQuery(...)` 调用。
 *
 * **去重保证**：同一个 PsiMethodCallExpression 只有一个方法名 identifier，天然去重。
 * 其他 provider（Hql / Jpql / Jpa / CriteriaQuery）的锚点策略与此不冲突。
 *
 * 注册见 plugin.xml `<codeInsight.lineMarkerProvider language="JAVA" .../>`。
 */
class CustomDaoHqlGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // LineMarker 框架只传递 leaf 元素：锚点为 PsiIdentifier（变量名 / 方法名 leaf）。
        // 其它 leaf（关键字、括号、字面量等）不可能位于自定义 DAO 调用链上——早退。
        if (element !is PsiIdentifier) return null
        // 锚定在方法调用的方法名 PsiIdentifier 上：
        // element = "findByQuery" 等方法名 identifier
        // element.parent = PsiReferenceExpression（方法调用的方法名引用）
        // element.parent.parent = PsiMethodCallExpression
        val parent = element.parent as? PsiReferenceExpression ?: return null
        val grandParent = parent.parent as? PsiMethodCallExpression ?: return null
        // 确认 element 是方法名而非 qualifier：方法调用的 referenceName 必须等于 element 的文本
        if (grandParent.methodExpression.referenceName != element.text) return null
        // 【关键排除】标准 JPA/Hibernate 方法（createQuery/createNamedQuery/createNativeQuery）由 HqlGutterLineMarkerProvider 负责，
        // 本 provider 仅处理自定义 DAO 方法（findByQuery/executeHql 等），避免同行双图标。
        if (element.text in setOf("createQuery", "createNamedQuery", "createNativeQuery")) return null
        if (!gutterEnabled()) return null
        if (CustomDaoHqlLocator.locate(grandParent) == null) return null
        return LineMarkerInfo(
            element,
            element.textRange,
            CUSTOM_DAO_HQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.customDaoHql.tooltip") },
            CustomDaoHqlGutterNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.customDaoHql.accessible.name") },
        )
    }

    private fun gutterEnabled(): Boolean = runCatching {
        PluginSettings.getInstance().state.showGutterIcon
    }.getOrDefault(true)

    private object CustomDaoHqlGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project: Project = elt.project
            // 直接从锚点向上查找 PsiMethodCallExpression（不依赖 locate() 反查）
            val parent = elt.parent as? PsiReferenceExpression ?: return
            val call = parent.parent as? PsiMethodCallExpression ?: return
            val service = project.getService(SqlPreviewService::class.java)
            SqlPreviewService.previewAsync(project, {
                service.previewCustomDaoHql(call)
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }
    }

    companion object {
        private val CUSTOM_DAO_HQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", CustomDaoHqlGutterLineMarkerProvider::class.java)
    }
}
