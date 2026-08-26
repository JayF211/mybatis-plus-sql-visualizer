package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.ui.components.JBList
import javax.swing.Icon
import javax.swing.ListCellRenderer

/**
 * 原生数据源选择器（M36，反射隔离）：弹出数据源选择弹窗。
 *
 * 从 [DataSourceSettings.getAllDataSources] 获取已配置数据源列表，
 * 单数据源时直接返回（免弹窗），多数据源时弹出选择弹窗。
 *
 * 反射隔离约定：本类仅当 Database 插件已加载时被调用，Community 下永不加载。
 */
object DataSourceSelector {

    /**
     * 选择数据源。
     *
     * @param project  当前项目
     * @param callback 选择完成回调（参数为 LocalDataSource 反射对象，取消时为 null）
     */
    fun select(project: Project, callback: (Any?) -> Unit) {
        if (!ExecuteSqlSupport.isAvailable()) {
            callback(null)
            return
        }

        val dataSources = DataSourceSettings.getAllDataSources(project)
        when {
            dataSources.isEmpty() -> callback(null)
            dataSources.size == 1 -> callback(dataSources.first())
            else -> showDataSourcePopup(project, dataSources, callback)
        }
    }

    /**
     * 显示数据源选择弹窗（原生 JBPopup）。
     */
    private fun showDataSourcePopup(project: Project, dataSources: List<Any>, callback: (Any?) -> Unit) {
        val items = dataSources.map { ds ->
            DataSourceItem(
                dataSource = ds,
                name = DataSourceSettings.getName(ds),
                icon = DataSourceSettings.getIcon(ds),
            )
        }

        val list = JBList(items)

        val builder = PopupChooserBuilder(list)
            .setTitle(MpSqlVisualizerBundle.message("execute.datasource.select"))
            .setItemChoosenCallback {
                val selected = list.selectedValue?.dataSource
                callback(selected)
            }
            .setNamerForFiltering { it.name }

        builder.createPopup().showInCenterOf(
            com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)?.rootPane ?: return
        )
    }

    /** 弹窗列表项（LocalDataSource 包装）。 */
    private data class DataSourceItem(
        val dataSource: Any,
        val name: String,
        val icon: Icon?,
    ) {
        override fun toString(): String = name
    }
}
