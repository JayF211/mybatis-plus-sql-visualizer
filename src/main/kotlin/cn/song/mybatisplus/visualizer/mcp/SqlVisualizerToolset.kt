package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.projectOrNull
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * T-McpToolset：平台 MCP Server 工具集（一期三只读工具）。
 *
 * 注册：mcp-optional.xml `mcpServer.mcpToolset` EP（com.intellij.mcpServer 插件在 253+/262
 * 内置；本类只在该插件存在时加载——可选依赖未装自动降级，243 明确不支持，见 CHANGELOG）。
 * 编译期类型来自 mcpStub 桩源集（与 262 内置 jar javap 签名一一对应），运行时解析平台真类。
 *
 * 线程模型：MCP 框架在协程线程调用（非 EDT）——PSI 读取全部包 [ReadAction]
 * （[McpSourceLocator]/[McpSchemaContextService] 内部已包）；JDBC 在 [Dispatchers.IO]。
 * 零弹窗红线：所有拒绝走 [McpExpectedError]（agent 可读结构化错误），不弹任何确认框；
 * EXPLAIN 建连显式 setAskPassword(false)（密码未保存直接失败）。
 * 审计：每次调用经 [McpAuditLog] 记 idea.log（时间/工具/入参摘要/状态/耗时）。
 */
class SqlVisualizerToolset : McpToolset {

    /** 三钩子显式字面量覆写（桩已改抽象强制）。253 版真实接口缺 isEnabled/alwaysIncluded 默认方法，
     *  K2 兼容桥会在类内生成 invokespecial 引用接口默认方法 → 253 验证报未解析（verifyPlugin 实证）；
     *  字面量返回与 262 接口默认值一致（isEnabled=true / isExperimental=false / alwaysIncluded=false）。 */
    override fun isEnabled(): Boolean = true

    /** 稳定工具集（真实接口 isExperimental 默认 true = 实验态，必须显式 false 才进稳定集）。 */
    override fun isExperimental(): Boolean = false

    override fun alwaysIncluded(): Boolean = false

    // ------------------------------------------------------------------
    // mpsv_render_sql
    // ------------------------------------------------------------------

