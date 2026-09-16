package cn.song.mybatisplus.visualizer.mcp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.sql.SQLWarning

/**
 * warnings 取尽内核（T-NoticeChannel 纯逻辑层）：[surfaces] 每项 = (getAllWarnings 调用,
 * clearWarnings 调用) 二元组，反射接线在 [McpDbExecutor.warningSurfaces]——本核不碰反射
 * 与平台类型，裸 JUnit 可直测。顶层函数（非 object 成员）：测试加载本文件门面类时不必
 * 触发 [McpDbExecutor] 的 Logger 初始化。
 *
 * 全程 runCatching：个别驱动对 warnings 抛 UnsupportedOperationException 时降级为不产出
 * 该面消息，绝不连坐查询结果；clearWarnings 失败同样不丢已取到的消息（下一条语句执行前的
 * pre-drain 兜底，DBX postgres.rs 同款两件套）。
 */
internal fun drainWarnings(vararg surfaces: Pair<() -> List<*>?, () -> Unit>): List<String> =
    surfaces.flatMap { (getAllWarnings, clearWarnings) ->
        val messages = runCatching {
            getAllWarnings()?.mapNotNull { w ->
                runCatching {
                    when (w) {
                        null -> null
                        is SQLWarning -> w.message
                        else -> w.toString()
                    }
                }.getOrNull()
            }
        }.getOrNull().orEmpty()
        runCatching { clearWarnings() }
        messages
    }.filter { it.isNotBlank() }

/**
 * T-McpToolset headless 执行通道（mpsv_explain 专用，反射隔离 DatabaseTools）。
 *
 * 为什么不复用 [cn.song.mybatisplus.visualizer.execute.SqlExecutor]：既有执行链是 UI 控制台
 * 形态（弹数据源选择框 + 异步回调 + 结果进原生控制台），违反 MCP 零弹窗红线且拿不到文本结果。
 * 本通道走 DatabaseConnectionManager 同步建连（243/262 javap 同形实证）：
 * ```
 * DatabaseConnectionManager.getInstance()                    // static
 *   .build(project, localDataSource)                         // LocalDataSource 即 DatabaseConnectionPoint
 *   .setAskPassword(false)                                   // 零弹窗：密码未保存直接失败（fail closed）
 *   .createBlocking()  -> GuardedRef<DatabaseConnection>     // 同步无 UI
 * ref.get().getRemoteConnection()                            // DatabaseConnectionCore 接口方法
 *   .createStatement().executeQuery(sql)  -> RemoteResultSet // DBN remote JDBC 镜像
 * ```
 * 全反射 + Method 一律从声明接口/类获取（T-Embed-10 跨 classloader 教训）。签名由
 * McpDbSignatureCrossVersionTest 对本机 243/253/262 发行版核验。
 *
 * T-NoticeChannel：执行前后经 [warningSurfaces] 取尽语句级与连接级 warnings
 * （getAllWarnings + clearWarnings，DBN 镜像接口），产出挂 RawResult.notices 供工具层渲染。
 *
 * 数据源发现复用 [cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver] 同链：
 * DataSourceManager.getManagers(project) → getDataSources()，按 DasDataSource.getName() 匹配别名。
 */
object McpDbExecutor {

    private val LOG = Logger.getInstance(McpDbExecutor::class.java)

    /** database 插件标记类（探测用，与 DatabaseSchemaResolver 同款）。 */
    private const val MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager"
    private const val DAS_DATA_SOURCE = "com.intellij.database.model.DasDataSource"

    private const val DCM = "com.intellij.database.dataSource.DatabaseConnectionManager"
    private const val CONNECTION_POINT = "com.intellij.database.dataSource.DatabaseConnectionPoint"
    private const val BUILDER = "com.intellij.database.dataSource.DatabaseConnectionManager\$Builder"
    private const val GUARDED_REF = "com.intellij.database.util.GuardedRef"
    private const val CONNECTION_CORE = "com.intellij.database.dataSource.DatabaseConnectionCore"
    private const val CONNECTION = "com.intellij.database.dataSource.DatabaseConnection"
    private const val REMOTE_CONNECTION = "com.intellij.database.remote.jdbc.RemoteConnection"
    private const val REMOTE_STATEMENT = "com.intellij.database.remote.jdbc.RemoteStatement"
    private const val REMOTE_RESULT_SET = "com.intellij.database.remote.jdbc.RemoteResultSet"
    private const val REMOTE_META = "com.intellij.database.remote.jdbc.RemoteResultSetMetaData"

