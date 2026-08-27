package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 原生数据源选择器（M36，反射隔离）：弹出数据源选择对话框。
 *
 * 从 [DataSourceSettings.getAllDataSources] 获取已配置数据源列表，
 * 单数据源时直接返回（免弹窗），多数据源时弹出选择对话框（带确定/取消按钮）。
 *
 * 反射隔离约定：本类仅当 Database 插件已加载时被调用，Community 下永不加载。
 */
object DataSourceSelector {

    /**
     * 选择数据源。
     *
     * @param project  当前项目
     * @param callback 选择完成回调（参数为 LocalDataSource 反射对象，取消/无数据源时为 null）
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
            else -> {
                val dialog = DataSourceSelectDialog(project, dataSources)
                if (dialog.showAndGet()) {
                    callback(dialog.getSelectedDataSource())
                } else {
                    callback(null)
                }
            }
        }
    }

    /** 数据源选择对话框（带确定/取消按钮）。 */
    private class DataSourceSelectDialog(
        project: Project,
        private val dataSources: List<Any>,
    ) : DialogWrapper(project, true) {

        private val list: JBList<DataSourceItem>

        init {
            title = MpSqlVisualizerBundle.message("execute.datasource.select")
            init()

            val items = dataSources.map { ds ->
                DataSourceItem(
                    dataSource = ds,
                    name = DataSourceSettings.getName(ds),
                    icon = DataSourceSettings.getIcon(ds),
                )
            }
            list = JBList(items).apply {
                selectedIndex = 0
            }
        }

        override fun createCenterPanel(): JComponent {
            return JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(4)
                add(com.intellij.ui.components.JBScrollPane(list), BorderLayout.CENTER)
            }
        }

        override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)

        fun getSelectedDataSource(): Any? = list.selectedValue?.dataSource

        /** 列表项包装。 */
        private data class DataSourceItem(
            val dataSource: Any,
            val name: String,
            val icon: javax.swing.Icon?,
        ) {
            override fun toString(): String = name
        }
    }
}
