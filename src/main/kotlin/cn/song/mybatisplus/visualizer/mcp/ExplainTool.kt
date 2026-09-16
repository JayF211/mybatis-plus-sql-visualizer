package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * mpsv_explain 工具（T-McpToolset2 / T-MTS2-00：一期方法体逐行迁入，按工具拆 toolset 类；
 * resolveExplainTarget/executeExplain/ExplainTarget 随本类走）。
 */
class ExplainTool : McpToolsetSupport("mpsv_explain") {

    @McpTool(name = "mpsv_explain", title = "Explain a SELECT against a configured data source")
    @McpDescription(
        "Run EXPLAIN for a single read-only SELECT statement against a real data source configured in the IDE " +
            "and return the plan text (MySQL/MariaDB/PostgreSQL/Oracle/SQL Server/H2/DM/KingBase dialect dispatch, " +
            "dialect auto-detected from the data source URL unless overridden). Deterministic plan rendering for " +
            "a statement you already have — pair with mpsv_render_sql to produce it first. " +
            "Strictly read-only: the statement must be a single plain SELECT (no locking reads, no INTO, no writes); " +
            "rejections are fail-closed.",
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
        val raw = executeExplain(target)
        // notices 节尾附在方言行后（T-NoticeChannel / O-16）；空清单零追加——输出逐字节与
        // 现状一致（防回归硬门禁）。失败路径 notices 无从产生：executeExplain 直接 throwExpected，
        // RawResult 只在成功时存在，天然满足 DBX「出错语句的 notices 丢弃」口径。
        McpExplainFormatter.format(raw) + "\n\ndialect: ${target.dialectName}" +
            McpExplainFormatter.formatNotices(raw.notices)
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
                // T-ErrorLocate T-EL-04：驱动错误消息携带位置信号（PG `Position: N` / `at line N`）时
                // 附 errorPosition 文本行——agent 可定位再自修复；匹配不到/多 marker 即不加（诚实降级，
                // ErrorPositionExtractor 的 marker 单一性门控与「基建语句不带位置」同源）。
                val causeMsg = e.message ?: e.javaClass.simpleName
                val pos = cn.song.mybatisplus.visualizer.execute.ErrorPositionExtractor.extractPosition(causeMsg)
                throwExpected(
                    McpToolErrors.code(
                        McpToolErrors.DATASOURCE_UNREACHABLE,
                        "EXPLAIN failed on '${target.dialectName}' data source: ${firstLine(causeMsg)}" +
                            if (pos != null) {
                                val kind = if (pos.second) "char" else "line"
                                "\nerrorPosition: $kind ${pos.first}"
                            } else "",
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
}
