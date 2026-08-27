package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * SQL 执行引擎（M36，反射隔离）：创建临时 .sql 文件并打开原生编辑器。
 *
 * 策略：创建临时 .sql 文件 → 打开编辑器（Database 插件自动关联数据源控制台）→
 * 提示用户 Ctrl+Enter 执行 → 结果以内联方式展示在编辑器下方（IDEA 原生样式）。
 *
 * 反射隔离约定：本类仅当 Database 插件已加载时被调用，Community 下永不加载。
 */
object SqlExecutor {

    /**
     * 执行 SQL（核心入口）。
     *
     * @param project    当前项目
     * @param sql        SQL 文本
     * @param dataSource LocalDataSource 反射对象（用于记录，不直接用于执行）
     * @param isQuery    是否为查询语句
     */
    fun execute(project: Project, sql: String, dataSource: Any?, isQuery: Boolean) {
        // 文件操作必须在 EDT 上执行
        ApplicationManager.getApplication().invokeAndWait {
            runCatching {
                val vf = createTempFile(sql)
                FileEditorManager.getInstance(project).openFile(vf, true)
            }.onFailure { error ->
                showError(project, MpSqlVisualizerBundle.message("execute.failed", error.message ?: error.toString()))
            }
        }
    }

    /**
     * 创建临时 .sql 文件并写入 SQL。
     */
    private fun createTempFile(sql: String): com.intellij.openapi.vfs.VirtualFile {
        val ioFile = FileUtil.createTempFile("mpsql-exec-", ".sql", true)
        FileUtil.writeToFile(ioFile, sql)
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            ?: error("临时文件创建失败: ${ioFile.absolutePath}")
    }

    private fun showError(project: Project, message: String) {
        runCatching {
            com.intellij.openapi.ui.Messages.showErrorDialog(project, message, MpSqlVisualizerBundle.message("execute.title"))
        }
    }
}
