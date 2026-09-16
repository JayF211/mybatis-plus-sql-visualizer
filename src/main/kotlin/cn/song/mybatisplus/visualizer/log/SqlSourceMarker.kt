package cn.song.mybatisplus.visualizer.log

/**
 * 实时日志源码标记（F-44-6 可靠跳转）：由运行期 [SqlSourceInterceptor]（MyBatis-Plus InnerInterceptor）
 * 在 SQL 执行线程捕获真实调用位置后，向控制台打印一行标记；[SqlLogConsoleBridge] 解析后写入
 * [LogSqlEntry.sourceFile] / [LogSqlEntry.sourceLine]，使实时日志获得与历史面板一致的精确 file:line 跳转。
 *
 * 标记行格式（单行，以 `|` 分隔，前缀固定；M56 T-M56-00 起宽松化支持 3~5 段，缺段为 null）：
 * ```
 * SQLVIS-SOURCE|<相对路径>|<行号>                                          （3 段，旧版拦截器）
 * SQLVIS-SOURCE|<相对路径>|<行号>|<mapper 语句 id>                          （4 段）
 * SQLVIS-SOURCE|<相对路径>|<行号>|<mapper 语句 id>|<请求标签>                （5 段）
 * ```
 * 3 段为既有格式（存量注入拦截器幂等不升级）；第 4 段 mapper 语句 id、第 5 段请求级聚合标签
 * 由新版拦截器模板选择性附加（第 5 段仅在 4 段在场时可辨，缺失段一律 null 而非空串）。
 * 该格式亦在 `docs/instrumentation/SqlSourceInterceptor.kt` 的拦截器示例中被打印，桥与此示例须保持一致。
 */
object SqlSourceMarker {
    private const val PREFIX = "SQLVIS-SOURCE|"
    /** 用户可见「3~5 段」含前缀 token；去掉前缀后的字段段为 2~4。 */
    private const val MIN_SEGMENTS = 2
    private const val MAX_SEGMENTS = 4

    /** 生成标记行（3 段形态，供拦截器打印到 stdout）。 */
    fun format(relativePath: String, line: Int): String = "$PREFIX$relativePath|$line"

    /**
     * 生成标记行（3~5 段形态）：[mapperStatement] / [requestTag] 为 null 时对应段省略；
     * [requestTag] 仅在 [mapperStatement] 非 null 时输出（段位固定，缺失段不可跳位）。
     */
    fun format(relativePath: String, line: Int, mapperStatement: String?, requestTag: String? = null): String =
        buildString {
            append(format(relativePath, line))
            if (mapperStatement != null) {
                append('|').append(mapperStatement)
                if (requestTag != null) append('|').append(requestTag)
            }
        }

    /**
     * 解析标记行；非标记行返回 null（桥据此区分 SQL 行与标记行，互不干扰）。
     * 支持 3~5 段（[SourceMarkerData.mapperStatement] / [SourceMarkerData.requestTag] 缺段 null）。
     * 段位固定不可跳位：第 5 段仅在第 4 段为有效内容时读取；空白段视同缺段。
     * 容错：路径为空 / 行号非数字 / 前缀不符 / 段数不足 3 或超过 5 均返回 null。
     */
    fun parse(line: String): SourceMarkerData? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        val segs = trimmed.removePrefix(PREFIX).split('|')
        if (segs.size < MIN_SEGMENTS || segs.size > MAX_SEGMENTS) return null
        val path = segs[0]
        if (path.isBlank()) return null
        val ln = segs[1].toIntOrNull() ?: return null
        val mapperStatement = segs.getOrNull(2)?.takeIf { it.isNotBlank() }
        val requestTag = if (mapperStatement != null) segs.getOrNull(3)?.takeIf { it.isNotBlank() } else null
        return SourceMarkerData(path, ln, mapperStatement, requestTag)
    }

    /**
     * T-IntrUpg-01：标记路径是否指向拦截器模板文件本身（自位污染形态）。
     *
     * 旧模板 isSelf 用生成期包名常量，用户拷贝/改包后常量与运行类分叉 → 自跳过失效，
     * 拦截器把 locateSource 自身帧上报为调用点。该形态路径文件名恒为模板注入文件名
     * [SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME]（包式/项目根式/裸文件名均可识别，
     * 与 [SourceMarkers.MP_PAGINATION_COUNT] 特殊标记互不干扰——后者不含 .java）。
     * 误伤面 = 用户业务类恰名同名文件（模板文件名常量决定，可忽略级；误伤也只丢位置不丢条目）。
     */
    fun isInterceptorSelfPath(relativePath: String): Boolean =
        EntrySourceMatcher.normalize(relativePath).substringAfterLast('/') ==
            SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME
}

/**
 * 标记行解析结果（M56 T-M56-00）：3 段基础位置 + 可选的第 4/5 段扩展。
 * [mapperStatement] → [LogSqlEntry.mapperStatement]，[requestTag] → [LogSqlEntry.requestTag]。
 */
data class SourceMarkerData(
    /** 相对项目根的源文件路径（正斜杠）。 */
    val file: String,
    /** 源码行号。 */
    val line: Int,
    /** 第 4 段：mapper 语句 id；缺段 null。 */
    val mapperStatement: String? = null,
    /** 第 5 段：请求级聚合标签；缺段 null。 */
    val requestTag: String? = null,
)

/**
 * F-44-7：特殊来源标记——MP 分页 COUNT 查询。
 * MP PaginationInnerInterceptor 自动执行的 COUNT 查询不经过本拦截器，无源码标记。
 * 使用特殊标记 "__MP_PAGINATION_COUNT__" 标识，UI 层据此展示友好文案。
 */
object SourceMarkers {
    const val MP_PAGINATION_COUNT = "__MP_PAGINATION_COUNT__"

    /** MP 分页 COUNT 查询的来源标记。 */
    fun mpPaginationCount(): SourceMarkerData = SourceMarkerData(MP_PAGINATION_COUNT, 0)

    /** 是否为 MP 分页 COUNT 查询标记。 */
    fun isMpPaginationCount(sourceFile: String?): Boolean = sourceFile == MP_PAGINATION_COUNT
}
