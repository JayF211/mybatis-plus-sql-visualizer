// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerMapperCheckTool.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.execute.UnboundedWriteDetector;
import cn.song.mybatisplus.visualizer.sql.SqlPerfAnalyzer;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser;
import cn.song.mybatisplus.visualizer.xml.XmlMapperModel;
import cn.song.mybatisplus.visualizer.xml.XmlParseException;
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel;
import cn.song.mybatisplus.visualizer.xml.XmlStatement;
import cn.song.mybatisplus.visualizer.xml.RenderedXmlSql;
import com.intellij.mcpserver.annotations.McpDescription;
import com.intellij.mcpserver.annotations.McpTool;
import com.intellij.mcpserver.annotations.McpToolHintValue;
import com.intellij.mcpserver.annotations.McpToolHints;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.nio.charset.StandardCharsets;

/**
 * sqlv_mapper_check 工具（T-McpToolset2 / T-MTS2-03，R2）：mapper XML 静态契约核查。
 *
 * 两层产出（N-16 位差落点——CodeHelperPro validate_mybatis_mapper 校验静态文本，
 * 本工具校验的是<b>渲染后的真实 SQL</b>）：
 *  1. 契约层：XmlMapperParser 解析错误 + 语句清单（id/kind/parameterType/`#{}` 参数数/`${}` 直替数）；
 *  2. 渲染层（simulate=true）：空模拟参数渲染 → 渲染产物逐条过 UnboundedWriteDetector + SqlPerfAnalyzer。
 *
 * simulate 的空参数上下文（`&lt;if&gt;` 全关 → 空语句）是正常态不算 finding，输出注明语境。
 * 逻辑分层同 {@link SqlVisualizerCheckSqlTool}：{@link #mapperCheck} 纯函数可测，挂起方法只做文本定位 + audit 包装。
 *
 * 移植注记：xmlSnippet/filePath/simulate 的 Kotlin 默认值改为调用方必传（平台对 Java 方法无默认
 * 参数机制），内部空值/分支语义与原实现一致。
 */
public class SqlVisualizerMapperCheckTool extends McpToolsetSupport {

    public SqlVisualizerMapperCheckTool() {
        super("sqlv_mapper_check");
    }

