package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.LogStatementClassifier
import cn.song.mybatisplus.visualizer.log.SqlListTypeColor
import cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver
import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.log.sqlLogManagerIfCreated
import cn.song.mybatisplus.visualizer.log.SqlSource
import cn.song.mybatisplus.visualizer.log.StatementType
import cn.song.mybatisplus.visualizer.log.formatDatabaseFragment
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project

/**
 * MCP 工具集抽象基类（T-McpToolset2 / T-MTS2-00）：一工具一 toolset 类的平台门控基座。
 *
 * **为什么按工具拆类**：平台对工具的启用/禁用门控只有 toolset 粒度——
 * `McpToolset.Companion.getEnabledToolsets`（262 mcpserver.jar 字节码实证）按 `isEnabled()`
 * 过滤整个 toolset；工具级开关即一工具一 toolset 类，各以自己的 [toolName] 过 [McpToolGate]。
 * （O-68 订正：该过滤只覆盖 ReflectionToolsProvider 路径；agent 真正看到的外部会话清单走
 * `getMcpToolsFiltered` 过滤层，由 SqlVisualizerToolGateFilterProvider EP 承担，见 McpToolGate
 * 头注。）
 *
 * **类名前缀 `SqlVisualizer` 是硬约定（T-McpToolNaming / T-MTN-01）**：平台「暴露工具」页按工具描述符的
 * category 分组，category 的 `shortName` 直接取 **toolset 类的 simpleName**
 * （`ToolsetReflection_utilKt.asTools` 字节码实证），`McpToolset` 接口本身没有名字属性——
 * 分组名只能由类名决定。加统一前缀后本插件十组在列表里**组名自带插件名且字典序相邻**，
 * 扫视即知同属一个插件。**新增工具类必须照此命名**（`SqlVisualizer<Xxx>Tool`），
 * 否则该工具在「暴露工具」页会脱离本插件组群。
 *
 * **253 兼容（T-McpDynamicToolset 形态）**：本基类**零平台接口实现**（T-McpDynamicToolset：
 * jar 字节码零 com.intellij.mcpserver.* 类型引用——243/251 的 mcpServer 旧形态缺新 API，静态
 * 引用即 verifier compatibility problems，20260926 审核拒绝主因）。平台接口由运行期 ASM 生成的
 * 壳类实现（壳 extends 本基类链上的工具类 + implements McpToolset，三钩子直接继承本类实现，
 * 见 McpToolsetDynamicRegistrar）；此前「显式字面量覆写防 K2 兼容桥悬空」的三钩子约定随
 * implements 摘除自然消解（K2 桥只为接口默认方法生成）。
 *
 * **输出预算登记表（T-McpOutputBudget / O-20 守卫约定——新增工具或调整预算必须在此登记对齐，
 * MetadataInvalidator 缓存登记表同款防漏）**：
 *  - sqlv_schema_context：maxTables 默认 8 / 硬上限 20、单元格 200 字符、tables 白名单 ≤50
 *    （常量在 McpSchemaFormatter：DEFAULT_MAX_TABLES / HARD_MAX_TABLES / MAX_CELL_CHARS /
 *    MAX_TABLE_NAMES）；
 *  - sqlv_explain：行默认 ≤200 / 上限 1000（maxRows 可配，越界钳制不抛错；通道层另有兜底钳制）、
 *    单元格 2000 字符（常量在 McpExplainFormatter：DEFAULT_MAX_ROWS / HARD_MAX_ROWS /
 *    MAX_CELL_CHARS）；notices 节无条数预算（驱动会话通道天然有界，个案个位数）；
 *  - sqlv_perf_advice：建议 ≤30 条（MAX_FINDINGS，超限 SEVERITY 等级序先裁低级）+ 单条文案
 *    200 字符（MAX_ADVICE_CHARS，对齐 schema 200 口径）（常量在 SqlVisualizerPerfAdviceTool）；
 *  - 其余七工具：输出规模由输入决定天然有界（渲染=源码规模、logs=日志条数、crud/reverse/
 *    rollback=单实体单语句），不设额外预算。
 *
 * 注册：运行期探测式程序化注册（McpToolsetDynamicRegistrar，mcpServer 新形态在场的 253+/262
 * 命中；243/251 旧形态静默跳过）。线程模型/零弹窗/审计约定与一期一致：协程线程调用（PSI 读包
 * ReadAction 于各服务内部）；拒绝经 [SqlvExpectedToolException]＋audit 边界反射转译为平台
 * McpExpectedError（见 [McpExpectedBridge]）；每次调用经 [McpAuditLog] 审计。
 */
