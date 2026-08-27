package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.ui.components.JBList

/**
 * 原生数据源选择器（M36，反射隔离）：枚举真实数据源 → 原生风格弹窗选择 → 自动执行。
 *
 * 从 [com.intellij.database.psi.DataSourceManager] 获取用户配置的真实数据源
 * （DbDataSource），用原生风格弹窗展示名称+图标。选中后反射调用
 * [com.intellij.database.console.DatabaseRunners.runInDataSource] 执行——
 * 打开的 Database 编辑器下方原生展示查询结果。
 *
 * 反射隔离约定：本类仅当 Database 插件已加载时被调用，Community 下永不加载。
 */
object DataSourceSelector {

    /** DataSourceManager 类名（反射入口，获取 DbDataSource 列表）。 */
    private const val MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager"

    /** DatabaseRunners 类名（反射入口，执行 SQL）。 */
    private const val RUNNERS_CLASS = "com.intellij.database.console.DatabaseRunners"

    /**
     * 选择数据源并执行 SQL（原生弹窗）。
     *
     * 流程：
     *  1. 枚举 DataSourceManager 的真实数据源（DbDataSource）
     *  2. 无数据源 → 打开 Database 工具窗口引导配置
     *  3. 单数据源 → 直接执行
     *  4. 多数据源 → 原生风格弹窗展示，选中后执行
     *
     * @param project  当前项目
     * @param sql      SQL 文本
     * @param callback 执行完成回调（成功 true；不可用/无数据源/取消/失败为 false）
     */
    fun selectAndExecute(project: Project, sql: String, callback: ((Boolean) -> Unit)? = null) {
        if (!ExecuteSqlSupport.isAvailable()) {
            callback?.invoke(false)
            return
        }

        val dataSources = getDbDataSources(project)
        when {
            dataSources.isEmpty() -> {
                openDatabaseToolWindow(project)
                callback?.invoke(false)
            }
            dataSources.size == 1 -> {
                runInDataSource(project, dataSources.first(), sql)
                callback?.invoke(true)
            }
            else -> {
                showDataSourcePopup(project, dataSources, sql) { ds ->
                    if (ds != null) {
                        runInDataSource(project, ds, sql)
                        callback?.invoke(true)
                    } else {
                        callback?.invoke(false)
                    }
                }
            }
        }
    }

    /**
     * 反射枚举 DataSourceManager 的所有数据源（DbDataSource）。
     */
    private fun getDbDataSources(project: Project): List<Any> {
        return runCatching {
            val managerClass = Class.forName(MANAGER_CLASS)
            val managersMethod = managerClass.getMethod("getManagers", Project::class.java)
            val managers = managersMethod.invoke(null, project) as? List<*> ?: return emptyList()
            val dsList = mutableListOf<Any>()
            for (m in managers) {
                val getDataSources = m?.javaClass?.getMethod("getDataSources") ?: continue
                val list = getDataSources.invoke(m) as? List<*> ?: continue
                dsList.addAll(list.mapNotNull { it })
            }
            dsList
        }.getOrDefault(emptyList())
    }

    /**
     * 反射调用 DatabaseRunners.runInDataSource 在指定数据源上执行 SQL。
     */
    private fun runInDataSource(project: Project, ds: Any, sql: String) {
        runCatching {
            val runnersClass = Class.forName(RUNNERS_CLASS)
            val dbDataSourceClass = Class.forName("com.intellij.database.psi.DbDataSource")
            val method = runnersClass.getMethod(
                "runInDataSource",
                dbDataSourceClass,
                String::class.java,
                com.intellij.util.Consumer::class.java,
            )
            method.invoke(null, ds, sql, com.intellij.util.Consumer<Any> { })
        }.onFailure { /* 执行失败由 Database 插件内部提示 */ }
    }

    /**
     * 反射获取 DbDataSource 显示名称（getName()）。
     */
    private fun getDataSourceName(ds: Any): String {
        return runCatching {
            val getNameMethod = ds.javaClass.getMethod("getName")
            getNameMethod.invoke(ds) as? String ?: "Unknown"
        }.getOrDefault("Unknown")
    }

    /**
     * 反射获取 DbDataSource 图标。
     */
    private fun getDataSourceIcon(ds: Any): javax.swing.Icon? {
        return runCatching {
            val getIconMethod = ds.javaClass.getMethod("getIcon")
            getIconMethod.invoke(ds) as? javax.swing.Icon
        }.getOrNull()
    }

    /**
     * 用原生风格弹窗展示数据源列表（DbDataSource）。
     */
    private fun showDataSourcePopup(project: Project, dataSources: List<Any>, sql: String, callback: (Any?) -> Unit) {
        val items = dataSources.map { ds ->
            DataSourceItem(ds, getDataSourceName(ds), getDataSourceIcon(ds))
        }
        val list = JBList(items)
        val builder = PopupChooserBuilder(list)
            .setTitle(MpSqlVisualizerBundle.message("execute.datasource.select"))
            .setItemChoosenCallback {
                callback(list.selectedValue?.dataSource)
            }
            .setNamerForFiltering { it.name }

        builder.createPopup().showCenteredInCurrentWindow(project)
    }

    /**
     * 打开 Database 工具窗口（无配置数据源时引导用户）。
     */
    private fun openDatabaseToolWindow(project: Project) {
        runCatching {
            val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("Database")
            if (toolWindow != null) toolWindow.show()
        }
    }

    /** 弹窗列表项（DbDataSource 包装）。 */
    private data class DataSourceItem(
        val dataSource: Any,
        val name: String,
        val icon: javax.swing.Icon?,
    ) {
        override fun toString(): String = name
    }
}