    /** database 插件是否在场（Ultimate/DataGrip）。 */
    fun isDatabaseAvailable(): Boolean = runCatching {
        Class.forName(MANAGER_CLASS)
        true
    }.getOrDefault(false)

    /** 列出全部数据源显示名（alias 未匹配时给 agent 的可选清单）。 */
    fun listDataSourceNames(project: Project): List<String> {
        val dataSources = dataSources(project) ?: return emptyList()
        return dataSources.mapNotNull { ds ->
            runCatching {
                Class.forName(DAS_DATA_SOURCE).getMethod("getName").invoke(ds) as? String
            }.getOrNull()
        }
    }

    /**
     * 按别名找数据源。[alias] 空时：恰好一个数据源 → 用之；0 个/多个 → null（调用方报错并列名）。
     * 别名匹配大小写不敏感；找不到返回 null。
     */
    fun findDataSource(project: Project, alias: String?): Any? {
        val dataSources = dataSources(project) ?: return null
        val named = alias?.trim()?.takeIf { it.isNotEmpty() }
        if (named == null) {
            return dataSources.singleOrNull()
        }
        return dataSources.firstOrNull { ds ->
            val name = runCatching {
                Class.forName(DAS_DATA_SOURCE).getMethod("getName").invoke(ds) as? String
            }.getOrNull()
            name != null && name.equals(named, ignoreCase = true)
        }
    }

    /**
     * 在指定数据源上执行只读查询并采集行列文本（EXPLAIN 用）。
     * 密码未保存/不可达等建连失败以 SQLException 冒泡，适配层按
     * [McpToolErrors.DATASOURCE_UNREACHABLE] 包装为 agent 可读错误（零弹窗 fail closed）。
     */
    fun executeQuery(project: Project, dataSource: Any, sql: String, maxRows: Int): McpExplainFormatter.RawResult {
        val manager = Class.forName(DCM).getMethod("getInstance").invoke(null)
        val builder = Class.forName(DCM).getMethod("build", Project::class.java, Class.forName(CONNECTION_POINT))
            .invoke(manager, project, dataSource)
        Class.forName(BUILDER).getMethod("setAskPassword", Boolean::class.javaPrimitiveType)
            .invoke(builder, false)
        val ref = Class.forName(BUILDER).getMethod("createBlocking").invoke(builder)
        try {
            val connection = Class.forName(GUARDED_REF).getMethod("get").invoke(ref)
            val remoteConnection = findInterfaceMethod(connection, CONNECTION_CORE, CONNECTION, "getRemoteConnection")
                .invoke(connection)
            val statement = Class.forName(REMOTE_CONNECTION).getMethod("createStatement").invoke(remoteConnection)
            try {
                val surfaces = warningSurfaces(remoteConnection, statement)
                // 执行前先 drain 一次（DBX 口径）：DBN 连接可复用，上一条语句/会话遗留的陈旧
                // notices 不允许串扰进本次结果；返回值丢弃，只做清空。
                drainWarnings(*surfaces)
                val resultSet = Class.forName(REMOTE_STATEMENT).getMethod("executeQuery", String::class.java)
                    .invoke(statement, sql)
                val raw = collect(resultSet, maxRows)
                // 执行成功才采集 notices；失败路径直接抛出（notices 无从产生=DBX「出错丢弃」口径）
                val notices = drainWarnings(*surfaces)
                if (notices.isNotEmpty()) LOG.info("McpDbExecutor: drained ${notices.size} driver notice(s)")
                return raw.copy(notices = notices)
            } finally {
                runCatching { Class.forName(REMOTE_STATEMENT).getMethod("close").invoke(statement) }
                    .onFailure { LOG.warn("McpDbExecutor: close statement failed", it) }
            }
        } finally {
            runCatching { Class.forName(GUARDED_REF).getMethod("close").invoke(ref) }
                .onFailure { LOG.warn("McpDbExecutor: close connection ref failed", it) }
        }
    }

