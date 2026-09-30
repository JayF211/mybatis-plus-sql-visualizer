package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTFactory
import com.intellij.lang.ASTNode
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.PlainTextParserDefinition
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
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

        /**
         * T-PerfSweep16 修复：片段末尾边界（offset == textLength）也返回叶子。
         *
         * 平台把光标落在注入区间内（含两端边界，243 实测）的编辑器路由成 EditorWindow，此后
         * 「caret 处元素」类查询全部按片段坐标进行；EOF 是合法编辑位（语句文本最后一字符之后），
         * 单叶文件默认在该位答 null，令以 caret 定位的下游链（补全参数构建等）无从起步。
         * 越界（offset > textLength）仍为 null，不放宽其他边界语义。
         */
        override fun findElementAt(offset: Int): PsiElement? {
            if (offset == textLength && textLength > 0) {
                return super.findElementAt(textLength - 1)
            }
            return super.findElementAt(offset)
        }
    }

    companion object {
        private val FILE_ELEMENT_TYPE = object : IFileElementType(SqlMapperLanguage) {
            override fun parseContents(chameleon: ASTNode): ASTNode =
                ASTFactory.leaf(PlainTextTokenTypes.PLAIN_TEXT, chameleon.chars)
        }

        /** 当前显式注册实例（进程内一次；卸载摘除/测试清理按同实例 identity 摘）。 */
        @Volatile
        private var registered: SqlMapperParserDefinition? = null

        /**
         * 编程式注册（进程内一次，幂等；注册表已在位即跳过）。
         *
         * F48（T-PerfQuick5）：注册挂 [SqlMapperInjectionBarrierHost]（插件应用级宿主）为
         * Disposer parent——243 平台 KeyedExtensionCollector.addExplicitExtension 三参重载
         * （V39 源码实证）内部 `Disposer.register(parent) { removeExplicitExtension(key, t) }`，
         * 动态卸载容器级联即摘除，旧 classloader 不再被注册表钉住；摘除路径已登记
         * [cn.song.mybatisplus.visualizer.update.BarrierRegistry]。测试环境无应用容器时
         * 退回两参裸注册（无卸载语义，测试自清理）。
         */
        @Synchronized
        fun ensureRegistered() {
            val registry = com.intellij.lang.LanguageParserDefinitions.INSTANCE
            if (registry.forLanguage(SqlMapperLanguage) != null) return
            val def = SqlMapperParserDefinition()
            val host = runCatching {
                com.intellij.openapi.application.ApplicationManager.getApplication()
                    ?.getService(SqlMapperInjectionBarrierHost::class.java)
            }.getOrNull()
            if (host != null) {
                registry.addExplicitExtension(SqlMapperLanguage, def, host)
            } else {
                registry.addExplicitExtension(SqlMapperLanguage, def)
            }
            registered = def
        }

        /** 测试缝：显式摘除（V39 运行时证据——removeExplicitExtension 对显式注册真实生效）。 */
        @Synchronized
        fun unregisterForTest() {
            registered?.let {
                com.intellij.lang.LanguageParserDefinitions.INSTANCE
                    .removeExplicitExtension(SqlMapperLanguage, it)
            }
            registered = null
        }
    }
}
