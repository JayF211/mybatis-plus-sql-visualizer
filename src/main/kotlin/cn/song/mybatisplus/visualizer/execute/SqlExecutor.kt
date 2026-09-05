package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * SQL 执行引擎（M36，反射隔离）：弹出原生数据源选择 → Database 控制台内嵌执行。
 *
 * M36.5 执行链拆分：
 *  - [prepareConsole]：准备控制台（选数据源 → 建 session → attachConsole → 拼合组件回调），**不执行**——
 *    面板打开时调用，原生控制台即刻可见；
 *  - [executePrepared]：在已准备的控制台上执行；未就绪返回 false（调用方回退全链）；
 *  - [execute]：准备 + 执行合一（原语义，等价原生 Ctrl+Enter）。
 *
 * 反射隔离约定：本类仅当 Database 插件已加载时被调用，Community 下永不加载。
 */
object SqlExecutor {

    /**
     * 准备 + 执行合一（M36 原语义）：选数据源 → 建控制台 → 直接执行。
     *
     * @param onConsole 控制台 UI 组件就绪回调（供内嵌展示；null = 不内嵌）
     * @param onEditor  控制台编辑器就绪回调（注入插件补全上下文；null = 不需要）
     * @param onError   错误回调（执行链失败时调用；null = 不通知）
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

    /**
     * M36.5：准备控制台（不执行）——面板打开时调用，原生控制台默认展示。
     * Community 下静默失败（callback(false)，不打扰用户——社区版走自研编辑器回退）。
     */
    fun prepareConsole(
        project: Project,
        sql: String,
        onConsole: ((javax.swing.JComponent) -> Unit)? = null,
        onEditor: ((com.intellij.openapi.editor.Editor) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        callback: ((Boolean) -> Unit)? = null,
    ) {
        if (!ExecuteSqlSupport.isAvailable()) {
            // 社区版：静默回退（不弹提示——自研编辑器已是回退态）
            callback?.invoke(false)
            return
        }
        DataSourceSelector.prepareConsole(
            project, sql,
            onConsole = onConsole,
            onEditor = onEditor,
            onError = onError,
            callback = callback,
        )
    }

    /**
     * M36.5：在已准备的控制台上执行。
     * @param onResultsReady 执行触发成功回调（完整控制台 UI，顶部块+结果区）——
     *                       调用方据此把右侧从纯编辑器切换为带结果区的完整控制台
     * @return false = 控制台未就绪（调用方回退 [execute] 全链）
     */
    fun executePrepared(
        project: Project,
        onResultsReady: ((javax.swing.JComponent) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ): Boolean {
        if (!ExecuteSqlSupport.isAvailable()) return false
        return DataSourceSelector.executePrepared(project, onResultsReady, onError)
    }

    /** 控制台是否已就绪（[prepareConsole] 成功后 true）。 */
    fun isPrepared(): Boolean = ExecuteSqlSupport.isAvailable() && DataSourceSelector.isPrepared()

    /** 释放已准备控制台引用（面板清空/关闭时调用）。 */
    fun clearPrepared() {
        DataSourceSelector.clearPrepared()
    }

    /** T-Perf-16：面板 dispose 释放全部单例滞留引用（prepared/shared/pendingOnConsole）。 */
    fun releaseForPanel() {
        DataSourceSelector.releaseForPanel()
    }

    private fun showInfo(project: Project, message: String) {
        runCatching { Messages.showInfoMessage(project, message, MpSqlVisualizerBundle.message("execute.title")) }
    }
}
