package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTFactory
import com.intellij.lang.ASTNode
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.PlainTextParserDefinition
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.PlainTextTokenTypes
import com.intellij.psi.tree.IFileElementType

/**
 * T-PerfSweep16 F37 伴随发现：[SqlMapperLanguage] 的 ParserDefinition（lexer-only 语言的落地件）。
 *
 * 缘起：243 平台 `InjectionRegistrarImpl.parseFile` 对注入语言缺失 ParserDefinition 直接
 * assert 失败（源码 jar 实证）——注入器从死代码接线为活代码后必须补上，否则首次注入即崩。
 *
 * 形态：根元素按整段单叶（PLAIN_TEXT）解析、不产语法树——着色走 syntaxHighlighterFactory
 * 的 [SqlMapperSyntaxHighlighter]，词法即 [SqlMapperHighlightingLexer]。
 *
 * 关键坑（真红实证）：不能复用 PlainTextParserDefinition.createFile 的 PsiPlainTextFileImpl——
 * 其语言是 PlainTextLanguage 而 viewProvider baseLanguage 是 MpSqlMapper，InjectionRegistrarImpl
 * 按 `psiFile.getLanguage() == viewProvider.getBaseLanguage()` 决定是否进注入结果，不等则
 * 整个注入静默丢弃（visits=0）。故 createFile 自建 [SqlMapperFile]（语言=MpSqlMapper）。
 *
 * 注册不走 plugin.xml EP（本轮 EP 声明冻结），由 [SqlMapperInjector] 首次注入前编程注册
 * （进程内一次）。
 */
class SqlMapperParserDefinition : PlainTextParserDefinition() {

    override fun getFileNodeType(): IFileElementType = FILE_ELEMENT_TYPE

    override fun createFile(viewProvider: FileViewProvider): PsiFile = SqlMapperFile(viewProvider)

    /** 语言=MpSqlMapper 的单叶 PSI 文件（叶子形态承 PlainTextParserDefinition，语言必须=注入语言）。 */
    private class SqlMapperFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, SqlMapperLanguage) {
        override fun getFileType(): FileType = PlainTextFileType.INSTANCE
        override fun toString(): String = "MpSqlMapper:" + name
    }

    private companion object {
        private val FILE_ELEMENT_TYPE = object : IFileElementType(SqlMapperLanguage) {
            override fun parseContents(chameleon: ASTNode): ASTNode =
                ASTFactory.leaf(PlainTextTokenTypes.PLAIN_TEXT, chameleon.chars)
        }
    }
}