    @McpTool(name = "sqlv_mapper_check", title = "Static contract check for a mapper XML")
    @McpDescription(
        description = "Check a MyBatis mapper XML: parse errors, statement inventory "
            + "(id, kind, parameterType, #{} parameter counts, \\${} direct-substitution counts — the "
            + "injection surface). With simulate=true, additionally renders every statement with empty "
            + "simulation parameters and runs write-risk and performance analysis on the RENDERED SQL — "
            + "validators return a confidence score; this returns deterministic evidence a static-only "
            + "validator cannot produce (rendering is this plugin's core). Pass 'xmlSnippet' (mapper XML "
            + "text) or 'filePath' (project-relative or absolute). "
            + "Read-only: analysis only — never executes SQL."
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE
    )
    public String sqlv_mapper_check(String xmlSnippet, String filePath, boolean simulate) {
        return audit("sqlv_mapper_check", () -> {
            String text;
            if (!xmlSnippet.isBlank()) {
                text = xmlSnippet;
            } else if (!filePath.isBlank()) {
                String read = ReadAction.compute(() -> {
                    VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(filePath);
                    if (vFile == null) {
                        return null;
                    }
                    try (var in = vFile.getInputStream()) {
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    } catch (Throwable ignored) {
                        return null;
                    }
                });
                if (read == null) {
                    throwExpected(
                        McpToolErrors.code(McpToolErrors.SOURCE_NOT_FOUND,
                            "file not found or unreadable: " + filePath)
                    );
                }
                text = read;
            } else {
                throwExpected(
                    McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "provide xmlSnippet or filePath.")
                );
                return null; // 不可达：throwExpected 必抛
            }
            return mapperCheck(text, simulate);
        });
    }

    /** 纯函数核心（文本已在手的契约+渲染分析；XmlMapperParser/渲染链零 IDE fixture 依赖）。 */
    public String mapperCheck(String text, boolean simulate) {
        if (text.isBlank()) {
            throwExpected(McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "xmlSnippet/filePath is empty."));
        }
        // XML 解析异常（非法 XML/非 mapper 根元素）转 agent 可读错误——原始异常不是 MCP 契约
        XmlMapperModel parsed;
        try {
            parsed = new XmlMapperParser().parse(text);
        } catch (XmlParseException e) {
            throwExpected(
                McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT,
                    "not a valid MyBatis mapper XML: " + e.getMessage())
            );
            return null; // 不可达：throwExpected 必抛
        }
        if (parsed.statements().isEmpty()) {
            String errorSuffix = "";
            if (!parsed.errors().isEmpty()) {
                errorSuffix = " — " + String.join("; ", parsed.errors());
            }
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.INVALID_ARGUMENT,
                    "no <select|insert|update|delete> statements found" + errorSuffix
                )
            );
        }
        StringBuilder out = new StringBuilder();
        out.append("statements: ").append(parsed.statements().size()).append('\n');
        if (!parsed.errors().isEmpty()) {
            out.append("parse errors:\n");
            for (String error : parsed.errors()) {
                out.append("- ").append(error).append('\n');
            }
        }
        for (int index = 0; index < parsed.statements().size(); index++) {
            XmlStatement statement = parsed.statements().get(index);
            XmlPreviewModel model = XmlPreviewModel.from(statement, parsed.errors());
            RenderedXmlSql rendered = model.renderSql();
            out.append('\n').append("-- statement #").append(index + 1)
                .append(": ").append(statement.id())
                .append(" [").append(statement.kind().name()).append("]");
            // T-StatementNaming（O-19）：语句前导注释名附注（无注释零差异）
            String statementName = cn.song.mybatisplus.visualizer.util.SqlStatementNamer.nameOf(rendered.sql());
            if (statementName != null) {
                out.append(" [").append(statementName).append("]");
            }
            if (statement.parameterType() != null) {
                out.append(" parameterType=").append(statement.parameterType());
            }
            out.append('\n');
            out.append("    #{} parameters: ").append(rendered.mappings().size())
                .append(", \\${} substitutions: ").append(rendered.dollarSubstitutions()).append('\n');
            if (!rendered.errors().isEmpty()) {
                for (String renderError : rendered.errors()) {
                    out.append("    render note: ").append(renderError).append('\n');
                }
            }
            if (simulate) {
                // 空模拟参数语境：`<if>` 可能全关 → 空/退化语句是正常态，仅如实呈现分析结果
                if (rendered.sql().isBlank()) {
                    out.append("    simulate: (empty under empty-parameter context — dynamic branches all closed)\n");
                } else {
                    UnboundedWriteDetector.WriteRisk risk = UnboundedWriteDetector.detect(rendered.sql());
                    out.append("    simulate sql: ")
                        .append(rendered.sql().replace('\n', ' ')).append('\n');
                    out.append("    simulate risk: ");
                    out.append(risk.isDangerous() ? "DANGEROUS" : "bounded");
                    for (UnboundedWriteDetector.FindingKind kind : risk.kinds()) {
                        out.append(' ').append(kind.name());
                    }
                    out.append('\n');
                    var perf = SqlPerfAnalyzer.analyze(rendered.sql());
                    if (!perf.isEmpty()) {
                        out.append("    simulate perf:\n");
                        for (var warning : perf) {
                            out.append("      - [").append(warning.getSeverity()).append("] ").append(warning.getRuleId())
                                .append(": ").append(
                                    MpSqlVisualizerBundle.messageEn(
                                        warning.getMessageKey(), warning.getMessageArgs().toArray()
                                    )
                                ).append('\n');
                        }
                    }
                }
            }
            if (index < parsed.statements().size() - 1 && simulate) {
                out.append('\n');
            }
        }
        if (simulate) {
            out.append("\n(simulation uses an empty-parameter context; pass real parameter forms via ");
            out.append("sqlv_render_sql for production-shaped evidence)");
        }
        return McpExplainFormatter.trimEnd(out.toString());
    }
}
