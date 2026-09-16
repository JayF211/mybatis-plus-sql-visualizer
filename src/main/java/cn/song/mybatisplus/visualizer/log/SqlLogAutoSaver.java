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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
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
 *
 * T-AutoSaverSOE：write 的文件切换/滚动改循环判定，不再递归自调用（跨天/模板变更 +
 * 单文件超限叠加时旧实现递归两次以上可致 StackOverflowError）；configure/write/close 加锁，
 * 消除并发写与滚动判定竞态。
 */
public class SqlLogAutoSaver {

    /** 时钟（测试注入用），默认系统时钟。 */
    private final LongSupplier clock;
    /** 单文件滚动阈值（默认 100MB），测试注入小值用。 */
    private final long rollThresholdBytes;

    private BufferedWriter writer = null;
    private File currentFile = null;
    private String saveDir = "";
    private String fileNamePattern = "";
    private String currentResolvedName = "";
    private int currentIndex = 1;

    // ---------- T-Perf-12：异步批量写（淘汰条目投递无锁队列 + 专用单线程执行器） ----------

    /** 在途淘汰条目队列（控制台输出线程投递，写线程消费）。 */
    private final java.util.concurrent.ConcurrentLinkedQueue<LogSqlEntry> pendingEntries =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 本轮已调度标志（幂等调度——风暴期 N 条投递 = 1 个写任务）。 */
    private final java.util.concurrent.atomic.AtomicBoolean drainScheduled =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 专用写线程（懒创建；close 后下次 submit 重建）。 */
    private volatile java.util.concurrent.ExecutorService executor = null;

    /** 默认构造（系统时钟 + 100MB 滚动阈值）。 */
    public SqlLogAutoSaver() {
        this(System::currentTimeMillis, 100L * 1024 * 1024);
    }

    /** 完整构造（测试注入时钟与滚动阈值）。 */
    public SqlLogAutoSaver(LongSupplier clock, long rollThresholdBytes) {
        this.clock = clock;
        this.rollThresholdBytes = rollThresholdBytes;
    }

    /**
     * T-Perf-12：异步写入入口（buffer.onEvicted 回调用）——投递后由专用后台线程批量写出。
     * 此前回调在控制台输出热路径同步执行「检查文件长度 + 写两行 + flush 磁盘」，风暴期数百次/秒，
     * 且 EDT 刷新列表 snapshot() 抢同一把锁互相阻塞。
     */
    public void submit(LogSqlEntry entry) {
        pendingEntries.add(entry);
        if (drainScheduled.compareAndSet(false, true)) {
            ensureExecutor().execute(this::drainAndWrite);
        }
    }