abstract class McpToolsetSupport(private val toolName: String) {

    /** 平台门控：总开关 ∧ 工具级未禁用（McpToolGate 读插件设置，测试环境兜底启用）。 */
    fun isEnabled(): Boolean = McpToolGate.isEnabled(toolName)

    /** 稳定工具集（平台 McpToolset.isExperimental 默认 true = 实验态，必须显式 false 才进稳定集）。 */
    fun isExperimental(): Boolean = false

    fun alwaysIncluded(): Boolean = false

    // ------------------------------------------------------------------
    // 共用骨架（一期 SqlVisualizerToolset 逐行迁入）
    // ------------------------------------------------------------------

    /**
     * 审计包裹：成功/预期错误统一记 idea.log。非预期异常不在此吞（平台记 Internal error）。
     *  tool/started 打包单值 [AuditTicket] 过挂起点——挂起帧单槽位，杜绝 K2 nullOutSpilledVariable
     *  → SpillingKt 引用（该类 243 平台 stdlib 缺失，verifyPlugin 实证）。
     *
     *  T-McpAuditTrail T-MAT-04：[argsSummary] 为真实入参摘要（经 [McpAuditLog.summarize] 产出，
     *  如 sqlv_explain 的 dataSource/dialect/SQL 首段，整体受 [McpAuditLog] 的 SUMMARY_LIMIT 截断）。
     *  默认值 [LEGACY_ARGS_SUMMARY] 是改造前的字面量：其余九个**纯静态**工具无执行面、参数面未立项，
     *  保持审计行逐字节不变（计划 §验收 4，**仅 OK 路径**——错误路径的入参段格式十个工具统一变化）；
     *  新增执行类工具应显式传摘要，勿再落 "ok"。
     *
     *  错误路径（EXPECTED_ERROR）的组合串走 [errorArgsSummary] 的两段分预算：错误文本优先，
     *  入参摘要让位——长 SQL 场景下错误原因不被摘要挤掉（T-MAT-04 回退修复）。
     */
    protected suspend fun audit(
        tool: String,
        argsSummary: String = LEGACY_ARGS_SUMMARY,
        block: suspend () -> String,
    ): String {
        val ticket = AuditTicket(tool, argsSummary)
        try {
            val result = block()
            ticket.ok()
            return result
        } catch (e: SqlvExpectedToolException) {
            ticket.expectedError(e)
            // 对平台抛出的必须是平台 McpExpectedError（agent 可读错误而非 Internal error）；
            // 转译经反射（T-McpDynamicToolset 零平台类型引用），失败降级原样抛自有异常。
            throw McpExpectedBridge.wrapExpected(e)
        }
    }

    private class AuditTicket(private val tool: String, private val argsSummary: String) {
        private val started = System.currentTimeMillis()
        fun ok() = McpAuditLog.call(tool, argsSummary, "OK", System.currentTimeMillis() - started)

        /** 预期错误：入参摘要 + 错误文本同列（改造前该槽位只放错误文本；两者同列后错误文本
         *  可能被摘要挤掉——T-MAT-04 回退修复改为 [errorArgsSummary] 的两段分预算，错误文本优先）。 */
        fun expectedError(e: SqlvExpectedToolException) =
            McpAuditLog.call(
                tool,
                errorArgsSummary(argsSummary, e.mcpErrorText),
                "EXPECTED_ERROR",
                System.currentTimeMillis() - started,
            )
    }

    /** 取当前项目（框架注入 coroutineContext；反射解析平台 McpCallInfoKt，见 McpExpectedBridge）。 */
    protected suspend fun requireProject(): Project =
        McpExpectedBridge.currentProjectOrNull(kotlin.coroutines.coroutineContext)
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

    /** 拒绝通道：agent 可读结构化错误，零弹窗（audit 边界转译为平台 McpExpectedError）。 */
    protected fun throwExpected(message: String): Nothing = throw SqlvExpectedToolException(message)

    protected fun firstLine(text: String): String = text.lineSequence().firstOrNull { it.isNotBlank() } ?: text

    companion object {
        /**
         * T-McpAuditTrail T-MAT-04：改造前的审计入参槽位字面量。保留为默认值——纯静态工具
         * （无执行面、参数面未立项）的审计行因此逐字节不变；执行类工具显式传真实摘要。
         */
        private const val LEGACY_ARGS_SUMMARY = "ok"
    }
}

