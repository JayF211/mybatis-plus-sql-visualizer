package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.HibernateLogParser
import cn.song.mybatisplus.visualizer.log.JdbcTemplateLogParser
import cn.song.mybatisplus.visualizer.log.MyBatisLogParser
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints

/**
 * mpsv_render_logs 工具（T-McpToolset2 / T-MTS2-01，R1 纯逻辑）：MyBatis / Hibernate /
 * JdbcTemplate 日志文本 → 可执行 SQL。
 *
 * 复用 IDE「日志 → SQL」面板同款三解析器链（feed → closePending → takeEntries，
 * `LogToSqlPanel.computeConversion` 同构、纯计算不触 Swing）；条目直接消费
 * `LogSqlEntry.restoredSql`。前缀参数缺省读插件设置（与面板同源单点），工具参数可逐项覆盖
 * （null/空串 = 不覆盖，遵循设置）。逻辑分层同 [CheckSqlTool]：[renderLogs] 纯函数可测。
 */
class RenderLogsTool : McpToolsetSupport("mpsv_render_logs") {

    @McpTool(name = "mpsv_render_logs", title = "Restore MyBatis/Hibernate/JdbcTemplate logs into executable SQL")
    @McpDescription(
        "Restore executable SQL from application log text (MyBatis 'Preparing:'/'Parameters:' pairs, " +
            "Hibernate 'Hibernate:' SQL with 'binding parameter' lines, or Spring JdbcTemplate debug lines). " +
            "Pass the raw multi-line log text as 'logText'. Prefix overrides (sqlPrefix/paramPrefix/" +
            "hibernateSqlPrefix/hibernateParamPrefix) and 'mixedPrefixMatching' are optional and default to " +
            "the plugin's log-capture settings. Each restored statement is prefixed with a '-- SQL #N' " +
            "comment line carrying its source format. Deterministic restoration, not validation — pair with " +
            "mpsv_check_sql to assess write risk before executing. Read-only: never executes SQL.",
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
    )
    suspend fun mpsv_render_logs(
        logText: String,
        sqlPrefix: String = "",
        paramPrefix: String = "",
        hibernateSqlPrefix: String = "",
        hibernateParamPrefix: String = "",
        mixedPrefixMatching: Boolean? = null,
    ): String = audit("mpsv_render_logs") {
        renderLogs(
            logText, sqlPrefix, paramPrefix, hibernateSqlPrefix, hibernateParamPrefix, mixedPrefixMatching,
        )
    }

    /** 纯函数核心：defaults 传 null 时从插件设置装配（服务未注册兜底内置默认）。 */
    internal fun renderLogs(
        logText: String,
        sqlPrefix: String = "",
        paramPrefix: String = "",
        hibernateSqlPrefix: String = "",
        hibernateParamPrefix: String = "",
        mixedPrefixMatching: Boolean? = null,
        defaults: LogPrefixDefaults? = null,
    ): String {
        if (logText.isBlank()) {
            throwExpected(McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "logText is empty."))
        }
        val effectiveDefaults = defaults ?: runCatching {
            PluginSettings.getInstance().state.let { state ->
                LogPrefixDefaults(
                    sqlPrefix = state.logSqlPrefix,
                    paramPrefix = state.logParamPrefix,
                    mixedPrefixMatching = state.logMixedPrefix,
                    hibernateSqlPrefix = state.logHibernateSqlPrefix,
                    hibernateParamPrefix = state.logHibernateParamPrefix,
                    jdbcEnabled = state.logJdbcEnabled,
                    maxParseLength = state.logMaxParseLength,
                )
            }
        }.getOrDefault(LogPrefixDefaults())
        val entries = renderLogEntries(
            logText, sqlPrefix, paramPrefix, hibernateSqlPrefix, hibernateParamPrefix,
            mixedPrefixMatching, effectiveDefaults,
        )
        if (entries.isEmpty()) {
            // 0 条是合法结果（日志里确实没有匹配对）——正常返回 + 指引，不走错误通道
            return "No log entries recognized. Expected MyBatis '${effectiveDefaults.sqlPrefix}' / " +
                "'${effectiveDefaults.paramPrefix}' pairs (or Hibernate / JdbcTemplate formats) with SQL and " +
                "parameters on adjacent lines. Check the prefixes or pass explicit overrides."
        }
        return buildString {
            append("Restored ").append(entries.size).append(" statement(s):\n\n")
            entries.forEachIndexed { index, entry ->
                append("-- SQL #").append(index + 1)
                append(" [").append(entry.source.name.lowercase()).append("]")
                if (entry.sourceLine > 0) append(" log line ").append(entry.sourceLine)
                entry.executionTimeMs?.let { append(" (").append(it).append("ms)") }
                append("\n").append(entry.restoredSql)
                if (index < entries.size - 1) append("\n\n")
            }
        }
    }

    data class LogPrefixDefaults(
        val sqlPrefix: String = "Preparing:",
        val paramPrefix: String = "Parameters:",
        val mixedPrefixMatching: Boolean = true,
        val hibernateSqlPrefix: String = "Hibernate:",
        val hibernateParamPrefix: String = "binding parameter",
        val jdbcEnabled: Boolean = true,
        val maxParseLength: Int = 20_000,
    )

    companion object {
        /**
         * 纯函数核心（普通 JUnit 直测）：装配三解析器 → 逐行喂 → 合并条目。
         * 覆盖参数空串/null 回落 [defaults]；解析器装配与 `LogToSqlPanel.buildParsers` 同参同构。
         */
        fun renderLogEntries(
            logText: String,
            sqlPrefix: String = "",
            paramPrefix: String = "",
            hibernateSqlPrefix: String = "",
            hibernateParamPrefix: String = "",
            mixedPrefixMatching: Boolean? = null,
            defaults: LogPrefixDefaults = LogPrefixDefaults(),
        ) = Triple(
            MyBatisLogParser(
                sqlPrefix = sqlPrefix.ifBlank { defaults.sqlPrefix },
                paramPrefix = paramPrefix.ifBlank { defaults.paramPrefix },
                mixedPrefix = mixedPrefixMatching ?: defaults.mixedPrefixMatching,
                maxParseLength = defaults.maxParseLength,
            ),
            HibernateLogParser(
                sqlPrefix = hibernateSqlPrefix.ifBlank { defaults.hibernateSqlPrefix },
                paramPrefix = hibernateParamPrefix.ifBlank { defaults.hibernateParamPrefix },
                mixedPrefix = mixedPrefixMatching ?: defaults.mixedPrefixMatching,
                maxParseLength = defaults.maxParseLength,
            ),
            JdbcTemplateLogParser(
                enabled = defaults.jdbcEnabled,
                maxParseLength = defaults.maxParseLength,
            ),
        ).let { (myBatis, hibernate, jdbc) ->
            logText.lineSequence().forEach { line ->
                myBatis.feed(line)
                hibernate.feed(line)
                jdbc.feed(line)
            }
            myBatis.closePending()
            hibernate.closePending()
            jdbc.closePending()
            myBatis.takeEntries() + hibernate.takeEntries() + jdbc.takeEntries()
        }
    }
}
