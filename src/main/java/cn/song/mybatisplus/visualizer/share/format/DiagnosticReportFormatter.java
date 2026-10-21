// 自 master share/format/DiagnosticReportFormatter.kt 移植（R50 一键诊断报告）
package cn.song.mybatisplus.visualizer.share.format;

import cn.song.mybatisplus.visualizer.execute.ExecuteConfirmMessages;
import cn.song.mybatisplus.visualizer.execute.UnboundedWriteDetector;
import cn.song.mybatisplus.visualizer.sql.DialectCapabilities;
import cn.song.mybatisplus.visualizer.sql.DialectRegistry;
import cn.song.mybatisplus.visualizer.sql.SqlPerfAnalyzer;
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning;
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R50 一键诊断报告（诊断面聚合进既有分享通道；V74 已证伪降级路径）。
 *
 * <p>七环诊断产物散在五处（SQL+参数 / 20 条 perf 规则 / EXPLAIN 索引建议 / 无界写检测 / 回滚预案 /
 * 方言能力），发 MR 或缺陷单要手动拼。本类把它们聚合成一份自包含 Markdown / HTML 报告，
 * 经既有 {@link cn.song.mybatisplus.visualizer.share.ui.ShareDialog} 分享通道导出（不新增 MCP 工具、不新增弹窗形态）。</p>
 *
 * <h2>V74 诚实降级（IDE 面 EXPLAIN 建议数据不可得）</h2>
 * T-EI-01 定案：IDE 内嵌原生控制台的结果行<b>无程序化读取面</b>（网格由 DBN 托管），
 * {@link cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor} 的建议节只挂在 MCP 面。故 IDE 侧诊断报告的
 * 「EXPLAIN 索引建议」段<b>如实说明不可得 + 给出获取路径</b>（不臆造、不静默留白）；
 * 回滚预案在无快照路径时（{@code Rating.UNABLE}）输出 {@link RollbackSqlGenerator} 的原始降级警示。
 *
 * <p>纯逻辑层：只依赖分析器（无 IDE 类型），普通 JUnit 可直接覆盖格式与聚合。</p>
 */
public final class DiagnosticReportFormatter {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DiagnosticReportFormatter() {
    }

    /**
     * 报告输入（各诊断段为<b>已渲染文本行</b>——聚合层不重复分析，只组装）。
     */
    public record Input(
            @NotNull String sql,
            @NotNull List<String> sources,
            @NotNull Map<String, String> paramValues,
            @Nullable String tableName,
            @Nullable String dialect,
            @Nullable String source,
            /** 性能建议行（空 = 未发现问题）。 */
            @NotNull List<String> perfLines,
            /** EXPLAIN 索引建议行；<b>null = IDE 面不可得</b>（输出诚实说明段）。 */
            @Nullable List<String> explainLines,
            /** 无界写检测行（空 = 未发现）。 */
            @NotNull List<String> unboundedLines,
            /** 回滚预案行（含降级说明）。 */
            @NotNull List<String> rollbackLines,
            /** 方言能力矩阵行（R52 呈现层触点二）。 */
            @NotNull List<String> capabilityLines) {

        /** 全默认形态（除 sql 外均为空/未取到）。 */
        public Input(@NotNull String sql) {
            this(sql, List.of(), Map.of(), null, null, null, List.of(), null, List.of(), List.of(), List.of());
        }
    }

    /** 从 SQL + 方言名聚合各诊断段（纯逻辑；IDE 侧与测试共用）。 */
    public static Input buildInput(
            @NotNull String sql,
            @Nullable String dialectName,
            @NotNull List<String> sources,
            @NotNull Map<String, String> paramValues,
            @Nullable String tableName,
            @Nullable String source) {
        List<String> perfLines = new ArrayList<>();
        for (PerfWarning w : SqlPerfAnalyzer.analyze(sql)) {
            String text;
            try {
                text = MpSqlVisualizerBundle.message(w.messageKey(), w.messageArgs().toArray());
            } catch (Throwable ignored) {
                text = w.messageKey();
            }
            perfLines.add("[" + w.severity().name() + "] " + text);
        }
        List<UnboundedWriteDetector.FindingKind> kinds =
                new ArrayList<>(UnboundedWriteDetector.detect(sql).kinds());
        Collections.sort(kinds);
        List<String> unboundedLines = new ArrayList<>();
        for (UnboundedWriteDetector.FindingKind kind : kinds) {
            String text;
            try {
                text = MpSqlVisualizerBundle.message(ExecuteConfirmMessages.messageKeyOf(kind));
            } catch (Throwable ignored) {
                text = kind.name();
            }
            unboundedLines.add(text);
        }
        RollbackSqlGenerator.RollbackResult rollback = RollbackSqlGenerator.generate(sql);
        List<String> rollbackLines = buildRollbackLines(rollback);
        List<String> capabilityLines = List.of(
                DialectCapabilities.summaryText(DialectRegistry.INSTANCE.byName(dialectName)).split("\n"));
        return new Input(
                sql,
                sources,
                paramValues,
                tableName,
                dialectName,
                source,
                perfLines,
                // IDE 面无 EXPLAIN 结果可读（V74）→ null 触发诚实说明段
                null,
                unboundedLines,
                rollbackLines,
                capabilityLines);
    }

