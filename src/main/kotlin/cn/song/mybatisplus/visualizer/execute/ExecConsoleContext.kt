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

    /** B：把控制台当前库对齐到数据源 URL 里的库；返回一行诊断描述（失败也不抛）。 */
    fun alignToDataSourceDatabase(console: Any?): String = runCatching {
        val c = console as? JdbcConsole ?: return@runCatching "console=${console?.javaClass?.name}（非 JdbcConsole，跳过对齐）"
        val ds = c.dataSource
        val target = JdbcUrlDatabase.nameFromUrl(ds?.url)
            ?: return@runCatching "数据源 URL 未指定库（url=${ds?.url}），跳过对齐"
        val current = c.searchPath?.current?.name
        if (current != null && current.equals(target, ignoreCase = true)) {
            return@runCatching "库已一致：$target"
        }
        val path = SearchPath.of(ObjectPath.ROOT.append(target, ObjectKind.DATABASE))
        // switchSchema 带事务/越界校验（字节码实证），首选；未生效则退回纯状态设置
        runCatching { c.switchSchema(path, false) }
        if (!c.searchPath?.current?.name.equals(target, ignoreCase = true)) {
            runCatching { c.setSearchPath(path) }
        }
        val after = c.searchPath?.current?.name
        "库上下文对齐：${current ?: "?"} → $after（数据源=${ds?.name}，URL 库=$target）"
    }.getOrElse { e -> "库上下文对齐跳过（${e.javaClass.simpleName}: ${e.message}）" }

    /** A：错误提示附带的上下文行——「当前库 / 数据源 / 数据源默认库」。 */
    fun describeContext(console: Any?): String? = runCatching {
        val c = console as? JdbcConsole ?: return@runCatching null
        val ds = c.dataSource
        val current = c.searchPath?.current?.name ?: c.searchPath?.displayName ?: "?"
        val urlDb = JdbcUrlDatabase.nameFromUrl(ds?.url) ?: "?"
        "[当前库=$current，数据源=${ds?.name ?: "?"}，数据源默认库=$urlDb]"
    }.getOrNull()
}
