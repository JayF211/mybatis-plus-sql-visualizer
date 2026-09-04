package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code SqlLogPanel.kt} 顶层函数（{@code withTerminator} / {@code filterByType} / {@code filterBySearch} /
 * 渲染辅助 {@code formatTimestampHtml} / {@code formatDurationHtml} / {@code escapeHtml}）的 Java 承载类。
 */
public final class SqlLogPanelKt {

    private SqlLogPanelKt() {
    }

    /** SQL 末尾分号结束符（显示层统一追加；已以 `;` 结尾或空串不重复；T-M42 提为顶层，openPreview/右键复制/列表复制共用）。 */
    public static String withTerminator(String sql) {
        return (sql.isEmpty() || sql.endsWith(";")) ? sql : sql + ";";
    }

    // ---------- T-LogListFreeze：列表刷新分批常量 ----------

    /** 首屏立即写入条数（保证快速首屏，EDT 冻结可控）。 */
    public static final int REFRESH_FIRST_BATCH_SIZE = 30;

    /** 每批异步追加条数（invokeLater 每批一次 EDT 脉冲）。 */
    public static final int REFRESH_CHUNK_SIZE = 30;

    /** 类型多选弹窗可勾选的类型（OTHER 不单列；全选时 OTHER 一并透传）。 */
    public static final List<StatementType> FILTERABLE_TYPES = List.of(
            StatementType.SELECT,
            StatementType.INSERT,
            StatementType.UPDATE,
            StatementType.DELETE
    );

    /**
     * 类型过滤（纯逻辑，可独立单测）：所选类型覆盖全部可筛选类型时视为「全部」，
     * 透传所有条目（含 OTHER）；否则仅保留分类命中的条目。
     */
    public static List<LogSqlEntry> filterByType(List<LogSqlEntry> entries, Set<StatementType> activeTypes) {
        if (activeTypes.containsAll(FILTERABLE_TYPES)) {
            return entries;
        }
        List<LogSqlEntry> result = new ArrayList<>();
        for (LogSqlEntry it : entries) {
            if (activeTypes.contains(LogStatementClassifier.classify(it))) {
                result.add(it);
            }
        }
        return result;
    }

    /**
     * 搜索过滤（纯逻辑，可独立单测）：查询词忽略大小写 contains，匹配还原 SQL 与摘要
     * （摘要含参数值的还原 SQL 前缀；参数值是常见检索目标）；空白查询透传全部。
     */
    public static List<LogSqlEntry> filterBySearch(List<LogSqlEntry> entries, String query) {
        String q = query.trim();
        if (q.isEmpty()) {
            return entries;
        }
        String ql = q.toLowerCase(Locale.ENGLISH);
        List<LogSqlEntry> result = new ArrayList<>();
        for (LogSqlEntry it : entries) {
            if (it.restoredSql().toLowerCase(Locale.ENGLISH).contains(ql)
                    || it.summary().toLowerCase(Locale.ENGLISH).contains(ql)) {
                result.add(it);
            }
        }
        return result;
    }

    /** 时间戳 HTML 前缀：返回 `<span>` 片段，与 SQL 高亮共用 HTML 通道；未开启时间戳则返回空串。 */
    public static String formatTimestampHtml(long timestamp) {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        if (!state.getLogTimestampEnabled()) {
            return "";
        }
        String formatted;
        try {
            formatted = new SimpleDateFormat(state.getLogTimestampFormat()).format(new Date(timestamp));
        } catch (IllegalArgumentException e) {
            formatted = new SimpleDateFormat(SqlLogPanel.DEFAULT_TIMESTAMP_FORMAT).format(new Date(timestamp));
        }
        return "<span style='color:#9E9E9E'>[" + escapeHtml(formatted) + "]</span> ";
    }

    /** 耗时 HTML 片段：根据阈值套不同颜色——无耗时显示 "—" 占位。 */
    public static String formatDurationHtml(Long ms, boolean isSelected) {
        long threshold = PluginSettings.getInstance().getState().getSlowQueryThresholdMs();
        String color;
        if (isSelected) {
            color = "#FFFFFF";
        } else if (ms == null) {
            color = "#9E9E9E";            // 灰：无耗时
        } else if (ms >= threshold) {
            color = "#D32F2F";      // 红：慢查询
        } else if (ms >= threshold / 2) {
            color = "#E67E22";  // 橙：偏慢
        } else {
            color = "#4CAF50";                 // 绿：正常
        }
        String text = (ms != null) ? (ms + "ms") : "—";
        return "<span style='color:" + color + "'>" + text + "</span> ";
    }

    /** 单行 HTML 转义（防注入日志中的特殊字符）。 */
    private static String escapeHtml(String s) {
        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
