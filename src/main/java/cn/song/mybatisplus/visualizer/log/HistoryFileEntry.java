package cn.song.mybatisplus.visualizer.log;

import org.jetbrains.annotations.NotNull;

import java.io.File;

/**
 * 历史 SQL 文件摘要（一级列表数据源，F-44-3）。
 */
public record HistoryFileEntry(
        /** 历史文件（.sql）。 */
        @NotNull File file,
        /** 文件内 SQL 条目数（[SqlLogFileReader.countInFile] 轻量统计）。 */
        int sqlCount
) {
    /** 展示名（文件名）。 */
    public String getDisplayName() {
        return file.getName();
    }

    /** 文件大小（字节）。 */
    public long getSizeBytes() {
        return file.length();
    }
}
