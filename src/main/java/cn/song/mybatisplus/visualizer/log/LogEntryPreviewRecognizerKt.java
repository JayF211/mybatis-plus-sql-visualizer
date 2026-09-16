package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code LogEntryPreviewRecognizer.kt} 顶层扩展函数（{@code LogSqlEntry.toRecognizedPreview()}）的 Java 承载类。
 */
public final class LogEntryPreviewRecognizerKt {
    private LogEntryPreviewRecognizerKt() {
    }

    /**
     * 识别日志条目为预览弹窗参数形态。
     *
 * 参数命名：日志无参数名，用序号 {@code param1..N}（对齐 MP 生成器 {@code param1} 风格）；
 * 值预填 {@link LogSqlParam#value()}（null → 空串，回填显示 {@code ?}——与还原 {@code NULL} 不一致的边界见开发计划 §7）。
 *
 * T-ListParamCollect：命名参数条目（{@code :paramN}）无 {@code ?} 占位符，{@code recognized=false} 走回退分支——
 * 但其 {@code initialValues} 提供参数名 → 日志值映射（集合值为元素清单），供预览面板按名回填。
 */
public static RecognizedLogPreview toRecognizedPreview(LogSqlEntry entry) {
    long placeholderCount = countPlaceholders(entry.rawSql());
    boolean recognized = placeholderCount > 0 && placeholderCount == entry.params().size();
    // T-ListParamCollect：命名参数条目（`sql = … :paramN` + 日志集合值）无 `?` 占位符，
    // 不进参数行通道；其 SQL 由解析器按同口径还原，此处只负责把集合值还原为元素清单，
    // 供参数行预填（面板既有 inList 逗号分支渲染出与还原 SQL 一致的形态）。
    java.util.Set<String> namedInClause = entry.rawSql().contains(":")
            ? ListParamRenderer.inClauseParamNames(entry.rawSql()) : java.util.Set.of();
    if (!recognized) {
        return new RecognizedLogPreview(
                entry.rawSql(),
                entry.rawSql(),
                List.of(),
                namedInitialValues(entry, namedInClause),
                List.of(),
                false);
    }
        List<String> sources = new ArrayList<>();
        for (int i = 1; i <= placeholderCount; i++) {
            sources.add("param" + i);
        }
        List<String> values = entry.params().stream().map(p -> p.value() != null ? p.value() : "").toList();
        Map<String, String> initialValues = new HashMap<>();
        for (int i = 0; i < sources.size(); i++) {
            initialValues.put(sources.get(i), values.get(i));
        }
        List<ParamHint> paramHints = entry.params().stream().map(LogSqlRestorerKt::toHint).toList();
        return new RecognizedLogPreview(
                entry.rawSql(),
                entry.rawSql(),
                sources,
                initialValues,
                paramHints,
                true);
    }

    /**
     * T-ListParamCollect：命名参数条目的参数名 → 预填值（集合值剥括号为逗号分隔元素清单）。
     *
     * 顺序口径与 {@link JdbcTemplateLogParser#extractNamedParamEntry} 收集 {@code params} 时<b>逐字一致</b>：
     * 都是按 SQL 中 {@code :name} 的出现顺序（重复出现则都收，位置一一对应）。两侧必须同源，
     * 否则参数值会整体错位（{@code param3} 拿到 {@code param1} 的值）。
     *
     * 仅当 SQL 含命名参数（{@code :name}）时产出；普通 {@code ?} 条目与裸 SQL 返回空表（零行为变化）。
     */
    private static java.util.Map<String, String> namedInitialValues(LogSqlEntry entry, java.util.Set<String> inClauseNames) {
        if (entry.params().isEmpty()) return Map.of();
        java.util.List<String> refs = new ArrayList<>();
        java.util.regex.Matcher m = NAMED_PARAM_REF.matcher(entry.rawSql());
        while (m.find()) {
            refs.add(m.group(1));
        }
        if (refs.isEmpty() || refs.size() != entry.params().size()) return Map.of();
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (int i = 0; i < refs.size(); i++) {
            String name = refs.get(i);
            LogSqlParam param = entry.params().get(i);
            String value = param.value();
            if (value == null) continue;
            // splitListElements 对非方括号形态返 null（master 语义）→ 回落原始值
            java.util.List<String> elements = inClauseNames.contains(name)
                    ? ListParamRenderer.splitListElements(value)
                    : null;
            values.put(name, elements != null ? String.join(", ", elements) : value);
        }
        return values;
    }
    /** 命名参数引用模式：{@code :name}（与 {@link JdbcTemplateLogParser} 的收集口径一致）。 */
    private static final java.util.regex.Pattern NAMED_PARAM_REF = java.util.regex.Pattern.compile(":(\\w+)");

