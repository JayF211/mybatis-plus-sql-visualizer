package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * SQL 执行引擎（M36，反射隔离）：弹出原生数据源选择 → Database 控制台内嵌执行。
 *
 * 通过 [DataSourceSelector.selectAndExecute] 触发 Database 插件原生数据源弹窗，
 * 选中后自动执行；[onConsole] 回调带回 JdbcConsole 的原生 UI 组件
 * （编辑器+结果区），由调用方（SQL 预览面板）内嵌展示。
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
     * @param onConsole  控制台 UI 组件就绪回调（供内嵌展示；null = 不内嵌）
     * @param onEditor   控制台编辑器就绪回调（M36.2：注入插件补全上下文；null = 不需要）
     * @param onError    错误回调（执行链失败时调用，参数为错误信息；null = 不通知）
     */
    fun execute(
        project: Project,
        sql: String,
        dataSource: Any?,
        isQuery: Boolean,
        onConsole: ((javax.swing.JComponent) -> Unit)? = null,
        onEditor: ((com.intellij.openapi.editor.Editor) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ) {
        DataSourceSelector.selectAndExecute(
            project, sql,
            onConsole = onConsole,
            onEditor = onEditor,
            onError = onError,
        ) { success ->
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
