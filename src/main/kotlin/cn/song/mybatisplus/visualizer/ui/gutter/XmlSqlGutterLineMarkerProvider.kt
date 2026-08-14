package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * XML mapper 语句预览图标（T-M24-04）：`<select|insert|update|delete>` 语句行显示预览图标，
 * 点击弹出动态 SQL 预览（复用 [SqlPreviewService.showResult] 链路）。
 *
 * **性能红线**：快速路径只做 O(1) 语法判定（tag 名 + 父标签名），零 resolve、零遍历、
 * 零转换；tooltip 静态文案；点击转换走 [SqlPreviewService.previewAsync] 后台线程。
 *
 * 注册见 plugin.xml `<codeInsight.lineMarkerProvider language="XML" .../>`。
 */
class XmlSqlGutterLineMarkerProvider : LineMarkerProvider {

    @Suppress("DEPRECATION")
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // LineMarker 必须注册在 leaf 元素：只接受语句标签的 name token（O(1) 判定，零 resolve）
        val token = element as? XmlToken ?: return null
        if (token.tokenType != XmlTokenType.XML_NAME) return null
        val tag = token.parent as? XmlTag ?: return null
        // 语句标签 + 直接父级是 <mapper>（避免非 mapper XML 误显）
        if (tag.name !in STATEMENT_TAGS) return null
        if (tag.parentTag?.name != "mapper") return null
        if (!gutterEnabled()) return null
        return LineMarkerInfo(
            element,
            element.textRange,
            SQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.xml.tooltip") },
            XmlGutterNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
        )
    }

    /** 读取 gutter 图标开关（测试环境 service 未注册时按默认 true 处理）。 */
    private fun gutterEnabled(): Boolean = runCatching {
        PluginSettings.getInstance().state.showGutterIcon
    }.getOrDefault(true)

    /** 点击处理：后台解析 + 渲染（EDT 禁止 PSI 慢操作），结果走统一展示入口。 */
    private object XmlGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project: Project = elt.project
            val tag = elt.parent as? XmlTag ?: return
            val file = tag.containingFile as? XmlFile ?: return
            SqlPreviewService.previewAsync(project, {
                // T-M25.2：传 project 解析 @class@field 静态常量（用户项目常量类字段值）
                XmlPreviewService.preview(file.text, tag.getAttributeValue("id"), project)
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }
    }

    companion object {
        private val SQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", XmlSqlGutterLineMarkerProvider::class.java)

        val STATEMENT_TAGS = setOf("select", "insert", "update", "delete")
    }
}
