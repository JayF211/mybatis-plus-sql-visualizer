package cn.song.mybatisplus.visualizer.mcp

import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints

/**
 * sqlv_schema_context 工具（T-McpToolset2 / T-MTS2-00：一期方法体逐行迁入，按工具拆 toolset 类）。
 */
class SqlVisualizerSchemaContextTool : McpToolsetSupport("sqlv_schema_context") {

    @McpTool(name = "sqlv_schema_context", title = "Get compact table/column schema context")
    @McpDescription(
        "Return a compact table+column context (names, types, comments, NOT NULL marks) for the given " +
            "tables, resolved from the real data source configured in the IDE (credentials never leave the IDE). " +
            "Pass a comma-separated 'tables' whitelist (1-50 names) and optionally 'dataSource' display name " +
            "when several data sources exist. 'maxTables' caps how many tables are returned (default 8, hard limit 20). " +
            "Deterministic live-schema lookup; pairs with sqlv_render_sql when you need column types " +
            "before writing SQL. Read-only: metadata only, never executes SQL.",
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
    )
    suspend fun sqlv_schema_context(
        tables: String,
        dataSource: String = "",
        maxTables: Int = McpSchemaFormatter.DEFAULT_MAX_TABLES,
    ): String = audit("sqlv_schema_context") {
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
}
