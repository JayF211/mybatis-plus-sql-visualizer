// T-SyncK2JMaster482 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/HistorySearchService.kt 移植（R14 T-HistorySearch / T-01）
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.log.LogSqlEntry;
import cn.song.mybatisplus.visualizer.log.SqlLogFileReader;
import kotlin.Pair;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 历史落盘检索器（R14 T-HistorySearch / T-01）：在 SQL 历史自动落盘文件
 * （SqlLogAutoSaver，默认 {@code .idea/sql-visualizer/sql-history/}）之上做过滤检索——
 * 读取面复用 {@link SqlLogFileReader}（不重造），检索语义四维：关键词 / 正则 / 时间范围 / 来源。
 *
 * <p><b>域中立（D3=A）</b>：本对象零 com.intellij、零 mcpserver 依赖，输入输出全是纯数据——
 * MCP 工具（SqlVisualizerHistorySearchTool）与 R16 历史跨文件全文检索 UI 共用同一实现，
 * 一次投入两处收益。MCP 侧的文案格式化/入参校验转译在工具层，不在这里。</p>
 *
 * <p><b>「来源」维度的落盘实况（实施期重锚证伪）</b>：通道标记（SqlSource）在历史文件里
 * <b>仅 MCP 存活</b>——SqlLogParamCodec.encodeEntry 只对 MCP 落 {@code s} 键，控制台/agent 来源
 * 读回一律 UNKNOWN；头部 {@code source:} 段语义已被 parseSource 占用为源码位置。故四维过滤的
 * 「来源」按<b>来源文件（sourceFile）</b>实现（大小写不敏感子串，对 agent「找 UserService.java
 * 落过的 SQL」才有实用价值）；通道标记仅在命中元信息里透出，不做过滤维度。</p>
 *
 * <p><b>范围默认值（D1=A）</b>：默认只检索「今日」条目（条目时间戳 ≥ 当日本地零点——按条目
 * timestamp 过滤而非按文件名挑当日文件，滚动块/自定义模板/跨天追加文件一律天然正确）；
 * {@code allDates=true} 扩展到目录全部日期。默认范围内显式 {@code fromMs} 早于今日起点<b>不放宽</b>
 * 默认范围（要跨天必须显式 allDates=true，防 agent 无意全量扫描拉爆上下文）。</p>
 *
 * <p><b>返回体（D2=A）</b>：条目摘要（时间戳 + SQL 预览 [MAX_PREVIEW_LINES]/[MAX_PREVIEW_CHARS] 截断 +
 * 来源位置/耗时/落盘文件）+ 命中计数；<b>不返回全文</b>——agent 需要更多上下文时自行缩小关键词
 * 再查。{@code maxResults} 越界钳制不抛错；命中按时间戳<b>最新在前</b>，超限置 {@code truncated}。</p>
 *
 * <p><b>性能预算</b>：读取走 Reader 的流式逐行解析（峰值≈单条 SQL，T-SqlHistoryFull 先例）；
 * 扫描条目封顶 [MAX_SCAN_ENTRIES]，超限置 {@code scanTruncated} 不再读后续文件——目录被
 * 保留上限失控（maxFiles=0 全量保留）时检索仍有时界。目录不存在/空目录 = 合法零命中
 * （自动保存开关默认关，历史可能确实不存在——零命中是正常结果不是错误）。</p>
 *
 * <p><b>排序兜底（V14b）</b>：多文件读取顺序与滚动块排序语义不做保证，命中输出前按
 * timestamp 自行排序（Reader 的 per-file 排序只作输入预备）。</p>
 *
 * <p>移植注记：master 的 {@code object} 按本仓惯例落为 final class + {@link #INSTANCE} + 全静态成员；
 * 嵌套 data class（Query/Hit/Result）落为静态 record（带字段注释），Kotlin 默认参数以「全参规范构造 +
 * 无参缺省重载」表达；{@code Regex(pattern, IGNORE_CASE)} 落为
 * {@code Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)}，{@code containsMatchIn} 落为
 * {@code Matcher.find()}；{@code String.contains(other, ignoreCase=true)} 落为 regionMatches 子串扫描
 * （与 Kotlin 实现同口径，规避默认 Locale 差异）。</p>
 */
