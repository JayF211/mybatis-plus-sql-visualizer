// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerSchemaContextTool.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import com.intellij.mcpserver.annotations.McpDescription;
import com.intellij.mcpserver.annotations.McpTool;
import com.intellij.mcpserver.annotations.McpToolHintValue;
import com.intellij.mcpserver.annotations.McpToolHints;
import com.intellij.openapi.project.Project;

import java.util.List;

/**
 * sqlv_schema_context 工具（T-McpToolset2 / T-MTS2-00：一期方法体逐行迁入，按工具拆 toolset 类）。
 *
 * <p><b>P3 / T-McpArgLooseDecode（T-SyncK2JMaster115）：入参改用 {@link McpStringArg}/{@link McpIntArg}
 * 载体而非裸 {@code String}/{@code int}。</b>根因（2026-09-20 沙盒实测，栈见 idea.log）：平台参数绑定走
 * {@code Json.decodeFromJsonElement(serializerOrNull(paramType), element)}（262
 * {@code CallableBridge.call:52}），而 kotlinx 把「JSON 数组/对象 → String」解进
 * {@code TaggedDecoder.popTag(Tagged.kt:320)} 对**空 tag 栈**调 {@code ArrayList.remove(-1)}
 * → 客户端只拿到 {@code MCP tool call has been failed: Index -1 out of bounds for length 0}，
 * **本工具方法体根本没进**（同一次调用 idea.log 里 {@code [MCP-AUDIT]} 行缺失可证）。载体形态下：
 * {@code serializer()} 由 {@link McpStringArg.Companion} 提供（{@code serializerOrNull} 反射命中，
 * 无需 serialization 编译器插件），descriptor 仍是 primitive STRING/INT——平台生成的 inputSchema 与
 * 改前**逐字节相同**，但任意 JSON 形态都能解进来，形态记进 {@link McpArgKind} 由校验层转
 * {@code INVALID_ARGUMENT}。</p>
 *
 * <p><b>k2j 侧形态偏离（与 master 的差异，如实登记）</b>：① 本方法**非 suspend**（k2j 既有移植决策：
 * 审计块的挂起包装退化为同步包装，语义等价，见 {@link McpToolsetSupport} 文件头）；
 * ② {@code dataSource} / {@code maxTables} **无默认值**——Java 方法无法表达 Kotlin 默认参数，
 * kotlin-reflect 对 Java 形参恒报 {@code isOptional()=false}（本轮 kotlin-reflect 2.0.0 探针实证），
 * 故平台生成的 inputSchema 会把这参标为必填，与 master 的 optional 语义有差。此为 k2j 全部 MCP 工具的
 * 共性限制（非本轮引入），补 optional 需在平台侧另设机制，本轮不做。</p>
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
            + "Table names are matched inside the data source's own schema/database (taken from its JDBC URL); "
            + "same-named tables of other schemas (e.g. the mysql system database) are never mixed in. "
            + "Deterministic live-schema lookup; pairs with sqlv_render_sql when you need column types "
            + "before writing SQL. Read-only: metadata only, never executes SQL."
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE
    )
    public String sqlv_schema_context(McpStringArg tables, McpStringArg dataSource, McpIntArg maxTables) {
        return audit("sqlv_schema_context", () -> {
            Project project = requireProject();
            requireSmart(project);
            // 形态与取值一律走参数校验（P3）：任一不符都是 INVALID_ARGUMENT，绝不落到平台内部异常
            List<String> names = McpSchemaFormatter.parseTableNames(tables);
            if (names == null) {
                return throwExpected(McpSchemaFormatter.tableNamesError(tables));
            }
            String alias = McpSchemaFormatter.parseDataSource(dataSource);
            if (alias == null) {
                return throwExpected(McpSchemaFormatter.dataSourceError(dataSource));
            }
            Integer limit = McpSchemaFormatter.parseMaxTables(maxTables);
            if (limit == null) {
                return throwExpected(McpSchemaFormatter.maxTablesError(maxTables));
            }
            McpSchemaContextService.Outcome outcome =
                McpSchemaContextService.buildContext(project, alias, names, limit);
            if (outcome.error() != null) {
                return throwExpected(outcome.error());
            }
            if (outcome.text() == null) {
                return throwExpected(McpToolErrors.code(McpToolErrors.SCHEMA_NOT_AVAILABLE, "empty schema context."));
            }
            return outcome.text();
        });
    }
}
