package cn.song.mybatisplus.visualizer.log;

import kotlin.Pair;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 .idea/sql-visualizer/sql-history/ 文件反解析为 LogSqlEntry（F-44；F-44-4 目录随插件更名）。
 *
 * 支持两种格式：
 *   - 新版（F-44-2）：-- source: xxx | time: xxx | yyyy-MM-dd HH:mm:ss.SSS --\nSQL\n
 *   - 历史 F-44（已停止生产）：--- source: xxx | time: xxx | timestamp ---\nSQL\n
 *   - 旧版（F-43）：-- yyyy-MM-dd HH:mm:ss\nSQL\n
 *
 * 混合文件中三种格式共存时均可正确解析。
 */
public final class SqlLogFileReader {

    public static final SqlLogFileReader INSTANCE = new SqlLogFileReader();

    private SqlLogFileReader() {
    }

    /**
     * 新版格式头部：兼容 2 或 3 个 `-` 起结（前缀与后缀的 `-` 数量独立，长度匹配即可）。
     * 捕获组：1=source 2=time 3=timestamp
     */
    private static final Pattern NEW_HEADER_REGEX = Pattern.compile(
            "^--+ source: (.+?) \\| time: (.+?) \\| (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d{3})?) --+$");

    /** 旧版格式头部：-- timestamp（无源码位置和耗时） */
    private static final Pattern OLD_HEADER_REGEX = Pattern.compile(
            "^-- (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d{3})?)$");

    /**
     * 参数持久化行前缀/结尾（T-HistParam F1 格式 v2）：头部注释行之下的独立注释行，
     * 载荷为 Base64 编码的 rawSql + params（编解码见 SqlLogParamCodec）。
     * 前缀固定 `-- pd: ` 且以 ` --` 结尾——旧版头部判据（`-- ` 起始无 `source:`）会把
     * pd 行误判为 F-43 头部导致块被劈开，块切分与计数必须显式排除。
     */
    private static final String PD_PREFIX = "-- pd: ";
    private static final String PD_SUFFIX = " --";

    /**
     * 读取目录下所有 .sql 文件，返回按时间戳排序的 LogSqlEntry 列表。
     */
    public List<LogSqlEntry> readAll(String dir) {
        File directory = new File(dir);
        if (!directory.isDirectory()) {
            return List.of();
        }
        File[] files = directory.listFiles(f -> f.getName().endsWith("sql"));
        if (files == null) {
            return List.of();
        }
        List<LogSqlEntry> all = new ArrayList<>();
        for (File f : files) {
            all.addAll(readFromFile(f));
        }
        all.sort(Comparator.comparingLong(LogSqlEntry::timestamp));
        return all;
    }

    public List<LogSqlEntry> readFromFile(File file) {
        if (!file.exists()) {
            return List.of();
        }
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            return parseContent(content);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 快速统计文件中的 SQL 条目数（F-44-3 两级导航一级列表用）：
     * 只数头部行，不构建 LogSqlEntry。判定规则与 {@link #parseContent(String)} 保持一致。
     */
    public int countInFile(File file) {
        if (!file.exists()) {
            return 0;
        }
        int count = 0;
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.stripLeading();
                if (trimmed.startsWith("--") && trimmed.contains("source:") && trimmed.contains("time:")) {
                    count++;
                } else if (line.startsWith("-- ") && !line.contains("source:") && !line.startsWith(PD_PREFIX)) {
                    count++;
                }
            }
        } catch (Exception e) {
            return count;
        }
        return count;
    }