public final class HistorySearchService {

    private HistorySearchService() {
    }

    /** 单例（Kotlin object 惯例形态；静态方法为主）。 */
    public static final HistorySearchService INSTANCE = new HistorySearchService();

    /** 默认返回条数（D2=A：摘要 + 计数，防 agent 上下文爆炸）。 */
    public static final int DEFAULT_MAX_RESULTS = 20;

    /** 返回条数硬上限（maxResults 越界钳制到此）。 */
    public static final int HARD_MAX_RESULTS = 100;

    /** 单条 SQL 预览的行数上限。 */
    public static final int MAX_PREVIEW_LINES = 8;

    /** 单条 SQL 预览的字符上限（含省略号）。 */
    public static final int MAX_PREVIEW_CHARS = 600;

    /** 扫描条目封顶：超出后停止读后续文件（scanTruncated=true），防失控目录无界扫描。 */
    private static final int MAX_SCAN_ENTRIES = 200_000;

    /** 入参形态非法（正则编译失败 / 时间范围倒置）——MCP 侧转译为 INVALID_ARGUMENT。 */
    public static class InvalidQueryException extends IllegalArgumentException {
        public InvalidQueryException(String message) {
            super(message);
        }
    }

    /**
     * 检索查询（全部字段可选；空查询 = 默认范围内的全部条目）。
     *
     * @param keyword     关键词（大小写不敏感子串，匹配 rawSql/restoredSql/mapperStatement）
     * @param regex       正则（大小写不敏感，匹配 rawSql/restoredSql；编译失败抛 [InvalidQueryException]）
     * @param fromMs      时间范围下界（含；epoch 毫秒，按条目 timestamp）
     * @param toMs        时间范围上界（含）
     * @param sourceFile  来源文件过滤（大小写不敏感子串，匹配条目 sourceFile；
     *                    通道标记在落盘数据里仅 MCP 存活，故「来源」维度按来源文件实现）
     * @param allDates    false（默认）= 只检索今日条目（D1=A）；true = 检索目录全部日期
     * @param maxResults  返回条数上限（越界钳制 1..[HARD_MAX_RESULTS]）
     */
    public record Query(
        /** 关键词（大小写不敏感子串）。 */
        String keyword,
        /** 正则（大小写不敏感；编译失败抛 InvalidQueryException）。 */
        String regex,
        /** 时间范围下界（含；epoch 毫秒）。 */
        Long fromMs,
        /** 时间范围上界（含）。 */
        Long toMs,
        /** 来源文件过滤（大小写不敏感子串）。 */
        String sourceFile,
        /** false = 只检索今日条目；true = 检索目录全部日期。 */
        boolean allDates,
        /** 返回条数上限（越界钳制）。 */
        int maxResults
    ) {
        /** Kotlin 默认参数等价重载：全字段缺省（空查询 = 默认范围内的全部条目）。 */
        public Query() {
            this(null, null, null, null, null, false, DEFAULT_MAX_RESULTS);
        }
    }

    /** 命中条目摘要（D2=A：不含 SQL 全文）。 */
    public record Hit(
        /** 条目时间戳（epoch 毫秒）。 */
        long timestamp,
        /** SQL 预览（截断）。 */
        String sqlPreview,
        /** 来源（SqlSource 名）。 */
        String source,
        /** 来源文件（可空）。 */
        String sourceFile,
        /** 来源行号（0 表示未知）。 */
        int sourceLine,
        /** 执行耗时（毫秒；可空）。 */
        Long executionTimeMs,
        /** 目标数据库标注（可空）。 */
        String databaseName,
        /** mapper 语句 id（可空）。 */
        String mapperStatement,
        /** 执行报错（可空）。 */
        String errorText,
        /** 命中条目所在的落盘文件名。 */
        String historyFile
    ) {
    }

