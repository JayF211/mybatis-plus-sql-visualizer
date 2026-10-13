package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.SqlLogFileReader
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * 历史落盘检索器（R14 T-HistorySearch / T-01）：在 SQL 历史自动落盘文件
 * （SqlLogAutoSaver，默认 `.idea/sql-visualizer/sql-history/`）之上做过滤检索——
 * 读取面复用 [SqlLogFileReader]（不重造），检索语义四维：关键词 / 正则 / 时间范围 / 来源。
 *
 * **域中立（D3=A）**：本对象零 com.intellij、零 mcpserver 依赖，输入输出全是纯数据——
 * 当前唯一调用方=MCP 工具（SqlVisualizerHistorySearchTool），MCP 侧的文案格式化/入参校验
 * 转译在工具层，不在这里。F94 改注（T-PerfSweep25，文档漂移修正）：原注「与 R16 历史跨文件
 * 全文检索 UI 共用同一实现」与实况不符——R16 UI（HistorySqlLogPanel 的文件名/条目级检索）
 * 走自身的 filterHistoryEntries 实现，不经本服务；域中立形态保留为未来复用留位，非既成事实。
 *
 * **「来源」维度的落盘实况（实施期重锚证伪）**：通道标记（SqlSource）在历史文件里
 * **仅 MCP 存活**——SqlLogParamCodec.encodeEntry 只对 MCP 落 `s` 键，控制台/agent 来源
 * 读回一律 UNKNOWN；头部 `source:` 段语义已被 parseSource 占用为源码位置。故四维过滤的
 * 「来源」按**来源文件（sourceFile）**实现（大小写不敏感子串，对 agent「找 UserService.java
 * 落过的 SQL」才有实用价值）；通道标记仅在命中元信息里透出，不做过滤维度。
 *
 * **范围默认值（D1=A）**：默认只检索「今日」条目（条目时间戳 ≥ 当日本地零点——按条目
 * timestamp 过滤而非按文件名挑当日文件，滚动块/自定义模板/跨天追加文件一律天然正确）；
 * `allDates=true` 扩展到目录全部日期。默认范围内显式 `fromMs` 早于今日起点**不放宽**
 * 默认范围（要跨天必须显式 allDates=true，防 agent 无意全量扫描拉爆上下文）。
 *
 * **返回体（D2=A）**：条目摘要（时间戳 + SQL 预览 [MAX_PREVIEW_LINES]/[MAX_PREVIEW_CHARS] 截断 +
 * 来源位置/耗时/落盘文件）+ 命中计数；**不返回全文**——agent 需要更多上下文时自行缩小关键词
 * 再查。`maxResults` 越界钳制不抛错；命中按时间戳**最新在前**，超限置 `truncated`。
 *
 * **性能预算**：读取走 Reader 的流式逐行解析（峰值≈单条 SQL，T-SqlHistoryFull 先例）；
 * 扫描条目封顶 [MAX_SCAN_ENTRIES]，超限置 `scanTruncated` 不再读后续文件——目录被
 * 保留上限失控（maxFiles=0 全量保留）时检索仍有时界。目录不存在/空目录 = 合法零命中
 * （自动保存开关默认关，历史可能确实不存在——零命中是正常结果不是错误）。
 *
 * **排序兜底（V14b）**：多文件读取顺序与滚动块排序语义不做保证，命中输出前按
 * timestamp 自行排序（Reader 的 per-file 排序只作输入预备）。
 */
object HistorySearchService {

    /** 默认返回条数（D2=A：摘要 + 计数，防 agent 上下文爆炸）。 */
    const val DEFAULT_MAX_RESULTS = 20

    /** 返回条数硬上限（maxResults 越界钳制到此）。 */
    const val HARD_MAX_RESULTS = 100

    /** 单条 SQL 预览的行数上限。 */
    const val MAX_PREVIEW_LINES = 8

    /** 单条 SQL 预览的字符上限（含省略号）。 */
    const val MAX_PREVIEW_CHARS = 600

