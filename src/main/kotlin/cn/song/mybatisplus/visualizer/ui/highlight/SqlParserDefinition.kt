package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

/**
 * SQL 预览语言的 ParserDefinition（M9-03）。
 *
 * 最小实现：不构建语法语义树，仅让语言可生成 PSI 文件（补全基础设施依赖 PSI）。
 * 解析策略：整个文件一个根节点 + 逐 token 叶子，供补全器按位置触发。
 * 注册见 plugin.xml `<lang.parserDefinition>`。
 */
class SqlParserDefinition : ParserDefinition {

    override fun createLexer(project: Project?): Lexer = SqlLexer()

    override fun createParser(project: Project?): PsiParser = SqlPsiParser()

    override fun getFileNodeType(): IFileElementType = FILE_ELEMENT_TYPE

    override fun getCommentTokens(): TokenSet = TokenSet.create(SqlTokenTypes.COMMENT)

    override fun getStringLiteralElements(): TokenSet = TokenSet.create(SqlTokenTypes.STRING)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = SqlPsiFile(viewProvider)

    override fun createElement(node: ASTNode): PsiElement = LeafPsiElement(node.elementType, node.chars)

    override fun spaceExistenceTypeBetweenTokens(left: ASTNode, right: ASTNode): ParserDefinition.SpaceRequirements =
        ParserDefinition.SpaceRequirements.MAY

    companion object {
        val FILE_ELEMENT_TYPE: IFileElementType = IFileElementType(SqlPreviewLanguage)
    }
}

/** 最小解析器：一个根节点，token 直接作为叶子（不建复合节点）。 */
class SqlPsiParser : PsiParser {

    override fun parse(root: IElementType, builder: com.intellij.lang.PsiBuilder): ASTNode {
        val marker = builder.mark()
        while (!builder.eof()) {
            builder.advanceLexer()
        }
        marker.done(root)
        return builder.treeBuilt
    }
}
