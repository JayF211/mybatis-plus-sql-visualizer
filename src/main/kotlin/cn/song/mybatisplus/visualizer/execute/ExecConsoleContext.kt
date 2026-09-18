package cn.song.mybatisplus.visualizer.execute

import com.intellij.database.console.JdbcConsole
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath

/**
 * T-ConsoleDbContext：预览控制台的**库上下文**对齐与诊断。
 *
 * 两个职责（用户 20260917 定案 A+B）：
 *  - **B 对齐**：预览会话默认以「数据源 URL 里的库」为准（[alignToDataSourceDatabase]）——
 *    DBN 的「文件→数据源」映射与历史记忆会把 exec 文件钉在别的库上（真机事故：文件被映射到
 *    `mpj_demo@localhost`，而 SQL 属于 `jpa_demo`，导致 `Unknown column 'u.username'`）；
 *  - **A 诊断**：[describeContext] 把「当前库 / 数据源 / 数据源默认库」拼成一行，供错误提示
 *    携带——裸 MySQL 报文（1054 之类）看不出是库选错了。
 *
 * **隔离约定**：本对象直接引用 DBN 类，只允许在 Database 插件链内调用（[DataSourceSelector]
 * 的 attach/reuse 与错误提取点），Community 下永不加载（同 `DbTableSpecAdapter` 惯例）。
 * 全部入口包 runCatching，任何反射/平台异常都退化为「不改动、不附加」，绝不影响主链。
 *
 * API 出处（243 平台 jar 反编译实证，非猜测）：
 *  - `JdbcConsole.getDataSource(): LocalDataSource`、`getSearchPath(): SearchPath`、
 *    `setSearchPath(SearchPath)`、`switchSchema(SearchPath, boolean)`
 *  - `SearchPath.of(ObjectPath)`、`SearchPath.getCurrent()`、`ObjectPath.ROOT`、
 *    `ObjectPath.append(String, ObjectKind)`、`ObjectNamePart.name`（public final 字段）
 */
