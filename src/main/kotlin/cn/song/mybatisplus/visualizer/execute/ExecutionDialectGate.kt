package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.IdeaSqlContextBridge
import cn.song.mybatisplus.visualizer.sql.UrlSubprotocolDialects

/** 最终执行目标方言安全闸的纯决策与反射隔离目标解析。 */
object ExecutionDialectGate {
    enum class RenderMode { AUTO, MANUAL, UNKNOWN }
    enum class Intent { QUERY, DML, EXPLAIN, TEMPLATE }
    enum class Status {
        MATCH,
        AUTO_RERENDER_REQUIRED,
        MANUAL_CONFLICT,
        RERENDER_UNAVAILABLE,
        TARGET_UNKNOWN_QUERY_CONFIRM,
        TARGET_UNKNOWN_BLOCK,
        RENDER_UNKNOWN_QUERY_CONFIRM,
        RENDER_UNKNOWN_BLOCK,
    }

    data class Request(
        val renderMode: RenderMode,
        val renderedDialectName: String?,
        val intent: Intent,
        val canLosslesslyRerender: Boolean = false,
        val userTextUntouched: Boolean = false,
        val moduleKey: String? = null,
    )

    data class Verdict(
        val status: Status,
        val renderedDialectName: String?,
        val targetDialectName: String?,
    )

    data class Target(
        val dataSource: Any,
        val stableId: String?,
        val displayName: String?,
        val dialectName: String?,
        val platformLanguageId: String?,
    )

    /**
     * O-145 W7 尚未携带 Request 的显式入口；完成态必须为空。
     * [ExecutionDialectGateTest] 同时逐入口扫描调用参数，防止只清清单、不接生产链的空绿。
     */
    internal val uncoveredEntryPoints: Set<String> = emptySet()

    /** 原始日志/临时 SQL 没有可信渲染来源；查询必须显式确认，写入与 EXPLAIN 一律阻断。 */
    fun rawSqlRequest(sql: String): Request {
        val firstKeyword = SqlTypeClassifier.getFirstKeyword(sql)
        val intent = when {
            firstKeyword.equals("EXPLAIN", ignoreCase = true) -> Intent.EXPLAIN
            SqlTypeClassifier.isQuery(sql) -> Intent.QUERY
            else -> Intent.DML
        }
        return Request(RenderMode.UNKNOWN, null, intent)
    }

    fun evaluate(request: Request, targetDialectName: String?): Verdict {
        val rendered = known(request.renderedDialectName)
        val target = known(targetDialectName)
        if (request.renderMode == RenderMode.UNKNOWN && rendered == null) {
            val status = if (request.intent == Intent.QUERY) Status.RENDER_UNKNOWN_QUERY_CONFIRM
                else Status.RENDER_UNKNOWN_BLOCK
            return Verdict(status, null, target)
        }
        if (target == null) {
            val status = if (request.intent == Intent.QUERY) Status.TARGET_UNKNOWN_QUERY_CONFIRM
                else Status.TARGET_UNKNOWN_BLOCK
            return Verdict(status, rendered, null)
        }
        if (rendered != null && rendered == target) return Verdict(Status.MATCH, rendered, target)
        if (request.renderMode == RenderMode.MANUAL) {
            return Verdict(Status.MANUAL_CONFLICT, rendered, target)
        }
        if (request.renderMode == RenderMode.AUTO && request.canLosslesslyRerender && request.userTextUntouched) {
            return Verdict(Status.AUTO_RERENDER_REQUIRED, rendered, target)
        }
        return Verdict(Status.RERENDER_UNAVAILABLE, rendered, target)
    }

    @Volatile
    internal var sessionDataSourceForTest: ((Any) -> Any?)? = null

    @Volatile
    internal var consoleDataSourceForTest: ((Any) -> Any?)? = null

    fun fromSession(session: Any): Target? {
        val ds = sessionDataSourceForTest?.invoke(session) ?: runCatching {
            val loader = ExecutionDialectGate::class.java.classLoader
            val sessionClass = Class.forName(DataSourceSelector.SESSION_CLASS, false, loader)
            val point = sessionClass.getMethod("getConnectionPoint").invoke(session) ?: return null
            val pointClass = Class.forName("com.intellij.database.dataSource.DatabaseConnectionPoint", false, loader)
            pointClass.getMethod("getDataSource").invoke(point)
        }.getOrNull()
        return ds?.let(::fromDataSource)
    }

    fun fromConsole(console: Any): Target? {
        val ds = consoleDataSourceForTest?.invoke(console) ?: runCatching {
            val jdbcConsole = Class.forName(
                "com.intellij.database.console.JdbcConsole",
                false,
                ExecutionDialectGate::class.java.classLoader,
            )
            jdbcConsole.getMethod("getDataSource").invoke(console)
        }.getOrNull()
        return ds?.let(::fromDataSource)
    }

    fun fromDataSource(dataSource: Any): Target {
        val url = ExecDialectRouting.dataSourceUrl(dataSource)
        val platformId = ExecDialectRouting.platformDialectId(dataSource)
        // JDBC 子协议是当前目标的精确厂商事实；平台 Language 可能只是 MySQL/PG 家族兜底。
        val dialectName = targetDialectName(url, platformId)
        return Target(
            dataSource = dataSource,
            stableId = DataSourceSettings.getUniqueId(dataSource),
            displayName = DataSourceSettings.getName(dataSource).takeUnless { it == "Unknown" },
            dialectName = dialectName,
            platformLanguageId = platformId,
        )
    }

    internal fun targetDialectName(jdbcUrl: String?, platformLanguageId: String?): String? =
        UrlSubprotocolDialects.dialectName(jdbcUrl)
            ?: IdeaSqlContextBridge.pluginDialectName(platformLanguageId)

    private fun known(name: String?): String? = name?.let { candidate ->
        DialectRegistry.dialects.firstOrNull { it.name.equals(candidate, ignoreCase = true) }?.name
    }
}