    /**
     * warnings 采集面（语句级先、连接级后，执行前 drain 与执行后采集共用同一组 Method 查找）。
     * getAllWarnings/clearWarnings 在 RemoteStatement 与 RemoteConnection 上均有声明
     * （243/262 发行版 javap 实证，McpDbSignatureChecker 登记）——DBN remote JDBC 镜像自带
     * 取尽方法，无需手写 getNextWarning 链式遍历（每次 next 都是一次 RMI 往返）。
     */
    private fun warningSurfaces(
        remoteConnection: Any,
        statement: Any,
    ): Array<Pair<() -> List<*>?, () -> Unit>> = arrayOf(
        {
            Class.forName(REMOTE_STATEMENT).getMethod("getAllWarnings").invoke(statement) as? List<*>
        } to {
            Class.forName(REMOTE_STATEMENT).getMethod("clearWarnings").invoke(statement)
        },
        {
            Class.forName(REMOTE_CONNECTION).getMethod("getAllWarnings").invoke(remoteConnection) as? List<*>
        } to {
            Class.forName(REMOTE_CONNECTION).getMethod("clearWarnings").invoke(remoteConnection)
        },
    )

    /** 采集 ResultSet 行列（读满 [maxRows] 即停，truncated 标记供格式化层提示）。 */
    private fun collect(resultSet: Any, maxRows: Int): McpExplainFormatter.RawResult {
        val rsClass = Class.forName(REMOTE_RESULT_SET)
        val meta = rsClass.getMethod("getMetaData").invoke(resultSet)
        val columnCount = Class.forName(REMOTE_META).getMethod("getColumnCount").invoke(meta) as Int
        val headers = (1..columnCount).map { col ->
            Class.forName(REMOTE_META).getMethod("getColumnLabel", Int::class.javaPrimitiveType)
                .invoke(meta, col) as? String ?: "col$col"
        }
        val nextMethod = rsClass.getMethod("next")
        val getString = rsClass.getMethod("getString", Int::class.javaPrimitiveType)
        val wasNull = rsClass.getMethod("wasNull")
        val rows = mutableListOf<List<String>>()
        var truncated = false
        while ((nextMethod.invoke(resultSet) as Boolean)) {
            if (rows.size >= maxRows) {
                truncated = true
                break
            }
            val row = (1..columnCount).map { col ->
                val value = getString.invoke(resultSet, col) as? String
                if (value == null && (wasNull.invoke(resultSet) as Boolean)) "" else value ?: ""
            }
            rows.add(row)
        }
        runCatching { rsClass.getMethod("close").invoke(resultSet) }
        LOG.info("McpDbExecutor: collected ${rows.size} rows (truncated=$truncated), columns=${headers.size}")
        return McpExplainFormatter.RawResult(headers, rows, truncated)
    }

    /**
     * 方法弹性查找：优先声明接口 [primaryInterface]（T-Embed-10：Method 一律从声明接口拿，
     * 跨插件 classloader 用实现类反射会抛 IllegalAccessException），243/262 声明层差异时
     * 回落 [fallbackInterface]。
     */
    private fun findInterfaceMethod(target: Any, primaryInterface: String, fallbackInterface: String, name: String) =
        runCatching {
            Class.forName(primaryInterface).getMethod(name)
        }.recoverCatching {
            Class.forName(fallbackInterface).getMethod(name)
        }.getOrElse {
            // 两个声明层都没有 → 从实例类拿（照 DatabaseSchemaResolver 的失败即空语义，此处应不可达）
            target.javaClass.getMethod(name)
        }

    /** 数据源清单（反射 DataSourceManager.getManagers(project) → getDataSources()）。 */
    private fun dataSources(project: Project): List<Any>? = runCatching {
        val managerClass = Class.forName(MANAGER_CLASS)
        val managers = managerClass.getMethod("getManagers", Project::class.java)
            .invoke(null, project) as? List<*> ?: return@runCatching emptyList<Any>()
        val out = mutableListOf<Any>()
        for (manager in managers) {
            if (manager == null) continue
            val list = managerClass.getMethod("getDataSources").invoke(manager) as? List<*> ?: continue
            out.addAll(list.filterNotNull())
        }
        out
    }.getOrNull()
}