    @McpTool(name = "mpsv_render_sql", title = "Render source code into executable SQL")
    @McpDescription(
        "Render the exact executable SQL behind MyBatis-Plus Wrapper chains, mapper XML statements, " +
            "MyBatis annotation SQL (@Select etc.), JPA @Query/derived queries/CriteriaQuery, HQL and " +
            "JdbcTemplate string SQL in the current IntelliJ project, with real parameter values filled in. " +
            "Pass (filePath, line) of the source location (line is 1-based; omit for the first statement), " +
            "or pass xmlSnippet + sourceType='xml_mapper' for a mapper XML text. Read-only: never executes SQL.",
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
    )
    suspend fun mpsv_render_sql(
        filePath: String = "",
        line: Int = 0,
        xmlSnippet: String = "",
        sourceType: String = "",
    ): String = audit("mpsv_render_sql") {
        val project = requireProject()
        val (parsed, error) = McpRenderInput.parse(filePath, line.takeIf { it > 0 }, xmlSnippet, sourceType)
        if (parsed == null) throwExpected(error!!)
        requireSmart(project)
        val outcome = when {
            parsed.filePath != null -> McpSourceLocator.renderAtLine(project, parsed.filePath, parsed.line)
            parsed.xmlSnippet != null -> McpSourceLocator.renderXmlSnippet(project, parsed.xmlSnippet)
            else -> throwExpected(
                McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "provide filePath+line or xmlSnippet+sourceType."),
            )
        }
        if (outcome.error != null) throwExpected(outcome.error)
        buildString {
            append(outcome.sql)
            outcome.paramTable?.let { append("\n\n").append(it) }
            append("\n\nsourceType: ").append(outcome.sourceType ?: "unknown")
            outcome.tableName?.let { append("\ntable: ").append(it) }
            outcome.warning?.let { append("\nwarning: ").append(it) }
            outcome.degraded?.let { append("\nnote: ").append(it) }
        }
    }

    // ------------------------------------------------------------------
    // mpsv_schema_context
    // ------------------------------------------------------------------

    @McpTool(name = "mpsv_schema_context", title = "Get compact table/column schema context")
    @McpDescription(
        "Return a compact table+column context (names, types, comments, NOT NULL marks) for the given " +
            "tables, resolved from the real data source configured in the IDE (credentials never leave the IDE). " +
            "Pass a comma-separated 'tables' whitelist (1-50 names) and optionally 'dataSource' display name " +
            "when several data sources exist. 'maxTables' caps how many tables are returned (default 8, hard limit 20). " +
            "Read-only: metadata only, never executes SQL.",
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
    )
    suspend fun mpsv_schema_context(
        tables: String,
        dataSource: String = "",
        maxTables: Int = McpSchemaFormatter.DEFAULT_MAX_TABLES,
    ): String = audit("mpsv_schema_context") {
        val project = requireProject()
        requireSmart(project)
        val names = tables.split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val sanitized = McpSchemaFormatter.sanitizeTableNames(names)
            ?: throwExpected(
                McpToolErrors.code(
                    McpToolErrors.INVALID_ARGUMENT,
                    "tables must contain 1..${McpSchemaFormatter.MAX_TABLE_NAMES} table names (comma-separated).",
                ),
            )
        val outcome = McpSchemaContextService.buildContext(project, dataSource, sanitized, maxTables)
        if (outcome.error != null) throwExpected(outcome.error)
        outcome.text ?: throwExpected(McpToolErrors.code(McpToolErrors.SCHEMA_NOT_AVAILABLE, "empty schema context."))
    }

    // ------------------------------------------------------------------
    // mpsv_explain
    // ------------------------------------------------------------------

    @McpTool(name = "mpsv_explain", title = "Explain a SELECT against a configured data source")
    @McpDescription(
        "Run EXPLAIN for a single read-only SELECT statement against a real data source configured in the IDE " +
            "and return the plan text (MySQL/MariaDB/PostgreSQL/Oracle/SQL Server/H2/DM/KingBase dialect dispatch, " +
            "dialect auto-detected from the data source URL unless overridden). Strictly read-only: the statement " +
            "must be a single plain SELECT (no locking reads, no INTO, no writes); rejections are fail-closed.",
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
    )
    suspend fun mpsv_explain(
        selectSql: String,
        dataSource: String = "",
        dialect: String = "",
    ): String = audit("mpsv_explain") {
        val target = resolveExplainTarget(requireProject(), selectSql, dataSource, dialect)
        McpExplainFormatter.format(executeExplain(target)) + "\n\ndialect: ${target.dialectName}"
    }

    // ------------------------------------------------------------------
    // 骨架工具方法
    // ------------------------------------------------------------------

    /** 审计包裹：成功/预期错误统一记 idea.log（计划 §三-5）。非预期异常不在此吞（平台记 Internal error）。
     *  tool/started 打包单值 [AuditTicket] 过挂起点——挂起帧单槽位，杜绝 K2 nullOutSpilledVariable
     *  → SpillingKt 引用（该类 243 平台 stdlib 缺失，verifyPlugin 实证）。 */
    private suspend fun audit(tool: String, block: suspend () -> String): String {
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

    /** explain 前置链（白名单→数据源定位→方言派发，步骤 1~3）。全链非挂起：不进协程状态机，
     *  从结构上杜绝 SpillingKt 引用；语义与分步内联版逐行一致（T-McpToolset 253/243 门禁修复）。 */
    private fun resolveExplainTarget(project: Project, selectSql: String, dataSource: String, dialect: String): ExplainTarget {
        // 1) 只读白名单（fail closed：非 SELECT / 多语句 / 锁读 / INTO / 解析失败全拒）
        val verdict = McpSelectGuard.checkSingleSelect(selectSql)
        if (!verdict.allowed) {
            throwExpected(McpToolErrors.code(verdict.errorCode ?: McpToolErrors.SQL_BLOCKED, verdict.reason ?: "rejected."))
        }

        // 2) 数据源定位（零弹窗：不给/给不出唯一别名即报错并列可选名，绝不弹选择框）
        if (!McpDbExecutor.isDatabaseAvailable()) {
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.DATASOURCE_NOT_FOUND,
                    "the Database tool plugin is not available in this IDE; configure a data source first.",
                ),
            )
        }
        val ds = McpDbExecutor.findDataSource(project, dataSource) ?: run {
            val names = McpDbExecutor.listDataSourceNames(project)
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.DATASOURCE_NOT_FOUND,
                    when {
                        names.isEmpty() -> "no data sources configured in this project."
                        dataSource.isBlank() -> "several data sources exist (${names.joinToString(", ")}); " +
                            "pass the 'dataSource' display name."
                        else -> "data source '$dataSource' not found; available: ${names.joinToString(", ")}."
                    },
                ),
            )
        }

        // 3) 方言派发（EXPLAIN 语句包装，七方言矩阵与 IDE 预览面板同链）
        if (!McpDialectDetector.isRecognized(dialect)) {
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.INVALID_ARGUMENT,
                    "dialect '$dialect' is not recognized; supported: MySQL, MariaDB, PostgreSQL, Oracle, " +
                        "SQL Server, H2, SQLite, DM, KingBase (omit to auto-detect from the data source URL).",
                ),
            )
        }
        val url = runCatching {
            Class.forName("com.intellij.database.model.DasDataSource")
                .getMethod("getConnectionConfig").invoke(ds)
                ?.let { Class.forName("com.intellij.database.model.RawConnectionConfig").getMethod("getUrl").invoke(it) as? String }
        }.getOrNull()
        val dialectName = McpDialectDetector.resolve(dialect, url)
        val explainSql = DialectRegistry.byName(dialectName).renderExplainStatement(selectSql.trim().removeSuffix(";"))
            ?: throwExpected(
                McpToolErrors.code(
                    McpToolErrors.DIALECT_UNSUPPORTED,
                    "dialect '$dialectName' has no EXPLAIN rendering in this plugin version.",
                ),
            )
        return ExplainTarget(project, ds, explainSql, dialectName)
    }

    /** 4) headless 执行（同步、无 UI、密码未保存即失败）。挂起帧只持 target 单引用。 */
    private suspend fun executeExplain(target: ExplainTarget): McpExplainFormatter.RawResult =
        withContext(Dispatchers.IO) {
            runCatching {
                McpDbExecutor.executeQuery(target.project, target.ds, target.explainSql, McpExplainFormatter.MAX_ROWS)
            }.getOrElse { e ->
                throwExpected(
                    McpToolErrors.code(
                        McpToolErrors.DATASOURCE_UNREACHABLE,
                        "EXPLAIN failed on '${target.dialectName}' data source: ${firstLine(e.message ?: e.javaClass.simpleName)}",
                    ),
                )
            }
        }

    /** explain 解析产物载体：项目/数据源/方言信息打包单值过挂起点（挂起帧单槽位，杜绝
     *  K2 nullOutSpilledVariable → SpillingKt 引用；该类 243 平台 stdlib 缺失，verifyPlugin 实证）。 */
    private class ExplainTarget(
        val project: Project,
        val ds: Any,
        val explainSql: String,
        val dialectName: String,
    )

    /** 取当前项目（框架注入 coroutineContext；桩扩展运行时解析平台 McpCallInfoKt）。 */
    private suspend fun requireProject(): Project =
        kotlin.coroutines.coroutineContext.projectOrNull
            ?: throwExpected(
                McpToolErrors.code(McpToolErrors.PROJECT_NOT_READY, "no project bound to this MCP call."),
            )

    /** 索引未就绪明确报错不挂死（计划 §三-6；渲染/schema 依赖 PSI 与索引）。 */
    private fun requireSmart(project: Project) {
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
    private fun throwExpected(message: String): Nothing = throw McpExpectedError(message)

    private fun firstLine(text: String): String = text.lineSequence().firstOrNull { it.isNotBlank() } ?: text
}
