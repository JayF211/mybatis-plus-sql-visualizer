package cn.song.mybatisplus.visualizer.mcp

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project

/**
 * MCP 工具集抽象基类（T-McpToolset2 / T-MTS2-00）：一工具一 toolset 类的平台门控基座。
 *
 * **为什么按工具拆类**：平台对工具的启用/禁用门控只有 toolset 粒度——
 * `McpToolset.Companion.getEnabledToolsets`（262 mcpserver.jar 字节码实证）按 `isEnabled()`
 * 过滤整个 toolset；工具级开关即一工具一 toolset 类，各以自己的 [toolName] 过 [McpToolGate]。
 *
 * **253 兼容（T-McpToolset 定案继承）**：三钩子在基类**显式字面量覆写**——K2（jvm-default=
 * all-compatibility）只为「实现类未覆写的接口默认方法」生成 invokespecial 兼容桥，253 分支
 * 接口缺 isEnabled/alwaysIncluded 默认方法即桥悬空（verifyPlugin 实证）；基类提供具体覆写后
 * 子类零接口默认引用，253/262 双清。子类**不得**再覆写三钩子。
 *
 * **输出预算登记表（T-McpOutputBudget / O-20 守卫约定——新增工具或调整预算必须在此登记对齐，
 * MetadataInvalidator 缓存登记表同款防漏）**：
 *  - mpsv_schema_context：maxTables 默认 8 / 硬上限 20、单元格 200 字符、tables 白名单 ≤50
 *    （常量在 McpSchemaFormatter：DEFAULT_MAX_TABLES / HARD_MAX_TABLES / MAX_CELL_CHARS /
 *    MAX_TABLE_NAMES）；
 *  - mpsv_explain：行默认 ≤200 / 上限 1000（maxRows 可配，越界钳制不抛错；通道层另有兜底钳制）、
 *    单元格 2000 字符（常量在 McpExplainFormatter：DEFAULT_MAX_ROWS / HARD_MAX_ROWS /
 *    MAX_CELL_CHARS）；notices 节无条数预算（驱动会话通道天然有界，个案个位数）；
 *  - mpsv_perf_advice：建议 ≤30 条（MAX_FINDINGS，超限 SEVERITY 等级序先裁低级）+ 单条文案
 *    200 字符（MAX_ADVICE_CHARS，对齐 schema 200 口径）（常量在 PerfAdviceTool）；
 *  - 其余七工具：输出规模由输入决定天然有界（渲染=源码规模、logs=日志条数、crud/reverse/
 *    rollback=单实体单语句），不设额外预算。
 *
 * 注册：mcp-optional.xml `mcpServer.mcpToolset` EP（com.intellij.mcpServer 插件在 253+/262
 * 内置；243 整文件不加载）。线程模型/零弹窗/审计约定与一期一致：协程线程调用（PSI 读包
 * ReadAction 于各服务内部）；拒绝走 [McpExpectedError]；每次调用经 [McpAuditLog] 审计。
 */
abstract class McpToolsetSupport(private val toolName: String) : McpToolset {

    /** 平台门控：总开关 ∧ 工具级未禁用（McpToolGate 读插件设置，测试环境兜底启用）。 */
    final override fun isEnabled(): Boolean = McpToolGate.isEnabled(toolName)

    /** 稳定工具集（真实接口 isExperimental 默认 true = 实验态，必须显式 false 才进稳定集）。 */
    final override fun isExperimental(): Boolean = false

    final override fun alwaysIncluded(): Boolean = false

    // ------------------------------------------------------------------
    // 共用骨架（一期 SqlVisualizerToolset 逐行迁入）
    // ------------------------------------------------------------------

    /** 审计包裹：成功/预期错误统一记 idea.log。非预期异常不在此吞（平台记 Internal error）。
     *  tool/started 打包单值 [AuditTicket] 过挂起点——挂起帧单槽位，杜绝 K2 nullOutSpilledVariable
     *  → SpillingKt 引用（该类 243 平台 stdlib 缺失，verifyPlugin 实证）。 */
    protected suspend fun audit(tool: String, block: suspend () -> String): String {
        val ticket = AuditTicket(tool)
        try {
            val result = block()
            ticket.ok()
            return result
        } catch (e: McpExpectedError) {
            ticket.expectedError(e)
            throw e
        }
    }

    private class AuditTicket(private val tool: String) {
        private val started = System.currentTimeMillis()
        fun ok() = McpAuditLog.call(tool, "ok", "OK", System.currentTimeMillis() - started)
        fun expectedError(e: McpExpectedError) =
            McpAuditLog.call(tool, e.mcpErrorText, "EXPECTED_ERROR", System.currentTimeMillis() - started)
    }

    /** 取当前项目（框架注入 coroutineContext；桩扩展运行时解析平台 McpCallInfoKt）。 */
    protected suspend fun requireProject(): Project =
        kotlin.coroutines.coroutineContext.projectOrNull
            ?: throwExpected(
                McpToolErrors.code(McpToolErrors.PROJECT_NOT_READY, "no project bound to this MCP call."),
            )

    /** 索引未就绪明确报错不挂死（渲染/schema/实体解析依赖 PSI 与索引）。 */
    protected fun requireSmart(project: Project) {
        if (DumbService.getInstance(project).isDumb) {
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.PROJECT_NOT_READY,
                    "project indexing is in progress; retry when the IDE finishes indexing.",
                ),
            )
        }
    }

    /** 拒绝通道：agent 可读结构化错误，零弹窗（McpExpectedError 由框架转工具错误）。 */
    protected fun throwExpected(message: String): Nothing = throw McpExpectedError(message)

    protected fun firstLine(text: String): String = text.lineSequence().firstOrNull { it.isNotBlank() } ?: text
}
