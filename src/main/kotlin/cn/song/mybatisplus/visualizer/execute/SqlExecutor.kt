package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.WindowManager
import java.io.File

/**
 * SQL 执行引擎（M36，反射隔离）：直接执行 SQL 并展示结果。
 *
 * 策略：创建临时 .sql 文件 → 打开原生编辑器（自动关联数据源控制台）→
 * 通过 JdbcConsoleProvider 执行 SQL → 结果以内联方式展示在编辑器下方（IDEA 原生样式）。
 *
 * 反射隔离约定：本类仅当 Database 插件已加载时被调用，Community 下永不加载。
 */
object SqlExecutor {

    /** JdbcConsoleProvider 类名（反射入口）。 */
    private const val CONSOLE_PROVIDER_CLASS = "com.intellij.database.console.JdbcConsoleProvider"

    /** JdbcConsole 类名（反射用）。 */
    private const val CONSOLE_CLASS = "com.intellij.database.console.JdbcConsole"

    /** DatabaseSession 类名（反射用）。 */
    private const val SESSION_CLASS = "com.intellij.database.console.session.DatabaseSession"

    /** DatabaseSettings 类名（反射用）。 */
    private const val DB_SETTINGS_CLASS = "com.intellij.database.settings.DatabaseSettings"

    /**
     * 执行 SQL（核心入口）。
     *
     * 流程：
     *  1. 创建临时 .sql 文件（含当前 SQL）
     *  2. 打开文件编辑器（Database 插件自动关联控制台）
     *  3. 通过 JdbcConsoleProvider 获取/创建控制台
     *  4. 附加数据源（如已选择）
     *  5. 执行 SQL（结果以内联方式展示在编辑器下方）
     *
     * @param project    当前项目
     * @param sql        SQL 文本
     * @param dataSource LocalDataSource 反射对象（可为 null，使用控制台当前数据源）
     * @param isQuery    是否为查询语句
     */
    fun execute(project: Project, sql: String, dataSource: Any?, isQuery: Boolean) {
        runCatching {
            // 1. 创建临时 .sql 文件
            val vf = createTempFile(sql)

            // 2. 打开文件编辑器
            val editorManager = FileEditorManager.getInstance(project)
            editorManager.openFile(vf, true)

            // 3. 获取/创建控制台并执行
            executeInConsole(project, vf, dataSource, sql, isQuery)
        }.onFailure { error ->
            showError(project, MpSqlVisualizerBundle.message("execute.failed", error.message ?: error.toString()))
        }
    }

    /**
     * 通过 JdbcConsoleProvider 在控制台内执行 SQL。
     */
    private fun executeInConsole(project: Project, vf: com.intellij.openapi.vfs.VirtualFile, dataSource: Any?, sql: String, isQuery: Boolean) {
        runCatching {
            val providerClass = Class.forName(CONSOLE_PROVIDER_CLASS)

            // 查找或创建 Session
            val findSessionMethod = providerClass.getMethod(
                "findOrCreateSession",
                Project::class.java,
                com.intellij.openapi.vfs.VirtualFile::class.java,
            )
            val session = findSessionMethod.invoke(null, project, vf)

            if (session == null) {
                showError(project, MpSqlVisualizerBundle.message("execute.failed", "无法创建数据库会话"))
                return
            }

            // 获取控制台
            val getConsoleMethod = providerClass.getMethod(
                "getConsole",
                Project::class.java,
                com.intellij.openapi.vfs.VirtualFile::class.java,
            )
            val console = getConsoleMethod.invoke(null, project, vf)

            if (console == null) {
                // 附加控制台到 Session
                val attachMethod = providerClass.getMethod(
                    "attachConsole",
                    Project::class.java,
                    Class.forName(SESSION_CLASS),
                    com.intellij.openapi.vfs.VirtualFile::class.java,
                )
                val attachedConsole = attachMethod.invoke(null, project, session, vf)
                if (attachedConsole != null) {
                    executeQueries(attachedConsole, sql)
                }
            } else {
                executeQueries(console, sql)
            }
        }
    }

    /**
     * 反射调用 JdbcConsole.executeQueries 执行 SQL。
     */
    private fun executeQueries(console: Any, sql: String) {
        runCatching {
            val consoleClass = Class.forName(CONSOLE_CLASS)
            val executeMethod = consoleClass.getMethod(
                "executeQueries",
                com.intellij.openapi.editor.Editor::class.java,
                    Class.forName("com.intellij.database.script.ScriptModel"),
                    Class.forName("$DB_SETTINGS_CLASS\$ExecOption"),
            )
            // 获取编辑器
            val getDocumentMethod = consoleClass.getMethod("getDocument")
            val document = getDocumentMethod.invoke(console)
            // 获取 ScriptModel
            val getScriptModelMethod = consoleClass.getMethod("getScriptModel")
            val scriptModel = getScriptModelMethod.invoke(console)
            // 获取 ExecOption
            val execOptionClass = Class.forName("$DB_SETTINGS_CLASS\$ExecOption")
            val execOption = execOptionClass.enumConstants?.firstOrNull() as? Enum<*>
            // 获取编辑器
            val editor = com.intellij.openapi.editor.EditorFactory.getInstance()
                .getEditors(document as com.intellij.openapi.editor.Document)
                .firstOrNull()

            if (editor != null && scriptModel != null && execOption != null) {
                executeMethod.invoke(console, editor, scriptModel, execOption)
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