    /** 回滚预案段：评级 + 自查模板 + 语句条数；UNABLE 时输出原始降级警示（V74 回滚降级文案）。 */
    private static List<String> buildRollbackLines(RollbackSqlGenerator.RollbackResult result) {
        List<String> lines = new ArrayList<>();
        lines.add(MpSqlVisualizerBundle.message("diag.report.rollback.rating", result.rating().name()));
        if (result.targetTable() != null) {
            lines.add(MpSqlVisualizerBundle.message("diag.report.rollback.table", result.targetTable()));
        }
        if (result.snapshotSelectSql() != null) {
            lines.add(MpSqlVisualizerBundle.message("diag.report.rollback.snapshot", result.snapshotSelectSql()));
        }
        if (result.countCheckSql() != null) {
            lines.add(MpSqlVisualizerBundle.message("diag.report.rollback.countCheck", result.countCheckSql()));
        }
        if (result.statements().isEmpty()) {
            lines.add(MpSqlVisualizerBundle.message("diag.report.rollback.none"));
        } else {
            lines.add(MpSqlVisualizerBundle.message("diag.report.rollback.statementCount", result.statements().size()));
        }
        for (RollbackSqlGenerator.RollbackWarning warning : result.warnings()) {
            String text;
            try {
                text = MpSqlVisualizerBundle.message(warning.messageKey(), warning.messageArgs().toArray());
            } catch (Throwable ignored) {
                text = warning.messageKey();
            }
            lines.add(text);
        }
        return lines;
    }

    /** 生成 Markdown 诊断报告（五段聚合 + 方言能力段）。 */
    public static String format(Input input) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        StringBuilder sb = new StringBuilder(2048);
        sb.append("# ").append(MpSqlVisualizerBundle.message("diag.report.title")).append("\n\n");
        sb.append("> ").append(metaLine(input, timestamp)).append("\n\n");

        sb.append("## ").append(MpSqlVisualizerBundle.message("diag.report.section.sql")).append("\n");
        sb.append("```sql\n").append(input.sql().trim()).append("\n```\n\n");

        sb.append("## ").append(MpSqlVisualizerBundle.message("diag.report.section.params")).append("\n");
        appendParams(sb, input);

        sb.append("\n## ").append(MpSqlVisualizerBundle.message("diag.report.section.perf")).append("\n");
        appendLines(sb, input.perfLines(), MpSqlVisualizerBundle.message("diag.report.perf.none"));

        sb.append("\n## ").append(MpSqlVisualizerBundle.message("diag.report.section.explain")).append("\n");
        appendExplain(sb, input);

        sb.append("\n## ").append(MpSqlVisualizerBundle.message("diag.report.section.unbounded")).append("\n");
        appendLines(sb, input.unboundedLines(), MpSqlVisualizerBundle.message("diag.report.unbounded.none"));

        sb.append("\n## ").append(MpSqlVisualizerBundle.message("diag.report.section.rollback")).append("\n");
        appendLines(sb, input.rollbackLines(), MpSqlVisualizerBundle.message("diag.report.rollback.none"));

        sb.append("\n## ").append(MpSqlVisualizerBundle.message("diag.report.section.capabilities")).append("\n");
        appendLines(sb, input.capabilityLines(), MpSqlVisualizerBundle.message("diag.report.none"));