object ExecConsoleContext {

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ExecConsoleContext::class.java)

    /** T-SI-03 ①：URL 未指定库时的诊断标记（预览头/错误上下文可见，不弹窗）。 */
    const val UNDETERMINED_MARKER = "未能确定默认库"

    /** T-SI-03 ②：强设后读回不一致的诊断标记。 */
    const val READBACK_MISMATCH_MARKER = "库上下文对齐未生效"

    /** B：把控制台当前库对齐到数据源 URL 里的库；返回一行诊断描述（失败也不抛）。 */
    fun alignToDataSourceDatabase(console: Any?): String = runCatching {
        val c = console as? JdbcConsole ?: return@runCatching "console=${console?.javaClass?.name}（非 JdbcConsole，跳过对齐）"
        val ds = c.dataSource
        val current = c.searchPath?.current?.name
        val target = JdbcUrlDatabase.nameFromUrl(ds?.url)
        if (target == null) {
            // T-SI-03 ①：URL 无库不再静默跳过——目标库无从判定，如实标注当前库（诊断可见化，
            // 不阻塞执行）。旧实现直接「跳过对齐」返回，真机「文件映射把会话钉在别的库」时
            // 日志与错误上下文里都看不到任何线索。
            return@runCatching undeterminedDiagnostic(ds?.url, current)
        }
        if (current != null && current.equals(target, ignoreCase = true)) {
            return@runCatching "库已一致：$target"
        }
        val path = SearchPath.of(ObjectPath.ROOT.append(target, ObjectKind.DATABASE))
        // switchSchema 带事务/越界校验（字节码实证），首选；未生效则退回纯状态设置
        runCatching { c.switchSchema(path, false) }
        var strongSet = false
        if (!c.searchPath?.current?.name.equals(target, ignoreCase = true)) {
            runCatching { c.setSearchPath(path) }
            strongSet = true
        }
        val after = c.searchPath?.current?.name
        val namespace = runCatching { c.currentNamespace?.name }.getOrNull()
        // T-SI-03 ②：强设后读回校验——setSearchPath 是纯状态设置（不校验目标库存在性），
        // 读回与目标不一致必须醒目告警并附实际库；v1 只告警不阻断（只读场景不能被对齐失败拦死）。
        val mismatch = readBackMismatch(target, after)
        if (mismatch != null) {
            val diag = mismatchDiagnostic(target, after, namespace, ds?.name, strongSet, mismatch)
            LOG.warn("[T-SI-03] $diag")
            return@runCatching diag
        }
        "库上下文对齐：${current ?: "?"} → ${after ?: "?"}（数据源=${ds?.name}，URL 库=$target）"
    }.getOrElse { e -> "库上下文对齐跳过（${e.javaClass.simpleName}: ${e.message}）" }

    /**
     * T-SI-03 ②：读回校验（纯函数，默认 test 通道可覆盖）——切换/强设之后 searchPath 的当前
     * 节点必须等于目标库，否则返回不一致描述（一致返回 null）。大小写不敏感：MySQL 库名在
     * 不同平台的回读大小写不保证（Linux 服务端按库名大小写敏感，回读可能与 URL 写法不同）。
     *
     * 读回面为 DBN 会话状态（`getSearchPath().getCurrent()`）——243/262 的 JdbcConsole 与
     * DatabaseSession 均无同步语句 API（javap 实证），故 v1 不做服务端 `select current_database()`
     * 实读回，该面列 T-SI-05 真机验证项。
     */
    fun readBackMismatch(target: String?, current: String?): String? {
        if (target.isNullOrBlank()) return null
        if (current.isNullOrBlank()) return "读回为空（searchPath.current 取不到）"
        return if (current.equals(target, ignoreCase = true)) null else "读回不一致：searchPath.current=$current"
    }

    /** T-SI-03 ①：URL 未指定库时的诊断文案（纯函数，默认 test 通道可覆盖）。 */
    fun undeterminedDiagnostic(url: String?, current: String?): String =
        "$UNDETERMINED_MARKER（数据源 URL 未指定库：${url ?: "?"}），当前=${current ?: "?"}"

    /** T-SI-03 ②：读回不一致的诊断文案（纯函数，默认 test 通道可覆盖）。 */
    fun mismatchDiagnostic(
        target: String,
        actual: String?,
        namespace: String?,
        dataSourceName: String?,
        strongSet: Boolean,
        reason: String,
    ): String = "$READBACK_MISMATCH_MARKER（$reason）：目标=$target，实际=${actual ?: "?"}，" +
        "命名空间=${namespace ?: "?"}，数据源=${dataSourceName ?: "?"}（强设=$strongSet）"

    /**
     * A：错误提示附带的上下文行文案（纯函数，默认 test 通道可覆盖）。
     * T-SI-03 ①：[urlDb] 为空（URL 未指定库）时不再给「数据源默认库=?」的哑值，明说未能确定默认库。
     */
    fun contextLine(current: String?, dataSourceName: String?, urlDb: String?): String =
        if (urlDb == null) {
            "[当前库=${current ?: "?"}，数据源=${dataSourceName ?: "?"}，$UNDETERMINED_MARKER（URL 未指定库）]"
        } else {
            "[当前库=${current ?: "?"}，数据源=${dataSourceName ?: "?"}，数据源默认库=$urlDb]"
        }

    /** A：错误提示附带的上下文行——「当前库 / 数据源 / 数据源默认库」。 */
    fun describeContext(console: Any?): String? = runCatching {
        val c = console as? JdbcConsole ?: return@runCatching null
        val ds = c.dataSource
        val current = c.searchPath?.current?.name ?: c.searchPath?.displayName
        contextLine(current, ds?.name, JdbcUrlDatabase.nameFromUrl(ds?.url))
    }.getOrNull()
}
