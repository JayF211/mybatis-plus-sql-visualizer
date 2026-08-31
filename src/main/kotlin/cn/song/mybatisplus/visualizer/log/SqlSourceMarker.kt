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
}