        return sb.toString();
    }

    /** 生成自包含 HTML 诊断报告（同段聚合，供浏览器查看/附件发送）。 */
    public static String formatHtml(Input input) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">\n");
        sb.append("<title>").append(escapeHtml(MpSqlVisualizerBundle.message("diag.report.title"))).append("</title>\n");
        sb.append("<style>\n");
        sb.append("body{font-family:Consolas,Monaco,'Courier New',monospace;margin:20px;background:#282c34;color:#abb2bf}\n");
        sb.append("h1{color:#61afef;font-size:18px;margin-bottom:8px}\n");
        sb.append("h2{color:#61afef;font-size:14px;margin-top:20px}\n");
        sb.append(".meta{color:#7f848e;font-size:12px;margin-bottom:16px}\n");
        sb.append("pre{background:#1e1e1e;border-radius:6px;padding:16px;overflow-x:auto}\n");
        sb.append("ul{margin:4px 0 0 0;padding-left:20px}\nli{font-size:13px;line-height:1.6}\n");
        sb.append(".none{color:#7f848e;font-style:italic}\n");
        sb.append("</style></head><body>\n");
        sb.append("<h1>").append(escapeHtml(MpSqlVisualizerBundle.message("diag.report.title"))).append("</h1>\n");
        sb.append("<div class=\"meta\">").append(escapeHtml(metaLine(input, timestamp))).append("</div>\n");

        sb.append("<h2>").append(escapeHtml(MpSqlVisualizerBundle.message("diag.report.section.sql"))).append("</h2>\n");
        sb.append("<pre><code>").append(escapeHtml(input.sql().trim())).append("</code></pre>\n");

        sb.append("<h2>").append(escapeHtml(MpSqlVisualizerBundle.message("diag.report.section.params"))).append("</h2>\n");
        appendParamsHtml(sb, input);

        appendSectionHtml(sb, "diag.report.section.perf", input.perfLines(), "diag.report.perf.none");
        appendExplainHtml(sb, input);
        appendSectionHtml(sb, "diag.report.section.unbounded", input.unboundedLines(), "diag.report.unbounded.none");
        appendSectionHtml(sb, "diag.report.section.rollback", input.rollbackLines(), "diag.report.rollback.none");
        appendSectionHtml(sb, "diag.report.section.capabilities", input.capabilityLines(), "diag.report.none");

        sb.append("</body></html>");
        return sb.toString();
    }

    // ---------- 段组装辅助 ----------

    private static String metaLine(Input input, String timestamp) {
        List<String> parts = new ArrayList<>();
        if (input.source() != null) {
            parts.add(MpSqlVisualizerBundle.message("diag.report.source", input.source()));
        }
        if (input.tableName() != null) {
            parts.add(MpSqlVisualizerBundle.message("diag.report.table", input.tableName()));
        }
        if (input.dialect() != null) {
            parts.add(MpSqlVisualizerBundle.message("diag.report.dialect", input.dialect()));
        }
        parts.add(MpSqlVisualizerBundle.message("diag.report.generated", timestamp));
        return String.join(" | ", parts);
    }

    private static void appendParams(StringBuilder sb, Input input) {
        if (input.sources().isEmpty() && input.paramValues().isEmpty()) {
            sb.append(MpSqlVisualizerBundle.message("diag.report.params.none")).append("\n");
            return;
        }
        sb.append("| ").append(MpSqlVisualizerBundle.message("diag.report.params.name")).append(" | ")
                .append(MpSqlVisualizerBundle.message("diag.report.params.value")).append(" |\n");
        sb.append("| --- | --- |\n");
        List<String> names = input.sources().isEmpty()
                ? new ArrayList<>(input.paramValues().keySet()) : input.sources();
        for (String name : names) {
            String value = input.paramValues().getOrDefault(name, "");
            sb.append("| ").append(name).append(" | ").append(value.isEmpty() ? "?" : value).append(" |\n");
        }
    }

    private static void appendParamsHtml(StringBuilder sb, Input input) {
        if (input.sources().isEmpty() && input.paramValues().isEmpty()) {
            sb.append("<p class=\"none\">")
                    .append(escapeHtml(MpSqlVisualizerBundle.message("diag.report.params.none")))
                    .append("</p>\n");
            return;
        }
        sb.append("<ul>\n");
        List<String> names = input.sources().isEmpty()
                ? new ArrayList<>(input.paramValues().keySet()) : input.sources();
        for (String name : names) {
            String value = input.paramValues().getOrDefault(name, "");
            if (value.isEmpty()) value = "?";
            sb.append("<li>").append(escapeHtml(name)).append(" = ").append(escapeHtml(value)).append("</li>\n");
        }
        sb.append("</ul>\n");
    }

    private static void appendExplain(StringBuilder sb, Input input) {
        if (input.explainLines() == null) {
            sb.append(MpSqlVisualizerBundle.message("diag.report.explain.unavailable")).append("\n");
            return;
        }
        appendLines(sb, input.explainLines(), MpSqlVisualizerBundle.message("diag.report.none"));
    }

    private static void appendExplainHtml(StringBuilder sb, Input input) {
        if (input.explainLines() == null) {
            sb.append("<h2>")
                    .append(escapeHtml(MpSqlVisualizerBundle.message("diag.report.section.explain")))
                    .append("</h2>\n");
            sb.append("<p class=\"none\">")
                    .append(escapeHtml(MpSqlVisualizerBundle.message("diag.report.explain.unavailable")))
                    .append("</p>\n");
            return;
        }
        appendSectionHtml(sb, "diag.report.section.explain", input.explainLines(), "diag.report.none");
    }

    private static void appendLines(StringBuilder sb, List<String> lines, String noneText) {
        if (lines.isEmpty()) {
            sb.append(noneText).append("\n");
            return;
        }
        for (String line : lines) {
            sb.append("- ").append(line).append("\n");
        }
    }

    private static void appendSectionHtml(StringBuilder sb, String titleKey, List<String> lines, String noneKey) {
        sb.append("<h2>").append(escapeHtml(MpSqlVisualizerBundle.message(titleKey))).append("</h2>\n");
        if (lines.isEmpty()) {
            sb.append("<p class=\"none\">").append(escapeHtml(MpSqlVisualizerBundle.message(noneKey)))
                    .append("</p>\n");
            return;
        }
        sb.append("<ul>\n");
        for (String line : lines) {
            sb.append("<li>").append(escapeHtml(line)).append("</li>\n");
        }
        sb.append("</ul>\n");
    }

    private static String escapeHtml(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
