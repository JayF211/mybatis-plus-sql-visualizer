package cn.song.mybatisplus.visualizer.database

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * 数据库执行器（M17 隔离实现，M19 去弹框直建临时文件）。
 *
 * **隔离约定**：本类经 [cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport] **反射**调用——
 * 仅当 Database 插件已加载（Ultimate/DataGrip）时才被 JVM 解析，Community 下永不加载。
 * M19 起已无 `com.intellij.database` 直接引用（纯平台 API），反射调用结构保持不变。
 *
 * 流程（M19，用户实测数据源弹框无内容——`ChooseByNamePopup` 空搜索词且未开启
 * `setShowListForEmptyPattern` 时列表被清空隐藏、元素计算不执行；按用户要求移除选择弹框，
 * 改由 SQL 编辑器**原生数据源选择器**承载）：
 *  1. 临时文件：系统临时目录 `mpsql-exec-*.sql`，写入当前 SQL；
 *  2. 打开编辑器：Ultimate + Database 插件的 SQL 文件编辑器右上角自带数据源选择器，
 *     用户**自行选择数据源**后 Ctrl+Enter 执行（IDE 原生行为）。
 */
object DatabaseConsoleLauncher {

    /**
     * 执行入口（经反射调用）：创建临时 .sql 文件并打开（数据源由用户在编辑器右上角选择）。
     * 任何一步失败 → 错误提示（不抛异常到调用方）。
     */
    @JvmStatic
    fun execute(project: Project, sql: String) {
        runCatching {
            val vf = createTempFile(sql)
            FileEditorManager.getInstance(project).openFile(vf, true)
            showInfo(project, MpSqlVisualizerBundle.message("execute.done"))
        }.onFailure { error ->
            showError(project, MpSqlVisualizerBundle.message("execute.failed", error.message ?: error.toString()))
        }
    }

    /**
     * 创建临时 .sql 文件（系统临时目录）并写入 SQL，返回其虚拟文件。
     * internal 供测试（测试沙盒无 database 插件，仅编译期可引用）。
     */
    internal fun createTempFile(sql: String): VirtualFile {
        val ioFile = FileUtil.createTempFile("mpsql-exec-", ".sql", true)
        FileUtil.writeToFile(ioFile, sql)
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            ?: error("临时文件创建失败: ${ioFile.absolutePath}")
    }

    private fun showInfo(project: Project, message: String) {
        runCatching { Messages.showInfoMessage(project, message, MpSqlVisualizerBundle.message("execute.title")) }
    }

    private fun showError(project: Project, message: String) {
        runCatching { Messages.showErrorDialog(project, message, MpSqlVisualizerBundle.message("execute.title")) }
    }
}
