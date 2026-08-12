package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

/**
 * SQL 预览文件类型（M9-03，代码补全需要 PSI 文件）。
 *
 * 仅用于内存中的预览 PSI 文件（`preview.mpsql`），不参与项目文件系统。
 * 注册见 plugin.xml `<fileType>`。
 */
class SqlPreviewFileType : LanguageFileType(SqlPreviewLanguage) {

    override fun getName(): String = "MP_SQL_Preview"

    override fun getDescription(): String = "MyBatis-Plus SQL Visualizer preview"

    override fun getDefaultExtension(): String = "mpsql"

    override fun getIcon(): Icon? = null
}
