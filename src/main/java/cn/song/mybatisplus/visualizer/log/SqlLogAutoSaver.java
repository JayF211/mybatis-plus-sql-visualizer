package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动保存历史 SQL 到文件（F-43）：[SqlLogManager] 装配为 buffer 的 onEvicted 回调。
 *
 * 文件规则：
 *  - 默认路径：.idea/sql-visualizer/sql-history/（F-44-4：随插件更名）
 *  - 文件名模板：sql-history-$yyyy-MM-dd$.sql（支持 $yyyy$ $MM$ $dd$ $HH$ $mm$ $ss$ 占位符）
 *  - 文件滚动：单文件 ≥100MB 时滚动（.2.sql、.3.sql…）
 *  - 格式：`-- 时间戳\nSQL\n\n`（纯文本，可直接在数据库工具中打开）
 *
 * 生命周期跟随 [SqlLogManager]：manager dispose 时 [close] 释放文件句柄。
 */
public class SqlLogAutoSaver {

    private BufferedWriter writer = null;
    private File currentFile = null;
    private String saveDir = "";
    private String fileNamePattern = "";
    private String currentResolvedName = "";
    private int currentIndex = 1;

    /** 单文件滚动阈值（100MB）。 */
    private final long rollThresholdBytes = 100L * 1024 * 1024;

    /**
     * 配置保存参数（设置变更时调用）。
     * @param customPath 用户自定义目录（空串 = 默认 .idea 路径）
     * @param pattern 文件名模板（空串 = 默认模板）
     * @param projectBasePath 项目基路径（默认路径计算用）
     */
    public void configure(String customPath, String pattern, String projectBasePath) {
        close();
        saveDir = resolveDir(customPath, projectBasePath);
        fileNamePattern = pattern.isBlank() ? PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN : pattern;
        currentResolvedName = "";
        currentIndex = 1;
    }

    /** 解析最终保存目录。 */
    private String resolveDir(String customPath, String projectBasePath) {
        if (!customPath.isBlank()) {
            return customPath.trim();
        }
        if (projectBasePath != null) {
            return projectBasePath + File.separator + DATA_DIR_NAME + File.separator + "sql-history";
        }
        return System.getProperty("user.home") + File.separator + DATA_DIR_NAME + File.separator + "sql-history";
    }

    /**
     * 写入被淘汰的条目（buffer.onEvicted 回调）。首次写入时懒打开文件。
     */
    public void write(LogSqlEntry entry) {
        BufferedWriter w = ensureWriter();
        if (w == null) {
            return;
        }
        String resolved = resolveFileName();
        if (!resolved.equals(currentResolvedName) ||
                (currentFile != null ? currentFile.length() : 0) >= rollThresholdBytes) {
            write(entry);
            return;
        }
        writeEntry(w, entry);
    }

    /** 确保 writer 就绪（首次打开 / 切换文件 / 滚动）。 */
    private BufferedWriter ensureWriter() {
        if (writer != null) {
            return writer;
        }
        currentResolvedName = resolveFileName();
        currentIndex = 1;
        openWriterForCurrent();
        return writer;
    }

    /** 根据 currentResolvedName + currentIndex 打开文件。 */
    private void openWriterForCurrent() {
        File file = new File(currentFileName());
        try {
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            // APPEND 模式：多次启动后历史内容追加而非覆盖
            writer = Files.newBufferedWriter(
                    file.toPath(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND
            );
            currentFile = file;
        } catch (Throwable t) {
            LOG.warn("自动保存历史 SQL：无法打开文件 " + file.getAbsolutePath(), t);
            writer = null;
            currentFile = null;
        }
    }

    /** 当前应写入的完整文件名（含目录 + 滚动序号后缀）。 */
    private String currentFileName() {
        String name = currentResolvedName;
        String fullName = name.contains(File.separator) ? name : saveDir + File.separator + name;
        if (currentIndex <= 1) {
            return fullName;
        }
        int dotIdx = fullName.lastIndexOf('.');
        if (dotIdx > 0) {
            return fullName.substring(0, dotIdx) + "." + currentIndex + fullName.substring(dotIdx);
        }
        return fullName + "." + currentIndex;
    }

    /**
     * 写入单条条目到文件。格式（F-44-2）：-- source: xxx | time: xxx | timestamp --\nSQL\n
     */
    private void writeEntry(BufferedWriter w, LogSqlEntry entry) {
        try {
            synchronized (w) {
                String source;
                if (entry.sourceFile() != null) {
                    source = entry.sourceLine() > 0
                            ? entry.sourceFile() + ":" + entry.sourceLine()
                            : entry.sourceFile();
                } else {
                    source = "unknown";
                }
                String duration = entry.executionTimeMs() != null ? entry.executionTimeMs() + "ms" : "—";
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ENGLISH)
                        .format(new Date(entry.timestamp()));
                w.write("-- source: " + source + " | time: " + duration + " | " + timestamp + " --\n");
                w.write(SqlLogPanelKt.withTerminator(entry.restoredSql()));
                w.write("\n\n");
                w.flush();
            }
        } catch (Throwable t) {
            LOG.warn("自动保存历史 SQL：写入失败", t);
        }
    }

    /**
     * 解析文件名模板（替换 $日期格式$ 占位符为 SimpleDateFormat 格式化结果）。
     * 若占位符内容不含日期模式字符（如 $unknown$），则原样保留。
     * Windows 文件名不允许 :，自动替换为 -。
     */
    public String resolveFileName() {
        long now = System.currentTimeMillis();
        Matcher m = PLACEHOLDER_REGEX.matcher(fileNamePattern);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String formatStr = m.group(1);
            String formatted;
            try {
                formatted = new SimpleDateFormat(formatStr, Locale.ENGLISH).format(new Date(now));
            } catch (Exception e) {
                formatted = m.group(0);
            }
            if (formatted.equals(formatStr)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(formatted));
            }
        }
        m.appendTail(sb);
        return sb.toString().replace(':', '-');
    }

    /** 当前打开的保存文件（测试访问 + 状态展示用）。 */
    public File currentFile() {
        return currentFile;
    }

    /** 关闭文件句柄（manager dispose 或路径变更时调用）。 */
    public void close() {
        if (writer != null) {
            try {
                synchronized (writer) {
                    writer.close();
                }
            } catch (Throwable t) {
                LOG.debug("自动保存历史 SQL：关闭文件异常", t);
            }
        }
        writer = null;
        currentFile = null;
    }

    private static final Logger LOG = Logger.getInstance(SqlLogAutoSaver.class);

    /**
     * 项目内插件数据根目录名（F-44-4）：随插件更名「SQL Visualizer」由旧名
     * `mybatis-plus-sql-visualizer` 改为 `sql-visualizer`，**不考虑向前兼容**。
     */
    public static final String DATA_DIR_NAME = "sql-visualizer";

    private static final String DEFAULT_DIR = DATA_DIR_NAME + File.separator + "sql-history";

    /** 匹配 $变量$ 占位符。 */
    private static final Pattern PLACEHOLDER_REGEX = Pattern.compile("\\$([^$]+)\\$");
}