    /**
     * 解析文件内容为 LogSqlEntry 列表。逐行扫描，遇到头部行则开始新块，兼容新旧两种头部混合。
     */
    public List<LogSqlEntry> parseContent(String content) {
        List<LogSqlEntry> entries = new ArrayList<>();
        List<String> currentLines = new ArrayList<>();
        for (String line : content.lines().toList()) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("--") && trimmed.contains("source:") && trimmed.contains("time:")) {
                flushBlock(currentLines, entries);
                currentLines.clear();
                currentLines.add(line);
            } else if (line.startsWith("-- ") && !line.contains("source:") && !line.startsWith(PD_PREFIX)) {
                // 检测旧版头部（F-43：`-- yyyy-MM-dd HH:mm:ss`）；`-- pd: ` 是参数持久化行非头部，
                // 不排除会被当作旧头部把条目劈成两块（T-HistParam）
                flushBlock(currentLines, entries);
                currentLines.clear();
                currentLines.add(line);
            } else {
                currentLines.add(line);
            }
        }
        flushBlock(currentLines, entries);
        return entries;
    }

    private void flushBlock(List<String> currentLines, List<LogSqlEntry> out) {
        if (currentLines.isEmpty()) {
            return;
        }
        String block = String.join("\n", currentLines);
        LogSqlEntry e = parseBlock(block);
        if (e != null) {
            out.add(e);
        }
    }

    private LogSqlEntry parseBlock(String block) {
        List<String> lines = block.lines().toList();
        if (lines.isEmpty()) {
            return null;
        }
        String header = lines.get(0);
        // T-HistParam F2：v2 头部下探测参数持久化行（`-- pd: <base64> --`），解码失败按普通
        // 正文注释行降级（SQL 多一行注释无害）；解码成功则 rawSql 与 params 从载荷还原
        List<String> contentLines = lines.subList(1, lines.size());
        List<String> bodyLines = contentLines;
        String rawSqlOverride = null;
        List<LogSqlParam> params = List.of();
        if (!contentLines.isEmpty()) {
            String first = contentLines.get(0);
            String trimmedFirst = first.trim();
            if (trimmedFirst.startsWith(PD_PREFIX) && trimmedFirst.endsWith(PD_SUFFIX)) {
                kotlin.Pair<String, java.util.List<LogSqlParam>> decoded = SqlLogParamCodec.decode(
                    trimmedFirst.substring(PD_PREFIX.length(), trimmedFirst.length() - PD_SUFFIX.length()));
                if (decoded != null) {
                    rawSqlOverride = decoded.getFirst();
                    params = decoded.getSecond();
                    bodyLines = contentLines.subList(1, contentLines.size());
                }
            }
        }
        // 写入端 withTerminator 会补 `;`（T-SqlDuration），读回去掉还原为原始 SQL——
        // 展示/复制路径会重新调用 withTerminator() 补回，此处只剥结尾一个
        String sql = String.join("\n", bodyLines).trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.isEmpty()) {
            return null;
        }

        Matcher m1 = NEW_HEADER_REGEX.matcher(header);
        if (m1.find()) {
            String sourceStr = m1.group(1);
            String timeStr = m1.group(2);
            String timestampStr = m1.group(3);
            Long timestamp = parseTimestampNew(timestampStr);
            if (timestamp == null) {
                return null;
            }
            Pair<String, Integer> src = parseSource(sourceStr);
            Long executionTimeMs = parseDuration(timeStr);
            return new LogSqlEntry(rawSqlOverride != null ? rawSqlOverride : sql, sql, params, timestamp,
                    src.getFirst(), src.getSecond(), executionTimeMs);
        }

        Matcher m2 = OLD_HEADER_REGEX.matcher(header);
        if (m2.find()) {
            String timestampStr = m2.group(1);
            Long timestamp = parseTimestampOld(timestampStr);
            if (timestamp == null) {
                return null;
            }
            return new LogSqlEntry(rawSqlOverride != null ? rawSqlOverride : sql, sql, params, timestamp);
        }

        return null;
    }

    private Pair<String, Integer> parseSource(String sourceStr) {
        if ("unknown".equals(sourceStr)) {
            return new Pair<>(null, 0);
        }
        int colonIdx = sourceStr.lastIndexOf(':');
        if (colonIdx > 0) {
            String file = sourceStr.substring(0, colonIdx);
            Integer line = toIntOrNull(sourceStr.substring(colonIdx + 1));
            return new Pair<>(file, line != null ? line : 0);
        }
        return new Pair<>(sourceStr, 0);
    }

    private Long parseDuration(String timeStr) {
        if ("—".equals(timeStr) || "-".equals(timeStr) || timeStr.isBlank()) {
            return null;
        }
        String s = timeStr.endsWith("ms") ? timeStr.substring(0, timeStr.length() - 2) : timeStr;
        return toLongOrNull(s);
    }

    private Long parseTimestampNew(String ts) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ENGLISH).parse(ts).getTime();
        } catch (Exception e) {
            return null;
        }
    }

    private Long parseTimestampOld(String ts) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).parse(ts).getTime();
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer toIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long toLongOrNull(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