    /** 扫描条目封顶：超出后停止读后续文件（scanTruncated=true），防失控目录无界扫描。 */
    private const val MAX_SCAN_ENTRIES = 200_000

    /** 入参形态非法（正则编译失败 / 时间范围倒置）——MCP 侧转译为 INVALID_ARGUMENT。 */
    class InvalidQueryException(message: String) : IllegalArgumentException(message)

    /**
     * 检索查询（全部字段可选；空查询 = 默认范围内的全部条目）。
     *
     * @param keyword 关键词（大小写不敏感子串，匹配 rawSql/restoredSql/mapperStatement）
     * @param regex   正则（大小写不敏感，匹配 rawSql/restoredSql；编译失败抛 [InvalidQueryException]）
     * @param fromMs  时间范围下界（含；epoch 毫秒，按条目 timestamp）
     * @param toMs    时间范围上界（含）
     * @param sourceFile 来源文件过滤（大小写不敏感子串，匹配条目 sourceFile；
     *                   通道标记在落盘数据里仅 MCP 存活，故「来源」维度按来源文件实现）
     * @param allDates false（默认）= 只检索今日条目（D1=A）；true = 检索目录全部日期
     * @param maxResults 返回条数上限（越界钳制 1..[HARD_MAX_RESULTS]）
     */
    data class Query(
        val keyword: String? = null,
        val regex: String? = null,
        val fromMs: Long? = null,
        val toMs: Long? = null,
        val sourceFile: String? = null,
        val allDates: Boolean = false,
        val maxResults: Int = DEFAULT_MAX_RESULTS,
    )

    /** 命中条目摘要（D2=A：不含 SQL 全文）。 */
    data class Hit(
        val timestamp: Long,
        val sqlPreview: String,
        val source: String,
        val sourceFile: String?,
        val sourceLine: Int,
        val executionTimeMs: Long?,
        val databaseName: String?,
        val mapperStatement: String?,
        val errorText: String?,
        val historyFile: String,
    )

    /** 检索结果：命中摘要 + 计数（totalHits/returned/truncated 供调用方自行组「截断说明」）。 */
    data class Result(
        val totalHits: Int,
        val returned: Int,
        val truncated: Boolean,
        val scannedFiles: Int,
        val scannedEntries: Int,
        val scanTruncated: Boolean,
        /** 本次生效的范围下界（epoch 毫秒）；allDates=true 且未给 fromMs 时为 null。 */
        val scopeStartMs: Long?,
        val hits: List<Hit>,
    )