// ------------------------------------------------------------------
// T-McpAuditTrail（T-MAT-01/02）：MCP 执行链落历史
//
// 为什么是**顶层函数**而非基类方法：本文件门面类（McpToolsetSupportKt）的顶层函数可被裸 JUnit /
// 平台测试直调，加载时不必解析抽象基类的 mcpserver 接口（可选插件依赖）——McpDbExecutor.drainWarnings
// 同款先例。落库链（[SqlLogManager] 取用 + 条目构造）整体留在这里，基类只多一个回调接线点；
// 未来新增执行类工具照抄 [recordMcpExecution] 一处即可获得历史与审计。
// ------------------------------------------------------------------

/**
 * MCP 执行条目构造（T-McpAuditTrail T-MAT-01/02）。
 *
 * **文本恒为调用方传入的原始 selectSql，绝不落 `EXPLAIN <select>` 包装句**：包装句首 token 为
 * `EXPLAIN` → [LogStatementClassifier] 判 `StatementType.OTHER` → [SqlListTypeColor.colorOf] 对 OTHER
 * 返回 null → 列表无类型染色（计划 §一 硬约束 2，第八轮报告 #38 宣称的「风险类别复用分类器」当场失效）。
 * 「该条来自 EXPLAIN 包装执行」由 [SqlSource.MCP] 承担独立标记（该来源仅 explain 通道产出）。
 *
 * 无参数（params 空、无占位符）：MCP 工具收的是已成型 SELECT，参数已在 agent 侧落地——
 * 故本条目依赖 [SqlLogAutoSaver] 的 `source == MCP` 条件才写出 pd 行（T-MAT-03 配套必改）。
 *
 * @param dataSourceName 数据源显示名 → 复用既有目标库角标 [formatDatabaseFragment]
 * @param elapsedMs 执行耗时（历史列表按慢查询阈值着色）
 * @param errorText 失败时的「异常类名: 首行消息」（成功为 null，列表红字片段展示）
 */
internal fun mcpHistoryEntry(
    selectSql: String,
    dataSourceName: String? = null,
    elapsedMs: Long? = null,
    errorText: String? = null,
    timestamp: Long = System.currentTimeMillis(),
): LogSqlEntry {
    val sql = selectSql.trim()
    return LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = timestamp,
        source = SqlSource.MCP,
        executionTimeMs = elapsedMs,
        errorText = errorText,
        databaseName = dataSourceName,
    )
}

/**
 * 落历史（T-McpAuditTrail T-MAT-01）：走 [SqlLogManager] 既有入库路径——`buffer.addEntry` 之后
 * 面板刷新与 [SqlLogAutoSaver] 写盘全部复用既有链路（不新开写通道、不自建 MCP 专属存储）。
 * 取服务用 `project.getService`（Kotlin 类无平台生成的 `getInstance` 静态——Java 注解处理器只处理
 * Java 类，全仓取用口径一致，见 LogStartupCapture / SectionConfigurable）。
 *
 * 失败只记 idea.log 不冒泡：审计是旁路，绝不影响工具结果（McpDbExecutor 侧的回调投递同样 runCatching，
 * 两道都不许把审计故障升级为工具故障）。
 *
 * **门控（D1 定案，2026-09-22 用户）**：审计条目**不受「SQL 日志捕获」开关约束**——走
 * [cn.song.mybatisplus.visualizer.log.SqlLogEntryBuffer.addAuditEntry]（跳过 `paused` 门），
 * 即开关关着（含用户显式停止后的 `paused=true`）时照样落列表；MCP 工具的可用性只由设置页
 * 「启用 SQL Visualizer MCP 工具」＋工具级禁用清单决定（`McpToolGate`）。
 * 其余门与普通条目一致：去重窗口内同 SQL 折叠、过滤关键字命中的丢弃（两者是列表展示偏好，不是开关）。
 */
internal fun recordMcpExecution(
    project: Project,
    selectSql: String,
    dataSourceName: String?,
    elapsedMs: Long,
    errorText: String?,
) {
    runCatching {
        // T-ST-02b2：读路径经 sqlLogManagerIfCreated（永不发起创建；审计是旁路，取不到即跳过）
        sqlLogManagerIfCreated(project)?.entries?.addAuditEntry(
            mcpHistoryEntry(selectSql, dataSourceName, elapsedMs, errorText),
        )
    }.onFailure { e ->
        com.intellij.openapi.diagnostic.Logger
            .getInstance("cn.song.mybatisplus.visualizer.mcp.McpAuditTrail")
            .warn("T-McpAuditTrail: MCP 执行落历史失败（工具结果不受影响）", e)
    }
}
