// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerRenderSqlTool.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import com.intellij.mcpserver.annotations.McpDescription;
import com.intellij.mcpserver.annotations.McpTool;
import com.intellij.mcpserver.annotations.McpToolHintValue;
import com.intellij.mcpserver.annotations.McpToolHints;

import java.util.Objects;

/**
 * sqlv_render_sql 工具（T-McpToolset2 / T-MTS2-00：一期 SqlVisualizerToolset 方法体逐行迁入，
 * 按工具拆 toolset 类——平台 per-tool 门控只有 toolset 粒度 isEnabled）。
 *
 * 移植注记：filePath/line/xmlSnippet/sourceType 的 Kotlin 默认值改为调用方必传（平台对 Java
 * 方法无默认参数机制）；line 的「0 = 未提供」语义与 {@link McpRenderInput} 内部口径不变。
 */
public class SqlVisualizerRenderSqlTool extends McpToolsetSupport {

    public SqlVisualizerRenderSqlTool() {
        super("sqlv_render_sql");
    }

    @McpTool(name = "sqlv_render_sql", title = "Render source code into executable SQL")
    @McpDescription(
        description = "Render the exact executable SQL behind MyBatis-Plus Wrapper chains, mapper XML statements, "
            + "MyBatis annotation SQL (@Select etc.), JPA @Query/derived queries/CriteriaQuery, HQL and "
            + "JdbcTemplate string SQL in the current IntelliJ project, with real parameter values filled in. "
            + "Pass (filePath, line) of the source location (line is 1-based; omit for the first statement), "
            + "or pass xmlSnippet + sourceType='xml_mapper' for a mapper XML text. One statement per call: "
            + "when several candidates match the location, the first is rendered and the output notes it; "
            + "for multi-statement log text use sqlv_render_logs. This is a deterministic "
            + "renderer: it returns the executable SQL itself, not a validation verdict. "
            + "Read-only: never executes SQL."
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE
    )
    public String sqlv_render_sql(String filePath, int line, String xmlSnippet, String sourceType) {
        return audit("sqlv_render_sql", () -> {
            var project = requireProject();
            var parsedPair = McpRenderInput.parse(filePath, line > 0 ? line : null, xmlSnippet, sourceType);
            McpRenderInput.Parsed parsed = parsedPair.getFirst();
            String error = parsedPair.getSecond();
            if (parsed == null) {
                throwExpected(Objects.requireNonNull(error));
            }
            requireSmart(project);
            McpSourceLocator.Outcome outcome;
            if (parsed.filePath() != null) {
                outcome = McpSourceLocator.renderAtLine(project, parsed.filePath(), parsed.line());
            } else if (parsed.xmlSnippet() != null) {
                outcome = McpSourceLocator.renderXmlSnippet(project, parsed.xmlSnippet());
            } else {
                throwExpected(
                    McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT,
                        "provide filePath+line or xmlSnippet+sourceType.")
                );
                return null; // 不可达：throwExpected 必抛
            }
            if (outcome.error() != null) {
                throwExpected(outcome.error());
            }
            StringBuilder out = new StringBuilder();
            out.append(outcome.sql());
            if (outcome.paramTable() != null) {
                out.append("\n\n").append(outcome.paramTable());
            }
            out.append("\n\nsourceType: ").append(outcome.sourceType() != null ? outcome.sourceType() : "unknown");
            if (outcome.tableName() != null) {
                out.append("\ntable: ").append(outcome.tableName());
            }
            if (outcome.warning() != null) {
                out.append("\nwarning: ").append(outcome.warning());
            }
            if (outcome.degraded() != null) {
                out.append("\nnote: ").append(outcome.degraded());
            }
            return out.toString();
        });
    }
}
