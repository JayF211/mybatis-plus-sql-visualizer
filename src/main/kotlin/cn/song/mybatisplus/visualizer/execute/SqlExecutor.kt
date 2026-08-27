package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * SQL 执行引擎（M36，反射隔离）：弹出原生数据源选择 → 自动执行 SQL。
 *
 * 通过 [DataSourceSelector.selectAndExecute] 触发 Database 插件原生数据源弹窗，
 * 选中后 [com.intellij.database.console.DatabaseRunners] 打开 Database 编辑器执行，
 * 结果在编辑器下方原生展示。
 *
 * 反射隔离约定：本类仅当 Database 插件已加载时被调用，Community 下永不加载。
 */
object SqlExecutor {

    /**
     * 执行 SQL（核心入口）。
     *
     * @param project    当前项目
     * @param sql        SQL 文本
     * @param dataSource 保留参数（不使用，数据源由原生弹窗选择）
     * @param isQuery    保留参数（SQL 类型分类已在面板层完成）
     */
    fun execute(project: Project, sql: String, dataSource: Any?, isQuery: Boolean) {
        // 创建临时上下文文件 → 原生数据源弹窗 → 选中后自动执行
        DataSourceSelector.selectAndExecute(project, sql) { success ->
            if (!success) {
                // Community 或失败时提示
                if (!ExecuteSqlSupport.isAvailable()) {
                    showInfo(project, MpSqlVisualizerBundle.message("execute.noDatabase"))
                }
            }
        }
    }

    private fun showInfo(project: Project, message: String) {
        runCatching { Messages.showInfoMessage(project, message, MpSqlVisualizerBundle.message("execute.title")) }
    }
}