package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.components.JBList

/**
 * 原生数据源选择器（M36，反射隔离）：用 Database 插件原生数据源弹窗选择并执行。
 *
 * 通过 [com.intellij.database.console.DatabaseRunners.getAttachDataSourceRunners] 获取
 * 数据源 Runner 列表（每个 Runner 封装"选中该数据源后打开编辑器执行"的逻辑）。
 * 用原生风格弹窗展示数据源列表，用户选中后 [Runner.run] 自动执行——
 * 打开的 Database 编辑器下方原生展示查询结果。
 *
 * 反射隔离约定：本类仅当 Database 插件已加载时被调用，Community 下永不加载。
 */
object DataSourceSelector {

    /** DatabaseRunners 类名（反射入口）。 */
    private const val RUNNERS_CLASS = "com.intellij.database.console.DatabaseRunners"

    /**
     * 选择数据源并执行 SQL（原生弹窗）。
     *
     * 流程：
     *  1. 创建临时 .sql 文件 + PsiFile（仅作 context，不打开编辑器）
     *  2. 反射调用 getAttachDataSourceRunners 获取 Runner 列表
     *  3. 用原生风格弹窗展示 Runner（数据源列表）
     *  4. 选中后 runner.run() 自动执行（打开编辑器 + 原生结果）
     *
     * @param project  当前项目
     * @param sql      SQL 文本
     * @param callback 执行完成回调（可选；数据源不可用/失败时为 null）
     */
    fun selectAndExecute(project: Project, sql: String, callback: ((Boolean) -> Unit)? = null) {
        if (!ExecuteSqlSupport.isAvailable()) {
            callback?.invoke(false)
            return
        }

        // 创建临时 .sql 文件 + PsiFile（context，不打开编辑器）
        val psiFile = createTempPsiFile(project, sql)
        if (psiFile == null) {
            callback?.invoke(false)
            return
        }

        // 反射调用 getAttachDataSourceRunners 获取 Runner 列表
        val runners = getAttachDataSourceRunners(project, psiFile, sql)
        when {
            runners.isEmpty() -> callback?.invoke(false)
            runners.size == 1 -> {
                // 单数据源：直接执行
                runRunner(runners.first())
                callback?.invoke(true)
            }
            else -> {
                // 多数据源：原生风格弹窗展示，选中后执行
                showRunnerPopup(project, runners) { runner ->
                    if (runner != null) {
                        runRunner(runner)
                        callback?.invoke(true)
                    } else {
                        callback?.invoke(false)
                    }
                }
            }
        }
    }

    /**
     * 反射调用 DatabaseRunners.getAttachDataSourceRunners 获取 Runner 列表。
     * 返回的 Runner 为反射对象（PersistenceConsoleProvider$Runner），
     * 可调用 getDisplayName()/getIcon() 展示，run() 执行。
     */
    private fun getAttachDataSourceRunners(project: Project, psiFile: PsiFile, sql: String): List<Any> {
        return runCatching {
            val runnersClass = Class.forName(RUNNERS_CLASS)
            val method = runnersClass.getMethod(
                "getAttachDataSourceRunners",
                PsiFile::class.java,
                String::class.java,
                com.intellij.util.Consumer::class.java,
            )
            // 注意：此处 callback 为执行时触发（选中数据源后），非弹窗时。
            // getAttachDataSourceRunners 返回 Runner 列表本身。
            @Suppress("UNCHECKED_CAST")
            val result = method.invoke(null, psiFile, sql, com.intellij.util.Consumer<Any> { })
            (result as? List<*>)?.filterNotNull() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * 反射获取 Runner 显示名称。
     */
    private fun getRunnerName(runner: Any): String {
        return runCatching {
            val method = runner.javaClass.getMethod("getDisplayName")
            method.invoke(runner) as? String ?: "Unknown"
        }.getOrDefault("Unknown")
    }

    /**
     * 反射获取 Runner 图标。
     */
    private fun getRunnerIcon(runner: Any): javax.swing.Icon? {
        return runCatching {
            val method = runner.javaClass.getMethod("getIcon")
            method.invoke(runner) as? javax.swing.Icon
        }.getOrNull()
    }

    /**
     * 反射调用 Runner.run 执行。
     */
    private fun runRunner(runner: Any) {
        runCatching {
            val runMethod = runner.javaClass.getMethod("run")
            runMethod.invoke(runner)
        }.onFailure { /* 执行失败由 Database 插件内部提示 */ }
    }

    /**
     * 用原生风格弹窗展示 Runner 列表（数据源选择）。
     */
    private fun showRunnerPopup(project: Project, runners: List<Any>, callback: (Any?) -> Unit) {
        val items = runners.map { RunnerItem(it, getRunnerName(it), getRunnerIcon(it)) }
        val list = JBList(items)
        val builder = PopupChooserBuilder(list)
            .setTitle(MpSqlVisualizerBundle.message("execute.datasource.select"))
            .setItemChoosenCallback {
                callback(list.selectedValue?.runner)
            }
            .setNamerForFiltering { it.name }

        builder.createPopup().showCenteredInCurrentWindow(project)
    }

    /**
     * 创建临时 .sql 文件 + PsiFile（仅作 context，不打开编辑器）。
     */
    private fun createTempPsiFile(project: Project, sql: String): PsiFile? {
        return runCatching {
            val ioFile = FileUtil.createTempFile("mpsql-select-", ".sql", true)
            FileUtil.writeToFile(ioFile, sql)
            ioFile.deleteOnExit()
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile) ?: return null
            PsiManager.getInstance(project).findFile(vf)
        }.getOrNull()
    }

    /** 弹窗列表项（Runner 包装）。 */
    private data class RunnerItem(
        val runner: Any,
        val name: String,
        val icon: javax.swing.Icon?,
    ) {
        override fun toString(): String = name
    }
}