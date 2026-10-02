package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.mcp.McpDbExecutor
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import cn.song.mybatisplus.visualizer.util.FatalErrors
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project

/**
 * R33：面板 EXPLAIN 结构化渲染通道（自建执行链路线，批次 9 核实报告 §三 V41 定案）。
 *
 * 现状（V41 锚点）：`SqlPreviewPanel.explainSql` 把包装后的 EXPLAIN 直送原生 Database 控制台，
 * 插件对结果区仅做组件换入（`embedConsole`），**无行列级程序化读取面**——截获控制台输出不可行
 * （网格由 DBN 托管，硬读=侵入平台内部模型，版本脆弱）。本通道复用 MCP 链既有的自持执行链
 * （[McpDbExecutor] 反射建连执行，243/262 签名核验在案），把行列文本交给面板侧结构化渲染。
 *
 * **双通道并存（保守增量红线）**：本通道不是 EXPLAIN 的唯一路径——判定/执行/渲染任一环节
 * 不成立都 fail-closed 回退既有直送控制台通道（[Decision.DirectSilent] = 设计内直送不提示；
 * [Decision.DirectFallback] = 结构化预期成立但环境不给，落回直送通道并给一条轻提示，不弹窗）。
 *
 * 线程纪律：[decide] 在 EDT 调用（只读布尔判定）；[executeStructured] 的 JDBC 全程在
 * pooled 线程（JDBC 禁 EDT，循 McpDbExecutor 既有线程形态），结果经 EDT 回调交付。
 */
object ExplainPanelChannel {

    /** 结构化渲染认得的输出格式：仅 MySQL 家族表格行与 PG 家族单列 JSON 文本。 */
    private val STRUCTURED_FORMATS = setOf(ExplainOutputFormat.TABLE, ExplainOutputFormat.JSON)

    /** 通道判定结果（三类可区分——提示文案不同，合并会让用户拿到错的解释，ExplainGate 同款口径）。 */
    sealed interface Decision {
        /** 走结构化通道（自建执行链 + 面板渲染）。 */
        object Structured : Decision

        /** 设计内直送（开关关 / 方言格式非结构化档 TEXT/XML/未知）——既有主路径，不给提示。 */
        object DirectSilent : Decision

        /** 环境性回退：结构化预期成立但环境不可用——落回直送通道并附轻提示（[reason] 可区分）。 */
        data class DirectFallback(val reason: FallbackReason) : Decision
    }

    /** 回退理由（轻提示按理由给可操作的措辞）。 */
    enum class FallbackReason {
        /** 预览会话没有已绑数据源（用户还没在控制台里选过数据源）。 */
        NO_DATA_SOURCE,

        /** Community / 无 Database 插件（自建链与控制台链同样不可用——此时直送链自身会提示引导）。 */
        NO_DATABASE_PLUGIN,

        /**
         * R33 真机走查修复：SQL 仍含未回填的 `?` 占位符——预览文本是回填链的终点产物
         * （参数击键有防抖重渲染），残留 `?` 即真未填（默认占位档 / 无类型信息）。此时下发
         * 结构化探测 = 把字面 `?` 送进 JDBC → 语法错（真机 `SQLException: You have an error
         * in your SQL syntax`）。响亮失败优于语法错：不发探测，明示「存在未回填参数」后
         * 落回直送通道（控制台如实呈现语法错）。
         */
        UNFILLED_PARAMS,
    }

    /**
     * 通道判定（纯函数，无 IDE 类型——EDT 直调安全）。
     *
     * 判定序：① 开关关 → 直送；② 格式非结构化档（TEXT/XML/未知，含 R23 Oracle 二段式与
     * SQL Server XML——**不硬解析**红线）→ 直送；③ database 插件不在场 → 回退（提示）；
     * ④ 无已绑数据源 → 回退（提示）；⑤ SQL 仍含未回填 `?`（R33 真机走查修复）→ 回退（提示）；
     * ⑥ 全部成立 → 结构化。
     *
     * `hasUnfilledParams` 默认 false 只为既有调用点零改编译——面板接线必须显式传
     * 占位探测结论（WiringGuard 钉住），漏传会让语法错场景重新滑进结构化探测。
     */
    fun decide(
        enabled: Boolean,
        format: ExplainOutputFormat?,
        databaseAvailable: Boolean,
        hasBoundDataSource: Boolean,
        hasUnfilledParams: Boolean = false,
    ): Decision {
        if (!enabled) return Decision.DirectSilent
        if (format == null || format !in STRUCTURED_FORMATS) return Decision.DirectSilent
        if (!databaseAvailable) return Decision.DirectFallback(FallbackReason.NO_DATABASE_PLUGIN)
        if (!hasBoundDataSource) return Decision.DirectFallback(FallbackReason.NO_DATA_SOURCE)
        if (hasUnfilledParams) return Decision.DirectFallback(FallbackReason.UNFILLED_PARAMS)
        return Decision.Structured
    }

    /**
     * 结构化执行结果（纯数据，EDT 回调交付；[dataSourceName]/[elapsedMs] 来自
     * [McpDbExecutor.ExecutedOutcome] 审计钩子的同步回读——成功失败两条路径都会先回调一次）。
     */
    data class StructuredOutcome(
        val raw: McpExplainFormatter.RawResult,
        val elapsedMs: Long,
        val dataSourceName: String?,
    )

    /**
     * 结构化执行（JDBC 全程 pooled 线程，禁 EDT）。
     *
     * 复用 [McpDbExecutor.executeQuery] 既有反射链（channelNote 不传——结构化档方言全是
     * 单语句通道，null 走 executeQuery 主通道与既有调用点逐字节同形）；行预算取
     * [McpExplainFormatter.DEFAULT_MAX_ROWS]（EXPLAIN 计划行通常 <100，MCP 链同款默认）；
     * 超时护栏沿用设置项（与 MCP 链同源）。
     *
     * @param onPlan   成功回调（EDT 交付）
     * @param onFailed 失败回调（EDT 交付；参数 = 驱动异常「类名: 首行消息」）——调用方据此
     *                 回退直送通道（fail-closed，绝不吞掉失败假装渲染成功）
     */
    fun executeStructured(
        project: Project,
        dataSource: Any,
        explainSql: String,
        timeoutSeconds: Int,
        onPlan: (StructuredOutcome) -> Unit,
        onFailed: (String) -> Unit,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            var elapsedMs = -1L
            var dataSourceName: String? = null
            try {
                val raw = McpDbExecutor.executeQuery(
                    project, dataSource, explainSql, McpExplainFormatter.DEFAULT_MAX_ROWS,
                    timeoutSeconds = timeoutSeconds,
                    onExecuted = { outcome ->
                        // 审计钩子同步回读（成功/失败两路径都回调一次；取数失败不影响主链）
                        elapsedMs = outcome.elapsedMs
                        dataSourceName = outcome.dataSourceName
                    },
                )
                val outcome = StructuredOutcome(raw, elapsedMs, dataSourceName)
                invokeLaterEdt { onPlan(outcome) }
            } catch (e: Throwable) {
                // T-FatalPassthrough 同款解包口径（反射链目标异常被包进 InvocationTargetException）
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                if (FatalErrors.isFatal(cause)) throw cause
                val text = "${cause.javaClass.simpleName}: " +
                    (cause.message?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "(no message)")
                invokeLaterEdt { onFailed(text) }
            }
        }
    }

    private fun invokeLaterEdt(block: () -> Unit) {
        // write-safe：回调只做 Swing 组装/换入（无文档写），ModalityState.any() 同 embedConsole 口径
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any())
    }
}
