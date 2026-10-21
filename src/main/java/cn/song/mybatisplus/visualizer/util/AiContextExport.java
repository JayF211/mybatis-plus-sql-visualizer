// 自 master util/AiContextExport.kt 移植（R37 T-LaneB D95/D97 + R91 v2）
package cn.song.mybatisplus.visualizer.util;

import cn.song.mybatisplus.visualizer.history.ExplainPlanKey;
import cn.song.mybatisplus.visualizer.history.ExplainPlanSnapshot;
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter;
import cn.song.mybatisplus.visualizer.share.ShareContentProjection;
import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.DialectRegistry;
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * R37（T-LaneB D95/D97）：安全 AI 上下文交递——「复制为 AI 上下文」动作的输出块组装。
 *
 * <p>出口背景（V51 核实）：批次 9 后 MCP 接入方已有 sqlv_schema_context/sqlv_explain 等结构化
 * 工具，但<b>未接 MCP 的用户</b>没有「把 SQL 安全交给任意 AI」的插件内出口。本类把当前预览
 * SQL 组装为<b>脱敏参数化</b>的文本块：敏感字面量替换为 {@code ?}
 * （{@link DisplayMasker#parameterize}），参数表只落 {@code ***}——原值不出本方法（对齐 agent
 * 侧 Desensitizer「数据不出应用 JVM」姿态与 sqlv_schema_context「credentials never leave the IDE」口径）。</p>
 *
 * <p>块格式（V51.4 定案）：
 * <pre>
 * -- SQL Visualizer AI context (sensitive literals masked)
 * -- dialect: MySQL
 * -- masked literals: 2
 * SELECT id FROM user WHERE phone = ? AND password = ?
 * -- params (masked): 1=*** 2=***
 * </pre>
 * 方言缺失省略方言行；零敏感无参数表行。EXPLAIN 计划/表列摘要不入首版（计划文本面板侧无
 * 稳定引用，见计划册 §五）。纯逻辑零平台依赖。</p>
 */
public final class AiContextExport {

    private AiContextExport() {
    }

    /** 组装产物：[block] 完整文本块（可直接进剪贴板）；[maskedCount] 敏感字面量计数。 */
    public record Result(String block, int maskedCount) {
    }

    /** R91: static preview metadata only, not a database schema probe. Names never leave v2. */
    public record StaticSchema(String table, List<String> columns) {
    }

    /** 含表列名模式的列数上限；超出追加 `…(+N)`，仍受 16 KiB 整段省略预算约束。 */
    private static final int MAX_SCHEMA_COLUMNS = 50;

    /** 头部来源声明（固定，AI 可溯源到插件）。 */
    private static final String HEADER = "-- SQL Visualizer AI context (sensitive literals masked)";

    /**
     * Versioned, bounded local projection. v1 {@link #build} intentionally remains byte-compatible.
     * The caller supplies an exact key for the current editor SQL and its confirmed target.
     * Missing context never borrows a different plan or causes acquisition.
     */
    public static Result buildV2(
            @NotNull String sql,
            @Nullable String dialect,
            @Nullable ExplainPlanKey currentKey,
            @NotNull List<ExplainPlanSnapshot> plans,
            @Nullable StaticSchema schema,
            boolean includeIdentifiers) {
        ShareContentProjection.SqlProjection projection =
                ShareContentProjection.sql(sql, includeIdentifiers);
        String safeDialect = "not obtained";
        if (dialect != null) {
            for (Dialect d : DialectRegistry.INSTANCE.getDialects()) {
                if (d.getName().equals(dialect)) {
                    safeDialect = d.getName();
                    break;
                }
            }
        }
        ExplainPlanSnapshot matching = null;
        if (currentKey != null && currentKey.isPersistable()) {
            for (ExplainPlanSnapshot snapshot : plans) {
                if (!snapshot.key().equals(currentKey)) continue;
                if (matching == null || snapshot.capturedAt > matching.capturedAt) {
                    matching = snapshot;
                }
            }
        }
        String plan = null;
        if (matching != null) {
            McpExplainFormatter.RawResult raw = matching.raw();
            if (raw != null) {
                ExplainOutputFormat format = null;
                try {
                    format = ExplainOutputFormat.valueOf(matching.format);
                } catch (Throwable ignored) {
                    // runCatching 等价：未知格式名不产出计划段
                }
                if (format != null) {
                    plan = AiContextPlanSummary.summarize(raw, format);
                }
            }
        }
        String visibility = includeIdentifiers
                ? "identifiers visible as written (quoted tokens stay hidden)"
                : "identifiers hidden";
        String staticSchema;
        if (schema == null || schema.table().isBlank() || schema.columns().isEmpty()) {
            staticSchema = "-- Static schema: not obtained";
        } else if (!includeIdentifiers) {
            staticSchema = "-- Static schema: tables=1; columns=" + distinct(schema.columns()).size() + "; "
                    + "identifiers hidden; static preview metadata only";
        } else {
            List<String> columns = distinct(schema.columns());
            List<String> shown = columns.size() > MAX_SCHEMA_COLUMNS
                    ? new ArrayList<>(columns.subList(0, MAX_SCHEMA_COLUMNS)) : columns;
            String suffix = columns.size() > shown.size()
                    ? " …(+" + (columns.size() - shown.size()) + ")" : "";
            staticSchema = "-- Static schema: table=" + schema.table() + "; columns=" + columns.size() + ": "
                    + String.join(", ", shown) + suffix;
        }
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("SQL", projection.sql() != null
                ? "-- SQL (strict projection)\n" + projection.sql()
                : "-- SQL: omitted (unsupported syntax or budget)");
        sections.put("EXPLAIN", plan != null
                ? "-- EXPLAIN (already acquired; exact match)\n" + plan
                : "-- EXPLAIN: not obtained (no matching structured summary)");
        sections.put("Static schema", staticSchema);
        String header = "-- SQL Visualizer AI context v2\n-- dialect: " + safeDialect + "\n"
                + "-- " + visibility + "; values masked=" + projection.maskedValues()
                + "; local-only; no acquisition\n";
        // Drop whole sections, never truncate into a raw fragment or restore the original SQL.
        for (String section : List.of("EXPLAIN", "Static schema", "SQL")) {
            if (block(header, sections).getBytes(StandardCharsets.UTF_8).length
                    <= ShareContentProjection.MAX_OUTPUT_BYTES) {
                break;
            }
            sections.put(section, "-- " + section + ": omitted (16 KiB budget)");
        }
        return new Result(block(header, sections), projection.maskedValues());
    }

    private static String block(String header, Map<String, String> sections) {
        return header + String.join("\n", sections.values());
    }

    private static List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    /**
     * 组装 AI 上下文块。
     *
     * @param sql 当前预览 SQL 原文（含敏感字面量；只在本方法内参数化，不外泄）
     * @param dialect 方言名（如 {@code MySQL}；null 省略方言行）
     */
    public static Result build(@NotNull String sql, @Nullable String dialect) {
        DisplayMasker.ParameterizedSql parameterized = DisplayMasker.parameterize(sql);
        StringBuilder block = new StringBuilder();
        block.append(HEADER).append('\n');
        if (dialect != null && !dialect.isBlank()) {
            block.append("-- dialect: ").append(dialect).append('\n');
        }
        block.append("-- masked literals: ").append(parameterized.maskedParams().size()).append('\n');
        block.append(parameterized.sql());
        if (!parameterized.maskedParams().isEmpty()) {
            block.append('\n');
            block.append("-- params (masked): ");
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < parameterized.maskedParams().size(); i++) {
                parts.add((i + 1) + "=" + parameterized.maskedParams().get(i));
            }
            block.append(String.join(" ", parts));
        }
        return new Result(block.toString(), parameterized.maskedParams().size());
    }
}
