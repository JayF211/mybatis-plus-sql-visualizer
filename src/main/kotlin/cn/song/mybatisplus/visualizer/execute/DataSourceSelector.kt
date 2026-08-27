package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.components.JBList
import javax.swing.Icon

/**
 * 原生数据源选择器（M36，反射隔离）：用 Database 插件原生 Runner 选择数据源并直接执行。
 *
 * 通过 [com.intellij.database.console.DatabaseRunners.getAttachDataSourceRunners] 获取
 * 数据源 Runner 列表——与 IDEA 原生 Ctrl+Enter 未绑定文件时弹出的数据源列表**同源**
 * （每个 Runner 封装数据源名称/图标/"在该数据源上打开控制台执行 SQL"的完整逻辑）。
 * 选中后 [Runner.run] 直接执行，结果在打开的控制台下方原生展示——无需手动 Ctrl+Enter。
 *
 * **反射关键**：`DatabaseRunners$MyRunner` 是**包私有类**，跨包反射 invoke 会抛
 * `IllegalAccessException`——所有方法调用前必须 `isAccessible = true`
 * （此前 "Unknown" 数据源名 / 选中无响应均由此引起）。
 *
 * 反射隔离约定：本类仅当 Database 插件已加载时被调用，Community 下永不加载。
 */
object DataSourceSelector {

    /** DatabaseRunners 类名（反射入口）。 */
    private const val RUNNERS_CLASS = "com.intellij.database.console.DatabaseRunners"

    /**
     * 选择数据源并执行 SQL。
     *
     * 流程：
     *  1. 创建临时 .sql 文件 + PsiFile（仅作 Runner 上下文，不打开编辑器）
     *  2. 反射调用 getAttachDataSourceRunners 获取原生 Runner 列表
     *  3. 无 Runner → 打开 Database 工具窗口引导配置数据源
     *  4. 单 Runner 且 shouldBeChosenIfAlone → 直接执行（原生免选行为）
     *  5. 多 Runner → 弹窗展示（图标+名称，原生列表样式），选中即执行
     *
     * @param project  当前项目
     * @param sql      SQL 文本
     * @param callback 完成回调（true=已触发执行；false=不可用/无数据源/取消）
     */
    fun selectAndExecute(project: Project, sql: String, callback: ((Boolean) -> Unit)? = null) {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        if (!ExecuteSqlSupport.isAvailable()) {
            LOG.warn("[M36] database plugin NOT available, abort")
            callback?.invoke(false)
            return
        }

        val psiFile = createTempPsiFile(project, sql)
        if (psiFile == null) {
            LOG.warn("[M36] createTempPsiFile failed, abort")
            callback?.invoke(false)
            return
        }

        val runners = getAttachDataSourceRunners(psiFile, sql)
        LOG.info("[M36] runners=${runners.size}, types=${runners.map { it.javaClass.name }}")
        when {
            runners.isEmpty() -> {
                // 无已配置数据源：打开 Database 工具窗口引导用户配置
                LOG.warn("[M36] runners empty -> open Database tool window")
                openDatabaseToolWindow(project)
                callback?.invoke(false)
            }
            runners.size == 1 && shouldBeChosenIfAlone(runners.first()) -> {
                // 单数据源且原生标记免选：直接执行
                LOG.info("[M36] single runner, run directly")
                runRunner(runners.first())
                callback?.invoke(true)
            }
            else -> {
                LOG.info("[M36] show popup with ${runners.size} runners")
                showRunnerPopup(project, runners) { runner ->
                    LOG.info("[M36] popup selected: ${runner?.let { getRunnerName(it) }}")
                    callback?.invoke(runner != null)
                    if (runner != null) runRunner(runner)
                }
            }
        }
    }

    /**
     * 反射调用 DatabaseRunners.getAttachDataSourceRunners 获取原生 Runner 列表。
     */
    private fun getAttachDataSourceRunners(psiFile: PsiFile, sql: String): List<Any> {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        return runCatching {
            val clazz = Class.forName(RUNNERS_CLASS)
            val method = clazz.getMethod(
                "getAttachDataSourceRunners",
                PsiFile::class.java,
                String::class.java,
                com.intellij.util.Consumer::class.java,
            )
            // Consumer<DatabaseSession>：Runner 执行后数据源附加完成时回调（此处无需处理）
            @Suppress("UNCHECKED_CAST")
            val result = method.invoke(null, psiFile, sql, com.intellij.util.Consumer<Any> { })
            (result as? List<*>)?.filterNotNull() ?: emptyList()
        }.onFailure { e ->
            LOG.warn("[M36] getAttachDataSourceRunners reflection FAILED: $e", e)
        }.getOrDefault(emptyList())
    }

    /** 反射获取 Runner 显示名称（数据源名，如 `jpa_demo@localhost`）。 */
    private fun getRunnerName(runner: Any): String = runCatching {
        val method = runner.javaClass.getMethod("getDisplayName")
        method.isAccessible = true
        method.invoke(runner) as? String
    }.getOrNull() ?: "Unknown"

    /** 反射获取 Runner 图标（数据源 DB 类型图标）。 */
    private fun getRunnerIcon(runner: Any): Icon? = runCatching {
        val method = runner.javaClass.getMethod("getIcon")
        method.isAccessible = true
        method.invoke(runner) as? Icon
    }.getOrNull()

    /** 反射调用 Runner.shouldBeChosenIfAlone（单数据源时原生是否免选直接执行）。 */
    private fun shouldBeChosenIfAlone(runner: Any): Boolean = runCatching {
        val method = runner.javaClass.getMethod("shouldBeChosenIfAlone")
        method.isAccessible = true
        method.invoke(runner) as? Boolean
    }.getOrNull() ?: false

    /** 反射调用 Runner.run：打开该数据源的控制台并执行 SQL（结果原生展示）。 */
    private fun runRunner(runner: Any) {
        runCatching {
            val method = runner.javaClass.getMethod("run")
            method.isAccessible = true
            method.invoke(runner)
        }
    }

    /**
     * 弹窗展示 Runner 列表（图标 + 数据源名称，原生 BaseListPopupStep 单击即选）。
     * 取消/关闭弹窗回调 null。
     */
    private fun showRunnerPopup(project: Project, runners: List<Any>, callback: (Any?) -> Unit) {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        val items = runners.map { RunnerItem(it, getRunnerName(it), getRunnerIcon(it)) }
        val step = object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<RunnerItem>(
            MpSqlVisualizerBundle.message("execute.datasource.select"),
            items,
        ) {
            override fun getTextFor(value: RunnerItem): String = value.name
            override fun getIconFor(value: RunnerItem): Icon? = value.icon
            override fun onChosen(selectedValue: RunnerItem?, finalChoice: Boolean): com.intellij.openapi.ui.popup.PopupStep<*>? {
                LOG.info("[M36] popup onChosen: ${selectedValue?.name}")
                callback(selectedValue?.runner)
                return com.intellij.openapi.ui.popup.PopupStep.FINAL_CHOICE
            }
        }
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createListPopup(step)
            .showInFocusCenter()
    }

    /** 弹窗列表项（Runner 包装）。 */
    private data class RunnerItem(val runner: Any, val name: String, val icon: Icon?)

    /**
     * 创建临时 .sql 文件 + PsiFile（仅作 Runner 上下文，不打开编辑器）。
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

    /** 打开 Database 工具窗口（无配置数据源时引导用户）。 */
    private fun openDatabaseToolWindow(project: Project) {
        runCatching {
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("Database")
                ?.show()
        }
    }
}