    /** 检索结果：命中摘要 + 计数（totalHits/returned/truncated 供调用方自行组「截断说明」）。 */
    public record Result(
        /** 总命中数（排序后、截断前）。 */
        int totalHits,
        /** 实际返回条数。 */
        int returned,
        /** 是否因 maxResults 截断。 */
        boolean truncated,
        /** 扫描的文件数。 */
        int scannedFiles,
        /** 扫描的条目数。 */
        int scannedEntries,
        /** 是否因条目封顶中断扫描。 */
        boolean scanTruncated,
        /** 本次生效的范围下界（epoch 毫秒）；allDates=true 且未给 fromMs 时为 null。 */
        Long scopeStartMs,
        /** 命中摘要清单（最新在前）。 */
        List<Hit> hits
    ) {
    }

    /**
     * 文件目录入口：扫目录下全部 .sql（大小写不敏感，文件名序），逐文件流式解析后进入纯逻辑核。
     */
    public static Result search(String dir, Query query) {
        return search(dir, query, System.currentTimeMillis());
    }

    /** 全参形态（Kotlin nowMs 默认参数显式入口）。 */
    public static Result search(String dir, Query query, long nowMs) {
        File directory = new File(dir);
        File[] listed = directory.listFiles(f -> f.isFile() && extensionOf(f).equalsIgnoreCase("sql"));
        List<File> files = new ArrayList<>();
        if (listed != null) {
            for (File f : listed) {
                files.add(f);
            }
        }
        files.sort(Comparator.comparing(File::getName));
        int scannedEntries = 0;
        boolean scanTruncated = false;
        List<Pair<String, List<LogSqlEntry>>> perFile = new ArrayList<>();
        for (File f : files) {
            if (scannedEntries >= MAX_SCAN_ENTRIES) {
                scanTruncated = true;
                perFile.add(new Pair<>(f.getName(), List.of()));
            } else {
                List<LogSqlEntry> entries = SqlLogFileReader.INSTANCE.readFromFile(f);
                scannedEntries += entries.size();
                if (scannedEntries > MAX_SCAN_ENTRIES) {
                    scanTruncated = true;
                }
                perFile.add(new Pair<>(f.getName(), entries));
            }
        }
        return search(
            perFile, query, nowMs,
            files.size(),
            scannedEntries,
            scanTruncated
        );
    }

    /** Kotlin 默认参数等价重载：scannedFiles/scannedEntries/scanTruncated 缺省（纯逻辑核直调）。 */
    public static Result search(List<Pair<String, List<LogSqlEntry>>> perFile, Query query, long nowMs) {
        int scannedEntries = 0;
        for (Pair<String, List<LogSqlEntry>> entry : perFile) {
            scannedEntries += entry.getSecond().size();
        }
        return search(perFile, query, nowMs, perFile.size(), scannedEntries, false);
    }

