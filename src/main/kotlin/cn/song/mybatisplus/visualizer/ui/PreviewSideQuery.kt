package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.execute.DataSourceSelector
import cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate
import cn.song.mybatisplus.visualizer.mcp.McpDbExecutor
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.mcp.McpSelectGuard
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project

/**
 * 预览面板只读旁路查询（T-PreviewSideQuery P1/P2）。
 *
 * 走 [McpDbExecutor.executeQuery] 这条既有只读通道（结构化 EXPLAIN 已在使用），
 * **不调用** `SqlExecutor.execute` / `embedConsole`，因此不会覆盖预览编辑器所在的
 * 共享控制台文档，也不会让 `currentEditorSql()` 漂移到试算/EXPLAIN 语句。
 *
 * 前置检查与生产执行链一致：SELECT 探针先过 [McpSelectGuard]，再按
 * [ExecutionDialectGate] 对绑定数据源做 fail-closed 方言校验；通过后才在池线程执行，
 * EDT 回调结果。
 */
internal object PreviewSideQuery {

    sealed interface Result {
        /** 单值结果（预估影响行数）。 */
        data class Scalar(val value: Long, val raw: McpExplainFormatter.RawResult) : Result

        /** 表格结果（EXPLAIN 直送等）。 */
        data class Table(val raw: McpExplainFormatter.RawResult) : Result

        /** 只读护栏/方言闸阻断；[message] 可直接展示。 */
        data class Blocked(val message: String) : Result

        /** 执行失败；[message] 可直接展示。 */
        data class Failed(val message: String) : Result
    }

    /**
     * 测试缝：headless 无真库/原生控制台；生产为 null 走真链。
     * 回调在调用线程执行，测试可直接同步给结果。
     */
    @Volatile
    internal var runnerForTest: ((Project, Any, String, Boolean, (Result) -> Unit) -> Unit)? = null

    /**
     * @param request 渲染来源/意图/是否可重渲染——与生产执行链同一份事实。
     * @param scalar  true=只取首行首列数字（COUNT 试算）；false=返回整表（EXPLAIN）。
     * @param requireSelectGuard true=先过 MCP 只读护栏（COUNT 探针）；EXPLAIN 走自己的
     *        ExplainGate/方言闸，不需要 SELECT 护栏。
     */
    fun run(
        project: Project,
        dataSource: Any,
        sql: String,
        request: ExecutionDialectGate.Request,
        scalar: Boolean,
        requireSelectGuard: Boolean = true,
        channelNote: String? = null,
        onResult: (Result) -> Unit,
    ) {
        if (requireSelectGuard) {
            val guard = McpSelectGuard.checkSingleSelect(sql)
            if (!guard.allowed) {
                onResult(Result.Blocked(guard.reason ?: guard.errorCode ?: "read-only guard blocked"))
                return
            }
        }
        val target = runCatching { ExecutionDialectGate.fromDataSource(dataSource) }.getOrNull()
        val verdict = ExecutionDialectGate.evaluate(request, target?.dialectName)
        if (verdict.status != ExecutionDialectGate.Status.MATCH) {
            onResult(Result.Blocked(DataSourceSelector.dialectGateMessage(verdict)))
            return
        }
        val testRunner = runnerForTest
        if (testRunner != null) {
            testRunner(project, dataSource, sql, scalar, onResult)
            return
        }
        val timeoutSeconds = PluginSettings.getInstance().state.executionTimeoutSeconds
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching {
                McpDbExecutor.executeQuery(
                    project,
                    dataSource,
                    sql,
                    if (scalar) 1 else McpExplainFormatter.DEFAULT_MAX_ROWS,
                    timeoutSeconds = timeoutSeconds,
                    channelNote = channelNote,
                )
            }
            ApplicationManager.getApplication().invokeLater({
                outcome.onSuccess { raw ->
                    if (scalar) {
                        val value = raw.rows.firstOrNull()?.firstOrNull()?.trim()?.toLongOrNull()
                        if (value == null) {
                            onResult(Result.Failed("query did not return a numeric count"))
                        } else {
                            onResult(Result.Scalar(value, raw))
                        }
                    } else {
                        onResult(Result.Table(raw))
                    }
                }.onFailure { e ->
                    val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                    onResult(Result.Failed(cause.message ?: cause.javaClass.simpleName))
                }
            }, ModalityState.any())
        }
    }
}
