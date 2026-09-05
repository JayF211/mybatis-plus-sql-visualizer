package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateMethodDetector
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService
import cn.song.mybatisplus.visualizer.psi.PreviewReceiverResolver
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
 * JdbcTemplate / NamedParameterJdbcTemplate SQL 字符串预览水印图标（JDBC 模板 SQL 字符串预览）。
 *
 * 在 `queryForList` / `update` / `queryForObject` / `execute` 等 JDBC 模板方法调用行显示预览图标，
 * 点击弹出 SQL 预览（复用 [SqlPreviewService] 弹窗链路，支持命名参数回填）。
 *
 * **性能红线**：快速路径只做 O(1) 语法判定——锚点为方法名 [PsiIdentifier]，方法名 ∈
 * [JdbcTemplateMethodDetector.JDBC_TEMPLATE_METHODS] 且第 1 实参存在；零 resolve、零聚合、
 * 零转换；完整解析（字符串聚合 / 命名参数识别）仅在点击时走 [SqlPreviewService.previewAsync]。
 *
 * **去重保证**：与既有 [cn.song.mybatisplus.visualizer.ui.gutter.SqlGutterLineMarkerProvider]
 * （Wrapper 链）、[AnnotationSqlGutterLineMarkerProvider]（@Select 注解）、
 * [CustomDaoHqlGutterLineMarkerProvider]（自定义 DAO HQL）互不冲突——方法名集合天然区分
 * （`query*` 是 Spring JDBC 模板方法名，`createQuery`/`findByQuery` 是 Hibernate / 自定义 DAO）。
 *
 * 注册见 plugin.xml `<codeInsight.lineMarkerProvider language="JAVA" .../>`。
 */
class JdbcTemplateSqlGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // LineMarker 框架只传递 leaf 元素：锚点为 PsiIdentifier（方法名 leaf）。
        if (element !is PsiIdentifier) return null

        // 锚定在方法调用的方法名 PsiIdentifier 上：
        // element = "queryForList" 等方法名 identifier
        // element.parent = PsiReferenceExpression（方法调用的方法名引用）
        // element.parent.parent = PsiMethodCallExpression
        val parent = element.parent as? PsiReferenceExpression ?: return null
        val grandParent = parent.parent as? PsiMethodCallExpression ?: return null

        // 确认 element 是方法名而非 qualifier
        if (grandParent.methodExpression.referenceName != element.text) return null

        // 方法名 ∈ JDBC 模板方法集合（O(1) 判定）
        if (!JdbcTemplateMethodDetector.isJdbcTemplateMethodReference(element.text)) return null

        // T-GutterDedup：接收者必须是 JDBC 模板家族（JdbcTemplate/NamedParameter/DbHelper），
        // 避免通用方法名 `execute` 在 commonDao / getHibernateTemplate() 上误标（重复图标）
        if (PreviewReceiverResolver.classify(grandParent) != PreviewReceiverResolver.Family.JDBC_TEMPLATE) return null

        // T-GutterDedup：第 1 实参须为字符串 SQL（杜绝 execute(callback) 非字符串实参"有图标点击无效"）
        if (!PreviewReceiverResolver.isStringLikeFirstArg(grandParent)) return null

        // 开关关闭时不显示
        if (!gutterEnabled()) return null

        return LineMarkerInfo(
            element,
            element.textRange,
            SQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.jdbcTemplate.tooltip") },
            JdbcTemplateGutterNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.jdbcTemplate.accessible.name") },
        )
    }

    /** 读取 gutter 图标开关 + JDBC 预览开关（测试环境 service 未注册时按默认 true 处理）。 */
    private fun gutterEnabled(): Boolean = runCatching {
        val state = PluginSettings.getInstance().state
        state.showGutterIcon && state.jdbcSqlPreviewEnabled
    }.getOrDefault(true)

    /**
     * 点击处理：在 EDT 捕获锚点元素，转换走后台线程 + ReadAction（EDT 禁止 PSI 慢操作），
     * 结果按显示方式配置展示。
     */
    private object JdbcTemplateGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project: Project = elt.project
            SqlPreviewService.previewAsync(project, {
                val call = JdbcTemplateSqlLocator.locate(elt)
                if (call != null) {
                    JdbcTemplateSqlPreviewService.preview(call, elt)
                } else {
                    null
                }
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }
    }

    companion object {
        private val SQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", JdbcTemplateSqlGutterLineMarkerProvider::class.java)
    }
}