    private java.util.concurrent.ExecutorService ensureExecutor() {
        java.util.concurrent.ExecutorService current = executor;
        if (current != null && !current.isShutdown()) {
            return current;
        }
        java.util.concurrent.ExecutorService created = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mpsv-sql-history-autosave");
            t.setDaemon(true);
            return t;
        });
        executor = created;
        return created;
    }

    /** 写任务：整批一次 flush；写期间新到条目重新调度（不丢条）。 */
    private void drainAndWrite() {
        drainScheduled.set(false);
        List<LogSqlEntry> batch = new java.util.ArrayList<>();
        while (true) {
            LogSqlEntry e = pendingEntries.poll();
            if (e == null) {
                break;
            }
            batch.add(e);
        }
        if (!batch.isEmpty()) {
            writeBatch(batch);
        }
    }

    /** close 前同步清空在途队列（不丢条；close 持有同一监视器，写任务串行）。 */
    private void drainPendingSync() {
        List<LogSqlEntry> batch = new java.util.ArrayList<>();
        while (true) {
            LogSqlEntry e = pendingEntries.poll();
            if (e == null) {
                break;
            }
            batch.add(e);
        }
        if (!batch.isEmpty()) {
            writeBatch(batch);
        }
    }

    /** 单文件滚动阈值（测试断言默认值用）。 */
    public long getRollThresholdBytes() {
        return rollThresholdBytes;
    }

    /**
     * 配置保存参数（设置变更时调用）。
     * @param customPath 用户自定义目录（空串 = 默认 .idea 路径）
     * @param pattern 文件名模板（空串 = 默认模板）
     * @param projectBasePath 项目基路径（默认路径计算用）
     */
    public synchronized void configure(String customPath, String pattern, String projectBasePath) {
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
     * 写入被淘汰的条目（同步 API——测试/直接调用；生产回调路径请走 {@link #submit}）。
     * 懒打开文件；文件名变化（跨天/模板变更）时重开并复位滚动序号，
     * 单文件超限时递增滚动序号。含 flush（调用方拿到的文件内容即时可见）。
     */
    public synchronized void write(LogSqlEntry entry) {
        prepareForEntry(entry);
        BufferedWriter w = writer;
        if (w == null) {
            return;
        }
        writeEntry(w, entry);
        flushQuietly();
    }

    /** T-Perf-12：批量写出——滚动/重开状态机逐条应用，<b>整批仅一次 flush</b>。 */
    public synchronized void writeBatch(List<LogSqlEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        for (LogSqlEntry entry : entries) {
            prepareForEntry(entry);
            BufferedWriter w = writer;
            if (w == null) {
                continue;
            }
            writeEntry(w, entry);
        }
        flushQuietly();
    }

    /** 写入前状态机：首写/文件名切换/滚动阈值检查与重开（须在 synchronized 方法内调用）。 */
    private void prepareForEntry(LogSqlEntry entry) {
        String resolved = resolveFileName();
        if (writer == null || !resolved.equals(currentResolvedName)) {
            // 首次写入 / 文件名切换：重开文件，滚动序号复位
            closeWriter();
            currentResolvedName = resolved;
            currentIndex = 1;
            openWriterForCurrent();
        } else if ((currentFile != null ? currentFile.length() : 0L) >= rollThresholdBytes) {
            // 单文件超限：滚动（xxx.sql → xxx.2.sql）
            closeWriter();
            currentIndex += 1;
            openWriterForCurrent();
        }
    }

    /** 静默 flush（写失败不阻断批次）。 */
    private void flushQuietly() {
        try {
            if (writer != null) {
                writer.flush();
            }
        } catch (Throwable t) {
            LOG.warn("自动保存历史 SQL：flush 失败", t);
        }
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
            // 头部：源码位置 | 耗时 | 时间戳 | 日志原始时间（可选）
            String source;
            if (entry.sourceFile() != null) {
                source = entry.sourceLine() > 0
                        ? entry.sourceFile() + ":" + entry.sourceLine()
                        : entry.sourceFile();
            } else {
                source = "unknown";
            }
            String duration = entry.executionTimeMs() != null ? entry.executionTimeMs() + "ms" : "—";
            // T-Perf-12：SimpleDateFormat（每条新建/线程缓存）→ DateTimeFormatter 常量（不可变线程安全）
            String timestamp = TIMESTAMP_FORMATTER.format(
                    java.time.Instant.ofEpochMilli(entry.timestamp()).atZone(java.time.ZoneId.systemDefault()));
            // 日志原始时间（解析到才写，向后兼容旧文件）
            String logTimePart = entry.logTimestamp() != null ? " | logTime: " + entry.logTimestamp() : "";
            // T-AgentDialectLoop：目标库显示名（agent 通道才有，解析到才写）
            String dbPart = entry.databaseName() != null ? " | db: " + entry.databaseName() : "";
            w.write("-- source: " + source + " | time: " + duration + " | " + timestamp
                    + logTimePart + dbPart + " --\n");
            // T-HistParam F1：参数持久化——params 非空或 rawSql 含 `?`（绑定行缺失未识别条目）时，
            // 在头部下写独立 Base64 注释行（正文保持可执行 restoredSql；读端 SqlLogFileReader 探测解码）。
            // M56 T-M56-00：扩展字段（mapperStatement/errorText/requestTag/updateCount）任一在场也写
            // pd 行（载荷扩展键，仅非默认落键）——否则 agent 通道结构化信息在历史文件丢失。
            if (!entry.params().isEmpty() || LogEntryPreviewRecognizerKt.countPlaceholders(entry.rawSql()) > 0
                    || entry.mapperStatement() != null || entry.errorText() != null
                    || entry.requestTag() != null || entry.updateCount() != null) {
                w.write("-- pd: " + SqlLogParamCodec.encodeEntry(entry) + " --\n");
            }
            w.write(SqlLogPanelKt.withTerminator(entry.restoredSql()));
            w.write("\n\n");
            // T-Perf-12：flush 上移到批末（writeBatch）/同步 API（write）——写线程逐条 flush 磁盘已移除
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
        return resolveFileName(clock.getAsLong());
    }

    /** 指定时刻解析文件名模板（T-AutoSaverSOE 测试注入用）。 */
    public String resolveFileName(long now) {
        Matcher m = PLACEHOLDER_REGEX.matcher(fileNamePattern);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String formatStr = m.group(1);
            String formatted;
            try {
                formatted = formatter(formatStr).format(new Date(now));
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

    /** SimpleDateFormat 非线程安全，按线程+模板缓存，避免每条日志重建。 */
    private final ThreadLocal<Map<String, SimpleDateFormat>> formatterCache =
            ThreadLocal.withInitial(HashMap::new);

    private SimpleDateFormat formatter(String formatStr) {
        return formatterCache.get().computeIfAbsent(formatStr,
                f -> new SimpleDateFormat(f, Locale.ENGLISH));
    }

    /** 当前打开的保存文件（测试访问 + 状态展示用）。 */
    public File currentFile() {
        return currentFile;
    }

    /**
     * 关闭文件句柄（manager dispose 或路径变更时调用）。
     * T-Perf-12：先同步清空在途队列（不丢条），再停写线程、关句柄；
     * 后续 configure/submit 可重建执行器。
     */
    public synchronized void close() {
        drainPendingSync();
        java.util.concurrent.ExecutorService current = executor;
        if (current != null) {
            current.shutdownNow();
        }
        executor = null;
        drainScheduled.set(false);
        pendingEntries.clear();
        closeWriter();
    }

    /** 仅关闭当前 writer（滚动/切换文件用；不动队列与执行器）。 */
    private void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (Throwable t) {
                LOG.debug("自动保存历史 SQL：关闭文件异常", t);
            }
        }
        writer = null;
        currentFile = null;
    }

    private static final Logger LOG = Logger.getInstance(SqlLogAutoSaver.class);

    /** T-Perf-12：条目时间戳格式（DateTimeFormatter 不可变线程安全，替代逐条 SimpleDateFormat）。 */
    private static final java.time.format.DateTimeFormatter TIMESTAMP_FORMATTER =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ENGLISH);

    /**
     * 项目内插件数据根目录名（F-44-4）：随插件更名「SQL Visualizer」由旧名
     * `mybatis-plus-sql-visualizer` 改为 `sql-visualizer`，**不考虑向前兼容**。
     */
    public static final String DATA_DIR_NAME = "sql-visualizer";

    private static final String DEFAULT_DIR = DATA_DIR_NAME + File.separator + "sql-history";

    /** 匹配 $变量$ 占位符。 */
    private static final Pattern PLACEHOLDER_REGEX = Pattern.compile("\\$([^$]+)\\$");
}
