package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.execute.UnboundedWriteDetector
import cn.song.mybatisplus.visualizer.sql.SqlPerfAnalyzer
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * mpsv_mapper_check 工具（T-McpToolset2 / T-MTS2-03，R2）：mapper XML 静态契约核查。
 *
 * 两层产出（N-16 位差落点——CodeHelperPro validate_mybatis_mapper 校验静态文本，
 * 本工具校验的是**渲染后的真实 SQL**）：
 *  1. 契约层：XmlMapperParser 解析错误 + 语句清单（id/kind/parameterType/`#{}` 参数数/`${}` 直替数）；
 *  2. 渲染层（simulate=true）：空模拟参数渲染 → 渲染产物逐条过 UnboundedWriteDetector + SqlPerfAnalyzer。
 *
 * simulate 的空参数上下文（`<if>` 全关 → 空语句）是正常态不算 finding，输出注明语境。
 * 逻辑分层同 [CheckSqlTool]：[mapperCheck] 纯函数可测，挂起方法只做文本定位 + audit 包装。
 */
class MapperCheckTool : McpToolsetSupport("mpsv_mapper_check") {

    @McpTool(name = "mpsv_mapper_check", title = "Static contract check for a mapper XML")
    @McpDescription(
        "Check a MyBatis mapper XML: parse errors, statement inventory " +
            "(id, kind, parameterType, #{} parameter counts, \${} direct-substitution counts — the " +
            "injection surface). With simulate=true, additionally renders every statement with empty " +
            "simulation parameters and runs write-risk and performance analysis on the RENDERED SQL — " +
            "validators return a confidence score; this returns deterministic evidence a static-only " +
            "validator cannot produce (rendering is this plugin's core). Pass 'xmlSnippet' (mapper XML " +
            "text) or 'filePath' (project-relative or absolute). " +
            "Read-only: analysis only — never executes SQL.",
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
    )
    suspend fun mpsv_mapper_check(
        xmlSnippet: String = "",
        filePath: String = "",
        simulate: Boolean = false,
    ): String = audit("mpsv_mapper_check") {
        val text = when {
            xmlSnippet.isNotBlank() -> xmlSnippet
            filePath.isNotBlank() -> ReadAction.compute<String?, Throwable> {
                LocalFileSystem.getInstance().findFileByPath(filePath)?.inputStream
                    ?.use { it.readBytes().decodeToString() }
            } ?: throwExpected(
                McpToolErrors.code(McpToolErrors.SOURCE_NOT_FOUND, "file not found or unreadable: $filePath"),
            )
            else -> throwExpected(
                McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "provide xmlSnippet or filePath."),
            )
        }
        mapperCheck(text, simulate)
    }

    /** 纯函数核心（文本已在手的契约+渲染分析；XmlMapperParser/渲染链零 IDE fixture 依赖）。 */
    internal fun mapperCheck(text: String, simulate: Boolean): String {
        if (text.isBlank()) {
            throwExpected(McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "xmlSnippet/filePath is empty."))
        }
        // XML 解析异常（非法 XML/非 mapper 根元素）转 agent 可读错误——原始异常不是 MCP 契约
        val parsed = try {
            XmlMapperParser().parse(text)
        } catch (e: cn.song.mybatisplus.visualizer.xml.XmlParseException) {
            throwExpected(
                McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "not a valid MyBatis mapper XML: ${e.message}"),
            )
        }
        if (parsed.statements.isEmpty()) {
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.INVALID_ARGUMENT,
                    "no <select|insert|update|delete> statements found" +
                        parsed.errors.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = " — ") { it }.orEmpty(),
                ),
            )
        }
        return buildString {
            append("statements: ").append(parsed.statements.size).append('\n')
            if (parsed.errors.isNotEmpty()) {
                append("parse errors:\n")
                parsed.errors.forEach { append("- ").append(it).append('\n') }
            }
            parsed.statements.forEachIndexed { index, statement ->
                val model = XmlPreviewModel.from(statement, parsed.errors)
                val rendered = model.renderSql()
                append('\n').append("-- statement #").append(index + 1)
                    .append(": ").append(statement.id)
                    .append(" [").append(statement.kind.name).append("]")
                statement.parameterType?.let { append(" parameterType=").append(it) }
                append('\n')
                append("    #{} parameters: ").append(rendered.mappings.size)
                    .append(", \${} substitutions: ").append(rendered.dollarSubstitutions).append('\n')
                rendered.errors.takeIf { it.isNotEmpty() }?.forEach { append("    render note: ").append(it).append('\n') }
                if (simulate) {
                    // 空模拟参数语境：`<if>` 可能全关 → 空/退化语句是正常态，仅如实呈现分析结果
                    if (rendered.sql.isBlank()) {
                        append("    simulate: (empty under empty-parameter context — dynamic branches all closed)\n")
                    } else {
                        val risk = UnboundedWriteDetector.detect(rendered.sql)
                        append("    simulate sql: ").append(rendered.sql.replace('\n', ' ')).append('\n')
                        append("    simulate risk: ")
                        append(if (risk.dangerous) "DANGEROUS" else "bounded")
                        risk.kinds().forEach { append(' ').append(it.name) }
                        append('\n')
                        val perf = SqlPerfAnalyzer.analyze(rendered.sql)
                        if (perf.isNotEmpty()) {
                            append("    simulate perf:\n")
                            perf.forEach { warning ->
                                append("      - [").append(warning.severity).append("] ").append(warning.ruleId)
                                    .append(": ").append(
                                        MpSqlVisualizerBundle.messageEn(
                                            warning.messageKey, *warning.messageArgs.toTypedArray(),
                                        ),
                                    ).append('\n')
                            }
                        }
                    }
                }
                if (index < parsed.statements.size - 1 && simulate) append('\n')
            }
            if (simulate) {
                append("\n(simulation uses an empty-parameter context; pass real parameter forms via ")
                append("mpsv_render_sql for production-shaped evidence)")
            }
        }.trimEnd()
    }
}
