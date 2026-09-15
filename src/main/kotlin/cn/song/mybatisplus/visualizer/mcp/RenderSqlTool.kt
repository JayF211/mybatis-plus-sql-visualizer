package cn.song.mybatisplus.visualizer.mcp

import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints

/**
 * mpsv_render_sql 工具（T-McpToolset2 / T-MTS2-00：一期 SqlVisualizerToolset 方法体逐行迁入，
 * 按工具拆 toolset 类——平台 per-tool 门控只有 toolset 粒度 isEnabled）。
 */
class RenderSqlTool : McpToolsetSupport("mpsv_render_sql") {

    @McpTool(name = "mpsv_render_sql", title = "Render source code into executable SQL")
    @McpDescription(
        "Render the exact executable SQL behind MyBatis-Plus Wrapper chains, mapper XML statements, " +
            "MyBatis annotation SQL (@Select etc.), JPA @Query/derived queries/CriteriaQuery, HQL and " +
            "JdbcTemplate string SQL in the current IntelliJ project, with real parameter values filled in. " +
            "Pass (filePath, line) of the source location (line is 1-based; omit for the first statement), " +
            "or pass xmlSnippet + sourceType='xml_mapper' for a mapper XML text. When the target yields " +
            "several statements, each is prefixed with a '-- SQL #N' comment line. This is a deterministic " +
            "renderer: it returns the executable SQL itself, not a validation verdict. " +
            "Read-only: never executes SQL.",
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
}
