package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.ui.highlight.SqlHtmlRenderer
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.psi.PsiElement

/**
 * 悬停预览提供器（M3-04，F-06 实时悬停）。
 *
 * 通过 `documentationProvider` 扩展点注册（见 plugin.xml），IDEA 在光标悬停元素时
 * 调用 [generateDoc]，返回 SQL 文本作为文档提示（类似方法文档弹层）。
 *
 * 行为：从悬停元素定位 Wrapper → [SqlPreviewService.preview] → 返回 HTML 格式化 SQL。
 * 未找到 Wrapper 时返回 null（IDEA 显示默认文档）。
 */
class SqlPreviewDocumentationProvider : AbstractDocumentationProvider() {

    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        val project = element.project ?: return null
        val service = project.getService(SqlPreviewService::class.java)
        val result = service.preview(element) ?: return null

        // 以等宽字体 pre 块返回 SQL；经 SqlHtmlRenderer 做语法高亮（M8-05），颜色随 IDE 主题
        val highlighted = SqlHtmlRenderer.render(result.sql)
        val title = result.wrapperType?.let { MpSqlVisualizerBundle.message("documentation.title", it.name) } ?: ""
        return """
            <div style='font-family: sans-serif; color: #808080; margin-bottom: 4px;'>$title</div>
            <pre style='font-family: JetBrains Mono, monospace; font-size: 12px; margin: 0;'>$highlighted</pre>
        """.trimIndent()
    }
}