    /**
     * 文件目录入口：扫目录下全部 .sql（大小写不敏感，文件名序），逐文件流式解析后进入纯逻辑核。
     */
    fun search(dir: String, query: Query, nowMs: Long = System.currentTimeMillis()): Result {
        val directory = File(dir)
        val files = directory
            .listFiles { f -> f.isFile && f.extension.equals("sql", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()
        var scannedEntries = 0
        var scanTruncated = false
        val perFile = files.map { f ->
            if (scannedEntries >= MAX_SCAN_ENTRIES) {
                scanTruncated = true
                f.name to emptyList<LogSqlEntry>()
            } else {
                val entries = SqlLogFileReader.readFromFile(f)
                scannedEntries += entries.size
                if (scannedEntries > MAX_SCAN_ENTRIES) scanTruncated = true
                f.name to entries
            }
        }
        return search(
            perFile, query, nowMs,
            scannedFiles = files.size,
            scannedEntries = scannedEntries,
            scanTruncated = scanTruncated,
        )
    }

    /**
     * 纯逻辑核（域中立测试缝）：已在内存的「落盘文件名 → 条目」清单上执行四维过滤。
     */
    fun search(
        perFile: List<Pair<String, List<LogSqlEntry>>>,
        query: Query,
        nowMs: Long,
        scannedFiles: Int = perFile.size,
        scannedEntries: Int = perFile.sumOf { (_, entries) -> entries.size },
        scanTruncated: Boolean = false,
    ): Result {
        val maxResults = query.maxResults.coerceIn(1, HARD_MAX_RESULTS)
        val keyword = query.keyword?.trim()?.takeIf { it.isNotEmpty() }
        val regex = query.regex?.trim()?.takeIf { it.isNotEmpty() }?.let { pattern ->
            runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrElse { e ->
                throw InvalidQueryException("invalid regex '$pattern': ${e.message}")
            }
        }
        val sourceFileFilter = query.sourceFile?.trim()?.takeIf { it.isNotEmpty() }
        if (query.fromMs != null && query.toMs != null && query.fromMs > query.toMs) {
            throw InvalidQueryException("invalid time range: fromMs (${query.fromMs}) > toMs (${query.toMs})")
        }
        // D1=A：默认范围下界 = 当日本地零点；allDates 或显式更晚的 fromMs 才放宽
        val todayStart = startOfLocalDay(nowMs)
        val scopeFrom = when {
            query.allDates -> query.fromMs
            else -> maxOf(todayStart, query.fromMs ?: Long.MIN_VALUE)
        }

        fun inScope(ts: Long): Boolean =
            (scopeFrom == null || ts >= scopeFrom) && (query.toMs == null || ts <= query.toMs)

        fun textMatches(e: LogSqlEntry): Boolean {
            val keywordHit = keyword == null ||
                e.rawSql.contains(keyword, ignoreCase = true) ||
                e.restoredSql.contains(keyword, ignoreCase = true) ||
                e.mapperStatement?.contains(keyword, ignoreCase = true) == true
            if (!keywordHit) return false
            val regexHit = regex == null ||
                regex.containsMatchIn(e.rawSql) ||
                regex.containsMatchIn(e.restoredSql)
            return regexHit
        }

        val matched = perFile.flatMap { (fileName, entries) ->
            entries.asSequence()
                .filter { inScope(it.timestamp) }
                .filter(::textMatches)
                .filter { sourceFileFilter == null || it.sourceFile?.contains(sourceFileFilter, ignoreCase = true) == true }
                .map { fileName to it }
        }.toList()

        // V14b：输出前自行按时间戳排序（最新在前，稳定序：同毫秒按落盘文件名 + 文件内序号）
        val ordered = matched
            .sortedWith(
                compareByDescending<Pair<String, LogSqlEntry>> { (_, e) -> e.timestamp }
                    .thenBy { (fileName, _) -> fileName }
                    .thenBy { (_, e) -> e.sequenceNumber },
            )

        val hits = ordered.take(maxResults).map { (fileName, e) ->
            Hit(
                timestamp = e.timestamp,
                sqlPreview = preview(e.restoredSql),
                source = e.source.name,
                sourceFile = e.sourceFile,
                sourceLine = e.sourceLine,
                executionTimeMs = e.executionTimeMs,
                databaseName = e.databaseName,
                mapperStatement = e.mapperStatement,
                errorText = e.errorText,
                historyFile = fileName,
            )
        }
        return Result(
            totalHits = ordered.size,
            returned = hits.size,
            truncated = ordered.size > hits.size,
            scannedFiles = scannedFiles,
            scannedEntries = scannedEntries,
            scanTruncated = scanTruncated,
            scopeStartMs = scopeFrom,
            hits = hits,
        )
    }

    /** 当日本地零点（epoch 毫秒）。 */
    private fun startOfLocalDay(nowMs: Long): Long =
        Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** SQL 预览（D2=A）：前 [MAX_PREVIEW_LINES] 行且 ≤ [MAX_PREVIEW_CHARS] 字符，截断带省略号。 */
    private fun preview(sql: String): String {
        val lines = sql.lines()
        val taken = lines.take(MAX_PREVIEW_LINES)
        var out = taken.joinToString("\n")
        if (out.length > MAX_PREVIEW_CHARS) {
            out = out.take(MAX_PREVIEW_CHARS - 1) + "…"
        } else if (lines.size > taken.size) {
            out += "\n…"
        }
        return out
    }
}