    /**
     * T-HibDualCapture：string-aware `?` 占位符计数——口径与 {@link LogSqlRestorerKt#placeholderPositions}
     * （还原回填）完全一致，字符串字面量与注释内的 {@code ?} 不计数，识别数量与回填位置不再错位。
     */
    public static long countPlaceholders(String sql) {
        return LogSqlRestorerKt.placeholderPositions(sql).size();
    }

    /**
     * 日志条目 → 预览结果（T-HistParam F3：SQL 日志 tab 与 SQL 历史 tab 的统一识别通道）。
     *
     * 三级分流：
     * 1. 占位符匹配识别（toRecognizedPreview，`?` 数 == 参数数）→ 参数行预填日志值；
     * 2. 字面量参数恢复（recoverLiteralParams，rawSql 零 `?` 且 literalRecovery 开启，
     *    运行期逐字符一致性熔断 verifyLiteralRoundTrip）→ 字面量改写为 `?` 复用参数管线；
     * 3. 回退现状（restoredSql + 零参数/数量不匹配警告分流，零 `?` 无警告）。
     *
     * 纯函数无平台依赖（开关由调用方读设置传入）：SqlLogPanel 与 HistorySqlLogPanel 均传
     * formatSql=设置值（T-ListPreviewCopy：两页预览统一跟随「预览时格式化还原 SQL」，默认开）。
     */
    public static PreviewResult toPreviewResult(LogSqlEntry entry, boolean formatSql, boolean literalRecovery) {
        RecognizedLogPreview recognized = toRecognizedPreview(entry);
        if (recognized.recognized()) {
            return new PreviewResult(
                formatIfNeeded(SqlLogPanelKt.withTerminator(recognized.sql()), formatSql),
                SqlLogPanelKt.withTerminator(recognized.compactSql()),
                recognized.unresolvedParams(),
                null,
                null,
                null,
                null,
                recognized.paramHints(),
                recognized.initialValues());
        }
        // 字面量恢复只接「零占位符」分支（裸 SQL / ${} / 上游日志已内联值）；数量不匹配
        // （残留 `?`）维持现状警告，不做两套占位符的混合恢复
        if (literalRecovery && countPlaceholders(entry.rawSql()) == 0) {
            LiteralParamRecovery recovery = LiteralParamRecoveryKt.recoverLiteralParams(entry.rawSql());
            if (recovery != null && LiteralParamRecoveryKt.verifyLiteralRoundTrip(entry.rawSql(), recovery)) {
                return new PreviewResult(
                    formatIfNeeded(SqlLogPanelKt.withTerminator(recovery.placeholderSql()), formatSql),
                    SqlLogPanelKt.withTerminator(recovery.placeholderSql()),
                    recovery.unresolvedParams(),
                    null,
                    null,
                    null,
                    null,
                    recovery.paramHints(),
                    recovery.initialValues());
            }
        }
        String base = SqlLogPanelKt.withTerminator(entry.restoredSql());
        // T-HibDualCapture：文案分流——零参数是「绑定行没抓到」（引导开 TRACE），
        // 有参数但数量不齐才是「数量不匹配」；`?` 计数 string-aware
        // T-JdbcThreadBucket F2：零参数时按来源分流——JdbcTemplate 引导开 org.springframework.jdbc.core，
        // Hibernate 引导开 type.descriptor.sql（避免把 JDBC 条目误导向 Hibernate 配置）
        String warning = countPlaceholders(entry.restoredSql()) > 0
            ? MpSqlVisualizerBundle.message(entry.params().isEmpty()
                ? (entry.source() == SqlSource.JDBC
                    ? "log.preview.unresolvedNoBinding.jdbc"
                    : "log.preview.unresolvedNoBinding")
                : "log.preview.unresolvedWarning")
            : null;
        return new PreviewResult(
            formatIfNeeded(base, formatSql),
            entry.restoredSql(),
            List.of(),
            null,
            null,
            null,
            warning,
            List.of(),
            Map.of());
    }

    private static String formatIfNeeded(String sql, boolean formatSql) {
        return formatSql ? SqlFormatter.format(sql) : sql;
    }
}
