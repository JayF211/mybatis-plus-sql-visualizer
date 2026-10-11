package cn.song.mybatisplus.visualizer.log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * R4（W3 历史工作流包）：SQL 导出服务——历史/日志面板行集导出为 CSV / TSV / 纯文本 / SQL 脚本文件。
 *
 * <p>纯逻辑（格式化 + 写盘），不依赖平台 UI；面板侧负责行集收集（与当前过滤一致）与
 * 文件选择，写盘在后台线程（大集不卡 EDT，第四轮 R4 口径）。
 *
 * <p>CSV：RFC 4180 口径——含逗号/引号/换行的字段双引号包裹、内部双引号加倍；首行表头。
 * TSV：制表符分隔，字段内 tab/换行替换为空格（TSV 无转义机制，防串列）。
 * TXT：人读形态——分隔线 + 时间/来源/耗时元信息 + 完整 SQL 原文（多行保形）。
 * SQL：可执行脚本形态（T-ExportSqlFormat，O-131/R77/D131/V154）——文件头计数注释 + 逐条
 * TXT 同构注释头 + 分号终止的 SQL（复用 {@link SqlLogPanelKt#withTerminator}，单一真源）+ 空行分隔；
 * 内容源=restoredSql（参数回填后可执行），参数缺失条目如实保留 {@code ?}（LogSqlRestorer 口径）。
 */
public final class SqlExportService {

    private SqlExportService() {
    }

    /** 表头（CSV/TSV 共用列序；TXT 用分隔线形态）。 */
    private static final List<String> HEADER = List.of("time", "source", "duration_ms", "sql");

    /** CSV 字段转义：含逗号/引号/换行/回车即包裹，内部双引号加倍。 */
    public static String csvField(String value) {
        boolean needsQuote = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                needsQuote = true;
                break;
            }
        }
        return needsQuote ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    /** 行集格式化（四格式分发）。 */
    public static String format(List<SqlExportRow> rows, SqlExportFormat format) {
        switch (format) {
            case CSV:
                return formatCsv(rows);
            case TSV:
                return formatTsv(rows);
            case TXT:
                return formatTxt(rows);
            case SQL:
                return formatSql(rows);
            default:
                throw new IllegalStateException("SqlExportService.format: unknown format " + format);
        }
    }

    /** 写盘（调用方保证后台线程）。 */
    public static void exportToFile(File file, String content) {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try {
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** SQL 格式默认保存名（时钟取当前时刻的默认实参形态）。 */
    public static String defaultSqlExportFileName() {
        return defaultSqlExportFileName(System.currentTimeMillis());
    }

    /**
     * SQL 格式默认保存名（T-ExportSqlFormat 用户拍板 20261009）：{@code sql-export-<yyyyMMdd-HHmmss>.sql}。
     * 时间戳到秒防同日多次导出静默覆盖；{@code sql-export-} 前缀不与自动保存名族（{@code sql-history-*}）重叠，
     * 即便被手动存进自动保存目录也不参与保留上限清理（SqlLogAutoSaver 名族过滤，非匹配 .sql 不计数）；
     * CSV/TSV/TXT 默认名仍沿用 {@code sql-history./sql-log.${ext}}（面板各自拼）。
     */
    public static String defaultSqlExportFileName(long nowMs) {
        return "sql-export-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(nowMs)) + ".sql";
    }

    private static String formatCsv(List<SqlExportRow> rows) {
        StringBuilder sb = new StringBuilder(String.join(",", HEADER));
        sb.append('\n');
        for (SqlExportRow r : rows) {
            sb.append(csvField(r.time())).append(',')
                    .append(csvField(r.source())).append(',')
                    .append(csvField(r.durationMs() != null ? r.durationMs().toString() : "")).append(',')
                    .append(csvField(r.sql()));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String formatTsv(List<SqlExportRow> rows) {
        StringBuilder sb = new StringBuilder(String.join("\t", HEADER));
        sb.append('\n');
        for (SqlExportRow r : rows) {
            sb.append(flattenTsv(r.time())).append('\t')
                    .append(flattenTsv(r.source())).append('\t')
                    .append(flattenTsv(r.durationMs() != null ? r.durationMs().toString() : "")).append('\t')
                    .append(flattenTsv(r.sql()));
            sb.append('\n');
        }
        return sb.toString();
    }

    /** TSV 字段扁平化（无转义机制，tab/换行/回车替换为空格）。 */
    private static String flattenTsv(String v) {
        return v.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** 逐条注释头（{@code -- === SQL #N ===} + time/source/duration 元信息行）——TXT/SQL 两格式同构共用（勿两份拷贝）。 */
    private static void appendSqlCommentHeader(StringBuilder sb, int index, SqlExportRow r) {
        sb.append("-- === SQL #").append(index + 1).append(" ===\n");
        sb.append("-- time: ").append(r.time());
        if (!r.source().isBlank()) {
            sb.append(" | source: ").append(r.source());
        }
        if (r.durationMs() != null) {
            sb.append(" | ").append(r.durationMs()).append(" ms");
        }
        sb.append('\n');
    }

    private static String formatTxt(List<SqlExportRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            SqlExportRow r = rows.get(i);
            appendSqlCommentHeader(sb, i, r);
            sb.append(r.sql().stripTrailing());
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /**
     * SQL 文件格式（T-ExportSqlFormat）：文件头计数注释 + 逐条 TXT 同构注释头 + 分号终止的
     * SQL + 空行分隔——目标形态=数据库客户端可直接执行（缺分号补 {@code ;}，已有分号不重复，
     * 复用 {@link SqlLogPanelKt#withTerminator} 单一真源）；参数缺失条目的 {@code ?} 原样保留
     * （LogSqlRestorer 口径，导出文件出现 {@code ?} 属预期）。空行集入口已挡（两面板），服务层防一把：仅文件头。
     */
    private static String formatSql(List<SqlExportRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- Exported ").append(rows.size())
                .append(" SQL statements by MyBatis-Plus SQL Visualizer at ")
                .append(formatTime(System.currentTimeMillis()))
                .append('\n');
        for (int i = 0; i < rows.size(); i++) {
            SqlExportRow r = rows.get(i);
            appendSqlCommentHeader(sb, i, r);
            sb.append(SqlLogPanelKt.withTerminator(r.sql().stripTrailing()));
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 时间戳 → 导出用显示文本（毫秒精度，调用方口径统一）。
     * F43（T-PerfSweep16）：SDF ThreadLocal 缓存化——照 SqlSuppressManagerDialog F22 范式
     * （SimpleDateFormat 非线程安全；导出 EDT 行投影调用，ThreadLocal 同时覆盖后台线程触达面）。
     * 复用实例输出与逐次新建逐字节一致（format 不读旧状态），此前每行投影新建一个实例。
     */
    private static final ThreadLocal<SimpleDateFormat> TIME_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT));

    public static String formatTime(long ts) {
        return TIME_FORMAT.get().format(new Date(ts));
    }

    // ------------------------------------------------------------------
    // O-91 可移植导出（缩范围首版）：CRUD/回滚语句 + 方言形态标注，走既有三格式通道
    // ------------------------------------------------------------------

    /** 可移植脚本可执行格式（CSV/TSV 共用列序；TXT 走注释头形态）。 */
    private static final List<String> PORTABLE_HEADER = List.of("dialect", "kind", "upsert", "sql");

    /** 方言形态注释头（TXT 可移植脚本逐字形态；CSV/TSV 同三字段作列）。 */
    public static String portableAnnotation(PortableSqlRow row) {
        return "-- dialect: " + row.dialect() + " | kind: " + row.kind().name() + " | upsert: " + row.upsert();
    }

    /**
     * O-91 可移植导出（{@link PortableSqlRow} 行集 → 三格式，行集由
     * {@link PortableSqlExport#collectRows} 串联：CRUD 行 + 回滚链产物行）。语句本体不改写；
     * TXT 每条语句前带注释头（{@code -- dialect: X | kind: Y | upsert: Z}），CSV/TSV 把同三字段
     * 作列（机器可读形态），SQL 字段沿用既有转义/扁平化口径。
     */
    public static String formatPortable(List<PortableSqlRow> rows, SqlExportFormat format) {
        switch (format) {
            case CSV:
                return formatPortableFlat(rows, ",", SqlExportService::csvField);
            case TSV:
                return formatPortableFlat(rows, "\t", SqlExportService::flattenTsv);
            case TXT:
                return formatPortableTxt(rows);
            // T-ExportSqlFormat 枚举扩员波及此 when：可移植导出是 O-91 独立通道（预览面板自己的
            // 三选对话框，不提供 SQL 选项），与历史/日志导出的 SQL 格式不合并互不替代（计划 §三
            // 边界写死防顺手统一）——UI 不可能传入 SQL，防御性显式失败而非静默伪装成 TXT。
            case SQL:
                throw new IllegalStateException(
                        "formatPortable: SQL format is not offered (O-91 portable export keeps CSV/TSV/TXT)");
            default:
                throw new IllegalStateException("SqlExportService.formatPortable: unknown format " + format);
        }
    }

    private static String formatPortableFlat(List<PortableSqlRow> rows, String sep, UnaryOperator<String> escape) {
        StringBuilder sb = new StringBuilder(String.join(sep, PORTABLE_HEADER));
        sb.append('\n');
        for (PortableSqlRow r : rows) {
            sb.append(escape.apply(r.dialect())).append(sep)
                    .append(escape.apply(r.kind().name())).append(sep)
                    .append(escape.apply(r.upsert())).append(sep)
                    .append(escape.apply(r.sql()));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String formatPortableTxt(List<PortableSqlRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            PortableSqlRow r = rows.get(i);
            sb.append("-- === SQL #").append(i + 1).append(" ===\n");
            sb.append(portableAnnotation(r)).append('\n');
            sb.append(r.sql().stripTrailing());
            sb.append("\n\n");
        }
        return sb.toString();
    }
}