    /**
     * 纯逻辑核（域中立测试缝）：已在内存的「落盘文件名 → 条目」清单上执行四维过滤。
     */
    public static Result search(
        List<Pair<String, List<LogSqlEntry>>> perFile,
        Query query,
        long nowMs,
        int scannedFiles,
        int scannedEntries,
        boolean scanTruncated
    ) {
        int maxResults = Math.max(1, Math.min(HARD_MAX_RESULTS, query.maxResults()));
        String keyword = trimmedOrNull(query.keyword());
        Pattern regex = null;
        String regexPattern = trimmedOrNull(query.regex());
        if (regexPattern != null) {
            try {
                regex = Pattern.compile(regexPattern, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                throw new InvalidQueryException("invalid regex '" + regexPattern + "': " + e.getMessage());
            }
        }
        String sourceFileFilter = trimmedOrNull(query.sourceFile());
        if (query.fromMs() != null && query.toMs() != null && query.fromMs() > query.toMs()) {
            throw new InvalidQueryException(
                "invalid time range: fromMs (" + query.fromMs() + ") > toMs (" + query.toMs() + ")");
        }
        // D1=A：默认范围下界 = 当日本地零点；allDates 或显式更晚的 fromMs 才放宽
        long todayStart = startOfLocalDay(nowMs);
        Long scopeFrom;
        if (query.allDates()) {
            scopeFrom = query.fromMs();
        } else {
            scopeFrom = Math.max(todayStart, query.fromMs() != null ? query.fromMs() : Long.MIN_VALUE);
        }

        List<Pair<String, LogSqlEntry>> matched = new ArrayList<>();
        for (Pair<String, List<LogSqlEntry>> fileEntry : perFile) {
            String fileName = fileEntry.getFirst();
            for (LogSqlEntry e : fileEntry.getSecond()) {
                if (!(scopeFrom == null || e.timestamp() >= scopeFrom)) {
                    continue;
                }
                if (!(query.toMs() == null || e.timestamp() <= query.toMs())) {
                    continue;
                }
                if (!textMatches(e, keyword, regex)) {
                    continue;
                }
                if (!(sourceFileFilter == null
                    || (e.sourceFile() != null && containsIgnoreCase(e.sourceFile(), sourceFileFilter)))) {
                    continue;
                }
                matched.add(new Pair<>(fileName, e));
            }
        }

        // V14b：输出前自行按时间戳排序（最新在前，稳定序：同毫秒按落盘文件名 + 文件内序号）
        matched.sort(
            Comparator.<Pair<String, LogSqlEntry>>comparingLong(p -> p.getSecond().timestamp()).reversed()
                .thenComparing(p -> p.getFirst())
                .thenComparingLong(p -> p.getSecond().sequenceNumber())
        );

        List<Hit> hits = new ArrayList<>();
        int limit = Math.min(maxResults, matched.size());
        for (int i = 0; i < limit; i++) {
            Pair<String, LogSqlEntry> fileEntry = matched.get(i);
            String fileName = fileEntry.getFirst();
            LogSqlEntry e = fileEntry.getSecond();
            hits.add(new Hit(
                e.timestamp(),
                preview(e.restoredSql()),
                e.source().name(),
                e.sourceFile(),
                e.sourceLine(),
                e.executionTimeMs(),
                e.databaseName(),
                e.mapperStatement(),
                e.errorText(),
                fileName
            ));
        }
        return new Result(
            matched.size(),
            hits.size(),
            matched.size() > hits.size(),
            scannedFiles,
            scannedEntries,
            scanTruncated,
            scopeFrom,
            hits
        );
    }

    /** 关键词/正则/来源文件三者的文本匹配核（keyword 与 regex 均为 null 时恒真）。 */
    private static boolean textMatches(LogSqlEntry e, String keyword, Pattern regex) {
        boolean keywordHit = keyword == null
            || containsIgnoreCase(e.rawSql(), keyword)
            || containsIgnoreCase(e.restoredSql(), keyword)
            || (e.mapperStatement() != null && containsIgnoreCase(e.mapperStatement(), keyword));
        if (!keywordHit) {
            return false;
        }
        return regex == null
            || regex.matcher(e.rawSql()).find()
            || regex.matcher(e.restoredSql()).find();
    }

    /** 当日本地零点（epoch 毫秒）。 */
    private static long startOfLocalDay(long nowMs) {
        return Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** SQL 预览（D2=A）：前 [MAX_PREVIEW_LINES] 行且 ≤ [MAX_PREVIEW_CHARS] 字符，截断带省略号。 */
    private static String preview(String sql) {
        List<String> lines = sql.lines().toList();
        List<String> taken = lines.subList(0, Math.min(MAX_PREVIEW_LINES, lines.size()));
        String out = String.join("\n", taken);
        if (out.length() > MAX_PREVIEW_CHARS) {
            out = out.substring(0, MAX_PREVIEW_CHARS - 1) + "…";
        } else if (lines.size() > taken.size()) {
            out += "\n…";
        }
        return out;
    }

    /** Kotlin {@code String?.trim()?.takeIf { it.isNotEmpty() }} 等价（空白归 null）。 */
    private static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Kotlin {@code String.contains(other, ignoreCase = true)} 等价（regionMatches 子串扫描）。 */
    private static boolean containsIgnoreCase(String text, String sub) {
        if (sub.isEmpty()) {
            return true;
        }
        int max = text.length() - sub.length();
        for (int i = 0; i <= max; i++) {
            if (text.regionMatches(true, i, sub, 0, sub.length())) {
                return true;
            }
        }
        return false;
    }

    /** Kotlin {@code File.extension} 等价（末个 {@code .} 之后；无点返回空串）。 */
    private static String extensionOf(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }
}
