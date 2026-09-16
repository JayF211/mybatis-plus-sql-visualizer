package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.LogSqlParam
import cn.song.mybatisplus.visualizer.log.SqlSource

/**
 * AgentEvent → LogSqlEntry 纯函数适配（§4.4，M56 T-M56-02）：
 * 字段映射 + 参数还原 + dedupeKey 一致性——与桥通道同 SQL 产出同指纹，
 * 增强接管/回落切换期两通道并存时去重口径统一（SqlLogEntryBuffer.dedupeEnabled 默认开）。
 * 参数还原：按 `?` 序朴素替换（引号包裹字符串值），数量不匹配时保留原 SQL 不替换（防错位）。
 */
object AgentEventAdapter {

    fun toEntry(event: AgentEvent): LogSqlEntry {
        val params = event.params
            .sortedBy { it.first }
            .map { LogSqlParam(it.second, it.third) }
        val restored = restore(event.sql, params)
        return LogSqlEntry(
            rawSql = event.sql,
            restoredSql = restored,
            params = params,
            timestamp = event.timestamp,
            source = SqlSource.AGENT,
            executionTimeMs = event.durationNanos / 1_000_000L,
            sourceFile = event.sourceFile,
            sourceLine = event.sourceLine,
            // T-AgentSourceJump：候选帧透传（IDE 侧跳转在主位置解析失败时按序回退）
            sourceCandidates = event.sourceCandidates,
            // T-M56-06：方法聚焦键（agent 栈帧 {Class}#{method}）——与 mapper 语句 id 同入 mapperStatement
            // 字段语义扩展由 focusMethodKey 统一承载（agent JDBC 层无 mapper id，非冲突）
            mapperStatement = event.methodKey,
            errorText = event.errorText,
            requestTag = event.requestTag,
            updateCount = event.updateCount,
            // T-AgentDialectLoop：连接 URL → 目标库显示名（未知/畸形 URL 为 null，面板不显示角标）
            databaseName = JdbcUrlDialects.displayName(event.url),
        )
    }

    /**
     * 按 `?` 序替换参数为字面量（字符串值加单引号 + 内部引号转义；null → NULL）。
     * 占位符数 != 参数数 → 原样返回（防错位误替换）；跳过引号字面量内的 `?`。
     */
    internal fun restore(sql: String, params: List<LogSqlParam>): String {
        if (params.isEmpty()) return sql
        // 数量守卫：占位符数 != 参数数 → 原样返回（防错位误替换）
        if (countPlaceholders(sql) != params.size) return sql
        val out = StringBuilder(sql.length + 32)
        var pi = 0
        var inSingle = false
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (inSingle) {
                out.append(c)
                if (c == '\'') {
                    // 单引号转义（'' 成对）不终结
                    if (i + 1 < sql.length && sql[i + 1] == '\'') {
                        out.append('\''); i++
                    } else {
                        inSingle = false
                    }
                }
                i++
                continue
            }
            if (c == '\'') {
                inSingle = true
                out.append(c)
                i++
                continue
            }
            if (c == '?') {
                if (pi < params.size) {
                    out.append(renderParam(params[pi]))
                    pi++
                } else {
                    out.append(c)
                }
                i++
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** 占位符计数（跳过引号字面量内的 `?`；与 restore 扫描规则一致）。 */
    private fun countPlaceholders(sql: String): Int {
        var n = 0
        var inSingle = false
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (inSingle) {
                if (c == '\'') {
                    // 单引号转义（'' 成对）不终结
                    if (i + 1 < sql.length && sql[i + 1] == '\'') {
                        i++
                    } else {
                        inSingle = false
                    }
                }
            } else if (c == '\'') {
                inSingle = true
            } else if (c == '?') {
                n++
            }
            i++
        }
        return n
    }

    private fun renderParam(p: LogSqlParam): String = when {
        p.value == null -> "NULL"
        isNumericLike(p.value) && isNumericType(p.type) -> p.value
        else -> "'" + p.value.replace("'", "''") + "'"
    }

    private fun isNumericType(type: String?): Boolean = type != null && type.matches(
        Regex("(Int|Integer|Long|Short|Byte|Double|Float|BigDecimal|BigInteger|Numeric|Decimal)"),
    )

    private fun isNumericLike(v: String): Boolean = v.matches(Regex("-?\\d+(\\.\\d+)?"))
}
