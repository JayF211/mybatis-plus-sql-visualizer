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

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DatabaseConsoleLauncher::class.java)

    /**
     * 执行入口（经反射调用）：创建临时 .sql 文件并打开（数据源由用户在编辑器右上角选择）。
     * 任何一步失败 → 错误提示（不抛异常到调用方）。
     *
     * T-M23.6：成功提示提供「关闭 SQL 面板」按钮——`onClosePanel` 非空时点击触发
     * （经反射由 ExecuteSqlSupport 传入，闭包内回调预览弹窗 dispose）。
     */
    @JvmStatic
    fun execute(project: Project, sql: String, onClosePanel: Runnable? = null) {
        runCatching {
            val vf = createTempFile(sql)
            FileEditorManager.getInstance(project).openFile(vf, true)
            showInfo(project, MpSqlVisualizerBundle.message("execute.done"), onClosePanel)
        }.onFailure { error ->
            // T-Perf-01：临时文件/打开编辑器失败改 LOG.warn 静默降级，不再弹模态框（低频异常路径，
            // 用户可从日志定位；原生 Database 编辑器自身的错误仍由平台提示）
            LOG.warn("[M17] launcher execute FAILED: ${error.message}", error)
        }
    }

    /**
     * 创建临时 .sql 文件（系统临时目录）并写入 SQL，返回其虚拟文件。
     * internal 供测试（测试沙盒无 database 插件，仅编译期可引用）。
     */
    internal fun createTempFile(sql: String): VirtualFile {
        val ioFile = createTempFile()
        writeSqlToFile(ioFile, sql)
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            ?: error("临时文件创建失败: ${ioFile.absolutePath}")
    }

    /**
     * 创建空的临时 .sql 文件（系统临时目录，测试缝）：纯平台 API，不依赖 Database 插件。
     * 文件名格式 `mpsql-exec-*.sql`。
     *
     * @return 创建的临时文件
     */
    internal fun createTempFile(): java.io.File = FileUtil.createTempFile("mpsql-exec-", ".sql", true)

    /**
     * 将 SQL 文本写入指定文件（测试缝）：纯平台 I/O，不依赖 Database 插件。
     *
     * @param file 目标文件
     * @param sql  SQL 文本
     */
    internal fun writeSqlToFile(file: java.io.File, sql: String) {
        FileUtil.writeToFile(file, sql)
    }

    /**
     * T-M23.6：成功提示改多按钮——`[确定, 关闭 SQL 面板]`，点「关闭 SQL 面板」（index 1）
     * → 触发 [onClosePanel]（预览弹窗直接关闭）；失败提示维持单按钮（用户需留在面板修改 SQL）。
     */
    private fun showInfo(project: Project, message: String, onClosePanel: Runnable?) {
        runCatching {
            if (onClosePanel == null) {
                Messages.showInfoMessage(project, message, MpSqlVisualizerBundle.message("execute.title"))
            } else {
                val options = arrayOf(
                    MpSqlVisualizerBundle.message("dialog.ok"),
                    MpSqlVisualizerBundle.message("dialog.closeSqlPanel"),
                )
                val choice = Messages.showDialog(
                    project, message, MpSqlVisualizerBundle.message("execute.title"), options, 0, Messages.getInformationIcon(),
                )
                if (choice == 1) onClosePanel.run()
            }
        }
    }
}