package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider

/**
 * SQL 预览 PSI 文件（M9-03）。
 *
 * 仅供内存中的预览编辑器使用（无物理文件），语言为 [SqlPreviewLanguage]，
 * 挂 [SqlCompletionContributor] 提供补全。
 */
class SqlPsiFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, SqlPreviewLanguage) {

    override fun getFileType(): FileType = SqlPreviewFileType()

    override fun toString(): String = "SqlPreviewFile"
}
