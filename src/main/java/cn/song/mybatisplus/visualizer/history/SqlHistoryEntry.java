package cn.song.mybatisplus.visualizer.history;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * SQL 历史记录条目（F-7）：一次预览的不可变快照 + 用户标注（收藏/标签）。
 *
 * <p>持久化友好：全部字段为基本类型 / String / List，XmlSerializer 可直接序列化。
 * [tags] 用 mutableList 而非 Set（XmlSerializer 对 Set 支持不稳定），[SqlHistoryManager] 维护唯一性。
 */
public final class SqlHistoryEntry {

    /** 摘要最大字符数（列表渲染用）。 */
    public static final int MAX_SUMMARY_LEN = 80;

    /** 唯一标识（空白时由 [SqlHistoryManager.add] 自动分配 UUID）。 */
    private String id = "";
    /** 完整 SQL（多行格式化）。 */
    private String sql = "";
    /** 压缩 SQL（摘要展示用）。 */
    private String compactSql = "";
    /** 预览时间戳（毫秒）。 */
    private long timestamp = 0L;
    /** 来源文件名（如 UserMapper.java）。 */
    private String sourceFile = "";
    /** 来源文件行号（0 表示未知）。 */
    private int sourceLine = 0;
    /** 目标表名（可为空）。 */
    private String tableName = "";
    /** 是否收藏（收藏条目不参与上限淘汰）。 */
    private boolean favorite = false;
    /** 用户自定义标签列表（唯一、有序）。 */
    private List<String> tags = new ArrayList<>();
    /** 执行耗时（毫秒）；null 表示无耗时信息。 */
    private Long executionTimeMs = null;
    /** R15（W3 历史工作流包）：钉住——非收藏分区裁剪跳过钉住条目（防翻找丢失）；上限由 manager 把关。 */
    private boolean pinned = false;

    public SqlHistoryEntry() {
    }

    /** 全参构造（字段顺序对齐 Kotlin 主构造器，供 Java 调用方按位置传参）。 */
    public SqlHistoryEntry(String id, String sql, String compactSql, long timestamp, String sourceFile,
                           int sourceLine, String tableName, boolean favorite, List<String> tags) {
        this.id = id != null ? id : "";
        this.sql = sql != null ? sql : "";
        this.compactSql = compactSql != null ? compactSql : "";
        this.timestamp = timestamp;
        this.sourceFile = sourceFile != null ? sourceFile : "";
        this.sourceLine = sourceLine;
        this.tableName = tableName != null ? tableName : "";
        this.favorite = favorite;
        this.tags = tags != null ? tags : new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id != null ? id : "";
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql != null ? sql : "";
    }

    public String getCompactSql() {
        return compactSql;
    }

    public void setCompactSql(String compactSql) {
        this.compactSql = compactSql != null ? compactSql : "";
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(String sourceFile) {
        this.sourceFile = sourceFile != null ? sourceFile : "";
    }

    public int getSourceLine() {
        return sourceLine;
    }

    public void setSourceLine(int sourceLine) {
        this.sourceLine = sourceLine;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName != null ? tableName : "";
    }

    public boolean isFavorite() {
        return favorite;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? tags : new ArrayList<>();
    }

    public Long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public void setExecutionTimeMs(Long executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }

    /** R15：钉住态（钉住条目在非收藏分区裁剪时跳过；上限由 SqlHistoryManager.MAX_PINNED 把关）。 */
    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    /** 分配唯一 id（仅当当前为空白时）。 */
    public void ensureId() {
        if (getId().isBlank()) {
            setId(UUID.randomUUID().toString());
        }
    }

    /** 摘要文本：取 compactSql 首行，截断至 [MAX_SUMMARY_LEN] 字符。 */
    public String summary() {
        String trimmed = getCompactSql().trim();
        String firstLine = trimmed.isEmpty() ? "" : trimmed.split("\n", -1)[0].trim();
        if (firstLine.length() <= MAX_SUMMARY_LEN) {
            return firstLine;
        }
        return firstLine.substring(0, MAX_SUMMARY_LEN) + "...";
    }

    /** 来源描述文案（如 "UserMapper.java:10"）。 */
    public String sourceLabel() {
        String sf = getSourceFile();
        if (sf.isBlank()) {
            return "";
        }
        return getSourceLine() > 0 ? sf + ":" + getSourceLine() : sf;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SqlHistoryEntry)) {
            return false;
        }
        SqlHistoryEntry that = (SqlHistoryEntry) o;
        return timestamp == that.timestamp && sourceLine == that.sourceLine && favorite == that.favorite
                && pinned == that.pinned
                && Objects.equals(id, that.id) && Objects.equals(sql, that.sql)
                && Objects.equals(compactSql, that.compactSql) && Objects.equals(sourceFile, that.sourceFile)
                && Objects.equals(tableName, that.tableName) && Objects.equals(tags, that.tags)
                && Objects.equals(executionTimeMs, that.executionTimeMs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, sql, compactSql, timestamp, sourceFile, sourceLine, tableName, favorite, tags, executionTimeMs, pinned);
    }

    @Override
    public String toString() {
        return "SqlHistoryEntry(id=" + id + ", sql=" + sql + ", compactSql=" + compactSql
                + ", timestamp=" + timestamp + ", sourceFile=" + sourceFile + ", sourceLine=" + sourceLine
                + ", tableName=" + tableName + ", favorite=" + favorite + ", tags=" + tags + ", executionTimeMs=" + executionTimeMs
                + ", pinned=" + pinned + ")";
    }
}
