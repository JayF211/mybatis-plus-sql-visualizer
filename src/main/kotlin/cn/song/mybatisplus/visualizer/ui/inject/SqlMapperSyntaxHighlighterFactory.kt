package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** T-XmlSynHi D1：注入语言的高亮器工厂（plugin.xml syntaxHighlighterFactory 注册）。 */
class SqlMapperSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        SqlMapperSyntaxHighlighter()
}
