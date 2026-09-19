// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerSchemaContextTool.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import com.intellij.mcpserver.annotations.McpDescription;
import com.intellij.mcpserver.annotations.McpTool;
import com.intellij.mcpserver.annotations.McpToolHintValue;
import com.intellij.mcpserver.annotations.McpToolHints;

import java.util.ArrayList;
import java.util.List;

/**
 * sqlv_schema_context 工具（T-McpToolset2 / T-MTS2-00：一期方法体逐行迁入，按工具拆 toolset 类）。
 *
 * 移植注记：dataSource 的 Kotlin 默认空串、maxTables 的 Kotlin 默认
 * {@link McpSchemaFormatter#DEFAULT_MAX_TABLES} 改为调用方必传（平台对 Java 方法无默认参数机制），
 * 工具内部限幅分支不变。
 */
public class SqlVisualizerSchemaContextTool extends McpToolsetSupport {

    public SqlVisualizerSchemaContextTool() {
        super("sqlv_schema_context");
    }

    @McpTool(name = "sqlv_schema_context", title = "Get compact table/column schema context")
    @McpDescription(
        description = "Return a compact table+column context (names, types, comments, NOT NULL marks) for the given "
            + "tables, resolved from the real data source configured in the IDE (credentials never leave the IDE). "
            + "Pass a comma-separated 'tables' whitelist (1-50 names) and optionally 'dataSource' display name "
            + "when several data sources exist. 'maxTables' caps how many tables are returned (default 8, hard limit 20). "
            + "Deterministic live-schema lookup; pairs with sqlv_render_sql when you need column types "
            + "before writing SQL. Read-only: metadata only, never executes SQL."
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE
    )
    public String sqlv_schema_context(String tables, String dataSource, int maxTables) {
        return audit("sqlv_schema_context", () -> {
            var project = requireProject();
            requireSmart(project);
            List<String> names = new ArrayList<>();
            for (String part : tables.split("[,;\n]")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    names.add(trimmed);
                }
            }
            List<String> sanitized = McpSchemaFormatter.sanitizeTableNames(names);
            if (sanitized == null) {
                throwExpected(
                    McpToolErrors.code(
                        McpToolErrors.INVALID_ARGUMENT,
                        "tables must contain 1.." + McpSchemaFormatter.MAX_TABLE_NAMES
                            + " table names (comma-separated)."
                    )
                );
            }
            McpSchemaContextService.Outcome outcome =
                McpSchemaContextService.buildContext(project, dataSource, sanitized, maxTables);
            if (outcome.error() != null) {
                throwExpected(outcome.error());
            }
            if (outcome.text() == null) {
                throwExpected(McpToolErrors.code(McpToolErrors.SCHEMA_NOT_AVAILABLE, "empty schema context."));
            }
            return outcome.text();
        });
    }
}
