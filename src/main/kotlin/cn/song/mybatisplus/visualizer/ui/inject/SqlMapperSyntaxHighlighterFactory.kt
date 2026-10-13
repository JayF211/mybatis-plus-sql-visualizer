package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * T-XmlSynHi D1：注入语言的高亮器工厂（plugin.xml `lang.syntaxHighlighterFactory` 注册）。
 *
 * **EP 名必须写限定形态**（`lang.syntaxHighlighterFactory`，限定名
 * `com.intellij.lang.syntaxHighlighterFactory`）：裸 `<syntaxHighlighterFactory>` 会被平台按
 * 未知 EP 静默忽略，本工厂永不实例化 → 注入片段整段无色（20261012 定位的「SQL 文本整段白」
 * 真根因；守卫见 `config/PluginXmlExtensionPointResolutionTest`）。
 */
class SqlMapperSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        SqlMapperSyntaxHighlighter()
}
