package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlMapping
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * hbm.xml 命名查询预览图标（T-H2，20260907-1630）：`<hibernate-mapping>` 下的
 * `<query>`（HQL）/ `<sql-query>`（原生 SQL）语句行显示预览图标，点击弹出 SQL 预览。
 *
 * **性能红线**：快速路径只做 O(1) 语法判定（tag 名 + 父标签名），零 resolve、零遍历；
 * 点击转换走 [SqlPreviewService.previewAsync] 后台线程。
 *
 * **去重保证**：与 [XmlSqlGutterLineMarkerProvider]（要求父标签 `mapper` 的
 * select/insert/update/delete）父标签名互斥，天然不重复。
 *
 * 注册见 plugin.xml `<codeInsight.lineMarkerProvider language="XML" .../>`。
 */
class HbmNamedQueryGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // LineMarker 必须注册在 leaf 元素：语句标签的 name token（O(1) 判定，零 resolve）
        val token = element as? XmlToken ?: return null
        if (token.tokenType != XmlTokenType.XML_NAME) return null
        val tag = token.parent as? XmlTag ?: return null
        if (tag.name !in QUERY_TAGS) return null
        if (tag.parentTag?.name != "hibernate-mapping") return null
        if (tag.getAttributeValue("name") == null) return null
        // F36（T-PerfSweep16）：单次取 state，总闸与组显隐共用（原 gutterEnabled + enabledFor
        // 双路径每到达元素 3 次 app 级查找收敛为 1 次）
        val gutterState = runCatching { PluginSettings.getInstance().state }.getOrNull()
        if (gutterState != null && !gutterState.showGutterIcon) return null
        if (!GutterGroups.enabledFor(javaClass, gutterState)) return null // R2 分组显隐
        return LineMarkerInfo(
            element,
            element.textRange,
            SQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.hbmNamedQuery.tooltip") },
            HbmNamedQueryNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.hbmNamedQuery.accessible.name") },
        )
    }

    /** 点击处理：按查询种类分派 HQL / 原生 SQL 转换（后台线程），结果走统一展示入口。 */
    private object HbmNamedQueryNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project: Project = elt.project
            val tag = elt.parent as? XmlTag ?: return
            val service = project.getService(SqlPreviewService::class.java)
            SqlPreviewService.previewAsync(project, {
                service.previewHbmNamedQueryTag(project, tag)
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }
    }

    companion object {
        private val SQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", HbmNamedQueryGutterLineMarkerProvider::class.java)

        /** 认领的命名查询标签名（值 = hbm QueryKind）。 */
        val QUERY_TAGS = setOf("query", "sql-query")

        /** 从 hbm 标签名推断查询种类（gutter 点击复用）。 */
        fun kindOf(tagName: String): HbmXmlMapping.QueryKind? = when (tagName) {
            "query" -> HbmXmlMapping.QueryKind.HQL
            "sql-query" -> HbmXmlMapping.QueryKind.NATIVE
            else -> null
        }
    }
}
