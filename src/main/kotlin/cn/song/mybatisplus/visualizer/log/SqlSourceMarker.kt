package cn.song.mybatisplus.visualizer.log

/**
 * 实时日志源码标记（F-44-6 可靠跳转）：由运行期 [SqlSourceInterceptor]（MyBatis-Plus InnerInterceptor）
 * 在 SQL 执行线程捕获真实调用位置后，向控制台打印一行标记；[SqlLogConsoleBridge] 解析后写入
 * [LogSqlEntry.sourceFile] / [LogSqlEntry.sourceLine]，使实时日志获得与历史面板一致的精确 file:line 跳转。
 *
 * 标记行格式（单行，以 `|` 分隔，前缀固定）：
 * ```
 * SQLVIS-SOURCE|src/main/java/com/example/service/UserService.java|42
 * ```
 * 相对路径基于标准 Maven/Gradle 源码布局（包名 + 源文件名）。该格式亦在
 * `docs/instrumentation/SqlSourceInterceptor.kt` 的拦截器示例中被打印，桥与此示例须保持一致。
 */
object SqlSourceMarker {
    private const val PREFIX = "SQLVIS-SOURCE|"

    /** 生成标记行（供拦截器打印到 stdout）。 */
    fun format(relativePath: String, line: Int): String = "$PREFIX$relativePath|$line"

    private val RE = Regex("""^SQLVIS-SOURCE\|(.+?)\|(\d+)$""")

    /**
     * 解析标记行；非标记行返回 null（桥据此区分 SQL 行与标记行，互不干扰）。
     * 容错：路径为空 / 行号非数字 / 前缀不符均返回 null。
     */
    fun parse(line: String): Pair<String, Int>? {
        val m = RE.matchEntire(line.trim()) ?: return null
        val path = m.groupValues[1]
        if (path.isBlank()) return null
        val ln = m.groupValues[2].toIntOrNull() ?: return null
        return path to ln
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
 * F-44-7：特殊来源标记——MP 分页 COUNT 查询。
 * MP PaginationInnerInterceptor 自动执行的 COUNT 查询不经过本拦截器，无源码标记。
 * 使用特殊标记 "__MP_PAGINATION_COUNT__" 标识，UI 层据此展示友好文案。
 */
object SourceMarkers {
    const val MP_PAGINATION_COUNT = "__MP_PAGINATION_COUNT__"

    /** MP 分页 COUNT 查询的来源标记。 */
    fun mpPaginationCount(): Pair<String, Int> = MP_PAGINATION_COUNT to 0

    /** 是否为 MP 分页 COUNT 查询标记。 */
    fun isMpPaginationCount(sourceFile: String?): Boolean = sourceFile == MP_PAGINATION_COUNT
}
