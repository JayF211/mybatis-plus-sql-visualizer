package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.util.DisplayMasker;
import cn.song.mybatisplus.visualizer.util.SqlStatementSplitter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * R36（V46 证伪②定案）：回放脚本组装——把预览编辑器里的 SQL 串成<b>单个可直接执行的
 * {@code .sql} 脚本</b>。入参 SQL 的两项前提已由上游保证：
 * <ul>
 * <li><b>参数已填回</b>：编辑器正文即预览链 renderSql → backfillParamsBySource 的回填产物
 *     （历史条目经 R51 recall 链回落后同样带参数），本类不再做参数替换；</li>
 * <li><b>方言适配</b>：语句本体已由生成链按当前方言渲染（{@link Dialect}），本类<b>不改写语句本体</b>
 *     （同 O-91 原则），只按方言补事务包裹语句。</li>
 * </ul>
 *
 * <p>与 O-91 产物先验的关系（V46 第二条的实测裁量）：</p>
 * <ul>
 * <li>O-91 产物＝PortableSqlRow 行集，走 {@link SqlExportService#formatPortable} 的 CSV/TSV/TXT
 *     <b>三格式通道</b>，形态是「机器可读行集 + dialect/kind/upsert 三列标注」；</li>
 * <li>R36 产物＝<b>单个可执行 .sql</b>（新产物形态）。通道<b>复用</b> SqlExportService.exportToFile
 *     写盘与 displayMaskEnabled 脱敏形参先例；<b>不扩</b> SqlExportFormat 枚举——加第 4 个值会
 *     连带 R4 三格式对话框与 format/formatPortable 的分支面，超出本件范围（登记为边界）。</li>
 * </ul>
 *
 * <p>本类只做三件事：①语句切分 + 结尾分号规范化（保证可直接执行）②可选事务包裹
 * ③注释头（方言/条数/事务态/脱敏态，机器与人都可读）。</p>
 *
 * <p>脱敏（R60 口径对齐）：{@link #render} 的 mask 形参开 → 逐语句 {@link DisplayMasker#maskDisplay}
 * （投影级，调用方入参与数据层条目不动），并在注释头标 masked——脱敏后字面量成 {@code ***}、
 * 脚本不再逐字可执行属<b>预期</b>（用户在设置里开启了展示脱敏即选择了交付形态）；
 * 默认关闭（displayMaskEnabled 默认 false）时为逐字可执行脚本。</p>
 */
public final class ReplayScriptExport {

    private ReplayScriptExport() {
    }

    /** 产物扩展名（{@code .sql} 可执行脚本）。 */
    public static final String EXT = "sql";

    /** 事务包裹尾语句。 */
    public static final String COMMIT_STATEMENT = "COMMIT;";

    /** 注释头首行标识。 */
    public static final String HEADER_TAG = "SQL Visualizer replay script";

    /**
     * 回放脚本组装结果（纯数据，渲染与 UI 说明共用）。
     */
    public record Plan(
            /** 当前方言名（注释头 dialect: 段）。 */
            @NotNull String dialectName,
            /** 切分并规范化前的语句原文（渲染时逐条补结尾分号）。 */
            @NotNull List<String> statements,
            /** 本方言显式事务开始语句（Dialect.transactionBegin），null = 无。 */
            @Nullable String transactionBegin,
            /** <b>生效</b>的事务包裹（方言无显式开始时强制 false）。 */
            boolean wrapped,
            /** 调用方是否请求了包裹（与 wrapped 合判降级）。 */
            boolean wrapRequested
    ) {
        /** 请求了但方言不支持（UI 据此说明诚实降级原因）。 */
        public boolean isWrapUnsupported() {
            return wrapRequested && !wrapped;
        }
    }

    /** 语句切分（SqlStatementSplitter.split，含 BEGIN/END 块与引号内分号感知）并剔除空白段。 */
    @NotNull
    public static List<String> statementsOf(@NotNull String sql) {
        List<String> out = new ArrayList<>();
        for (String statement : SqlStatementSplitter.split(sql)) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /**
     * 组装计划；无可执行语句（空/全空白）返回 null（调用方不发导出，同 O-91 空行集口径）。
     *
     * <p>wrapTransaction 仅在 {@link Dialect#getTransactionBegin()} 非 null 时生效——无显式事务开始
     * 语句的方言（Oracle/DM）走 {@link Plan#isWrapUnsupported}，脚本诚实省略包裹。</p>
     */
    public static @Nullable Plan plan(@NotNull String sql, @NotNull Dialect dialect, boolean wrapTransaction) {
        List<String> statements = statementsOf(sql);
        if (statements.isEmpty()) {
            return null;
        }
        String begin = dialect.getTransactionBegin();
        return new Plan(
                dialect.getName(),
                statements,
                begin,
                wrapTransaction && begin != null,
                wrapTransaction);
    }

    /**
     * 渲染脚本正文。
     *
     * @param mask        开 → 逐语句投影级脱敏（DisplayMasker.maskDisplay）＋头部 masked: 标注
     * @param generatedAt 生成时间文本（调用方传 SqlExportService.formatTime，null 则省略该行）
     */
    @NotNull
    public static String render(@NotNull Plan plan, boolean mask, @Nullable String generatedAt) {
        List<String> effective = mask
                ? plan.statements().stream().map(it -> DisplayMasker.maskDisplay(it, true)).toList()
                : plan.statements();
        StringBuilder sb = new StringBuilder();
        sb.append("-- ").append(HEADER_TAG).append('\n');
        if (generatedAt != null && !generatedAt.isBlank()) {
            sb.append("-- generated: ").append(generatedAt).append('\n');
        }
        sb.append("-- dialect: ").append(plan.dialectName()).append('\n');
        sb.append("-- statements: ").append(plan.statements().size()).append('\n');
        sb.append("-- transaction: ").append(transactionLine(plan)).append('\n');
        if (mask) {
            sb.append("-- masked: literal values replaced with *** (display mask enabled)\n");
        }
        if (plan.wrapped()) {
            sb.append(plan.transactionBegin()).append(";\n");
        }
        for (String raw : effective) {
            String trimmed = raw.stripTrailing();
            while (trimmed.endsWith(";")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1).stripTrailing();
            }
            sb.append(trimmed).append(";\n");
        }
        if (plan.wrapped()) {
            sb.append(COMMIT_STATEMENT).append('\n');
        }
        return sb.toString();
    }

    /** 注释头的 {@code transaction:} 段（三态：包裹 / 未请求 / 请求但方言无显式事务语句）。 */
    private static String transactionLine(@NotNull Plan plan) {
        if (plan.wrapped()) {
            return plan.transactionBegin() + " ... COMMIT (use ROLLBACK in place of COMMIT to undo)";
        }
        if (plan.isWrapUnsupported()) {
            return "none (" + plan.dialectName() + " has no explicit transaction-start statement)";
        }
        return "none";
    }
}
