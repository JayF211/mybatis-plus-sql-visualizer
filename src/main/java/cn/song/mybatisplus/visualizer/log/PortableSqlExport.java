package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.model.BatchKind;
import cn.song.mybatisplus.visualizer.model.BatchModel;
import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * O-91 CRUD/回滚 SQL 可移植导出（缩范围首版，边界=核实报告 20261001-批次9核实道2 §2.5 定案）。
 *
 * <p><b>做什么</b>：把当前预览的 CRUD/回滚 SQL 串成可移植脚本——语句本体已由方言链
 * （{@code BatchModel.renderSql} / {@link RollbackSqlGenerator}）生成，导出<b>不改写 SQL 本体</b>，
 * 只做两件事：①每条语句前加注释头标注方言形态；②导出格式走 R4 既有三格式通道
 * （{@link SqlExportService#formatPortable}，不新造文件格式）。
 *
 * <p><b>不做什么</b>（定案边界，勿顺手扩张）：DDL 导出、INSERT ALL 合成（#156 只登记）、
 * O-9 闸门改动（UPSERT 不进确认流属现状语义）、PG ON CONFLICT 回滚行为探针（留后续）。
 *
 * <p>upsert 形态标注值（对齐核实报告「原生简洁/整语句/降级 INSERT」三态＋非 upsert）：
 * <ul>
 * <li>{@link #UPSERT_STATEMENT}：方言链整语句形态（H2 {@code MERGE INTO..KEY..VALUES}），文本以 MERGE 起头；</li>
 * <li>{@link #UPSERT_SUFFIX}：INSERT+后缀形态（MySQL {@code ON DUPLICATE KEY UPDATE} / PG {@code ON CONFLICT}）；</li>
 * <li>{@link #UPSERT_DEGRADED}：方言无任何原生简洁 upsert（Oracle/SQL Server/DM）→ UPSERT 意图渲染降级纯 INSERT；</li>
 * <li>{@link #UPSERT_NONE}：非 upsert（含「UPSERT 模型主键未勾选 → 新记录路径纯 INSERT」——方言链有能力
 *   而文本无冲突子句，说明走的是新记录路径而非降级，两者以链能力区分，勿混标）。</li>
 * </ul>
 */
public final class PortableSqlExport {

    private PortableSqlExport() {
    }

    /** 整语句形态标注（H2 MERGE..KEY）。 */
    public static final String UPSERT_STATEMENT = "statement";

    /** INSERT+后缀形态标注（MySQL ON DUPLICATE / PG ON CONFLICT）。 */
    public static final String UPSERT_SUFFIX = "suffix";

    /** 降级纯 INSERT 标注（方言链无原生简洁 upsert，UPSERT 意图落成 INSERT）。 */
    public static final String UPSERT_DEGRADED = "insert";

    /** 非 upsert 标注。 */
    public static final String UPSERT_NONE = "no";

    /** 能力探测桩值（仅取方言链返回 null 与否；值本身不进产物）。 */
    private static final String PROBE = "__sqlvis_probe__";

    /** 无模型上下文的文本级判定（Kotlin 默认参数 model = null 的重载等价）。 */
    public static String upsertFormOf(Dialect dialect, String sql) {
        return upsertFormOf(dialect, sql, null);
    }

    /**
     * upsert 形态判定（与 {@code BatchModel.renderSql} UPSERT 分支的三级降级决策同源）：
     * <ol>
     * <li>文本以 MERGE 起头且方言链整语句形态有能力 → {@link #UPSERT_STATEMENT}；</li>
     * <li>文本含冲突子句标记且方言链后缀形态有能力 → {@link #UPSERT_SUFFIX}；</li>
     * <li>模型是 UPSERT 意图而方言链两形态均无（renderUpsert/renderUpsertStatement 恒 null）
     *   → {@link #UPSERT_DEGRADED}（渲染必然降级纯 INSERT）；</li>
     * <li>其余（含 UPSERT 模型但方言链有能力而文本是纯 INSERT = 主键未勾选的新记录路径）→ {@link #UPSERT_NONE}。</li>
     * </ol>
     *
     * <p>{@code model} 可 null（无模型上下文的文本级判定，如 MCP/历史条目）——此时第 3 条不可判，降 none。
     */
    public static String upsertFormOf(Dialect dialect, String sql, BatchModel model) {
        String normalized = sql.trim();
        int end = normalized.length();
        while (end > 0 && normalized.charAt(end - 1) == ';') {
            end--;
        }
        normalized = normalized.substring(0, end).trim().toUpperCase(Locale.ROOT);
        boolean statementCapable = dialect.renderUpsertStatement(PROBE, List.of(PROBE), PROBE) != null;
        boolean suffixCapable = dialect.renderUpsert(PROBE, List.of(PROBE)) != null;
        if (statementCapable && normalized.startsWith("MERGE")) {
            return UPSERT_STATEMENT;
        }
        if (suffixCapable && (normalized.contains("ON DUPLICATE KEY UPDATE") || normalized.contains("ON CONFLICT"))) {
            return UPSERT_SUFFIX;
        }
        if (model != null && model.getKind() == BatchKind.UPSERT && !statementCapable && !suffixCapable) {
            return UPSERT_DEGRADED;
        }
        return UPSERT_NONE;
    }

    /** 无模型上下文形态（Kotlin 默认参数 model = null 的重载等价）。 */
    public static List<PortableSqlRow> collectRows(String sql, Dialect dialect) {
        return collectRows(sql, dialect, null);
    }

    /**
     * 导出串联（首版核心）：当前 SQL 作 CRUD 行 + 回滚链产物逐条作 ROLLBACK 行。
     *
     * <p>回滚链对 UPDATE/DELETE 无 before-image、UPSERT 三形态等场景<b>诚实拒绝</b>
     * （statements 恒空，不产危险半成品）——导出面延续同口径：拒绝时不造回滚行、不降级造半成品。
     * 空输入返回空列表（调用方不发导出）。
     */
    public static List<PortableSqlRow> collectRows(String sql, Dialect dialect, BatchModel model) {
        String cleaned = sql.trim();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        List<PortableSqlRow> rows = new ArrayList<>();
        rows.add(new PortableSqlRow(
                dialect.getName(),
                PortableSqlKind.CRUD,
                upsertFormOf(dialect, cleaned, model),
                cleaned));
        for (String stmt : RollbackSqlGenerator.generate(cleaned).statements()) {
            rows.add(new PortableSqlRow(dialect.getName(), PortableSqlKind.ROLLBACK, UPSERT_NONE, stmt));
        }
        return rows;
    }
}
