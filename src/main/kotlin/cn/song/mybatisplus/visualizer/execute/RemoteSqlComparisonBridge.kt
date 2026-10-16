package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.FatalErrors
import com.intellij.openapi.project.Project
import java.io.StringReader
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Types

/** Separate from EXPLAIN's text collector: SQL NULL and decimal types never pass through RawResult.
 * Reflection uses declaring interfaces, not package-private implementation classes.
 */
internal class RemoteSqlComparisonBridge(
    private val connection: Any,
    private val loader: ClassLoader,
    private val release: () -> Unit,
) : ComparisonQuerySession {
    private val connectionClass = type("RemoteConnection")
    private val statementClass = type("RemoteStatement")
    private val resultClass = type("RemoteResultSet")
    private val metadataClass = type("RemoteResultSetMetaData")

    init {
        // Never switch read-only mode on a shared/live connection: it could change another console's
        // transaction. The selected datasource must already supply a driver-reported read-only handle.
        if (invoke(connectionClass, connection, "isReadOnly") != true) throw ComparisonRejected("read_only_connection_required")
        if (invoke(connectionClass, connection, "getAutoCommit") != true) throw ComparisonRejected("active_transaction_not_allowed")
    }

    override fun query(sql: String, contextKey: String, maxRows: Int, timeoutSeconds: Int,
                       cancellation: ComparisonCancellation, validate: () -> Unit): SqlResultSnapshot {
        validate()
        if (invoke(connectionClass, connection, "isReadOnly") != true ||
            invoke(connectionClass, connection, "getAutoCommit") != true) throw ComparisonRejected("connection_state_changed")
        val statement = invoke(connectionClass, connection, "createStatement") ?: throw ComparisonRejected("statement_unavailable")
        try {
            cancellation.attach { cleanup { invoke(statementClass, statement, "cancel") } }
            invoke(statementClass, statement, "setQueryTimeout", timeoutSeconds)
            invoke(statementClass, statement, "setMaxRows", maxRows + 1)
            validate()
            val result = invoke(statementClass, statement, "executeQuery", sql) ?: throw ComparisonRejected("result_unavailable")
            try {
                return collect(result, contextKey, maxRows) { cancellation.check(); validate() }
            } finally { cleanup { invoke(resultClass, result, "close") } }
        } finally {
            cancellation.attach(null)
            cleanup { invoke(statementClass, statement, "close") }
        }
    }

    internal fun collect(result: Any, contextKey: String, maxRows: Int, check: () -> Unit): SqlResultSnapshot {
        val metadata = invoke(resultClass, result, "getMetaData") ?: throw ComparisonRejected("metadata_unavailable")
        val jdbcMetadata = Proxy.newProxyInstance(ResultSetMetaData::class.java.classLoader,
            arrayOf(ResultSetMetaData::class.java)) { _, method, args ->
            invoke(metadataClass, metadata, method.name, *(args ?: emptyArray()))
        } as ResultSetMetaData
        val jdbcResult = Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java)) { _, method, args ->
            when (method.name) {
                "getMetaData" -> jdbcMetadata
                "getCharacterStream" -> {
                    val column = args!![0] as Int
                    val kind = jdbcMetadata.getColumnType(column)
                    val precision = invoke(metadataClass, metadata, "getPrecision", column) as Int
                    // RemoteResultSet has no character-stream API. Do not fetch an unbounded cell
                    // and then pretend the in-memory collector's budget constrained its allocation.
                    if (kind in setOf(Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB)
                        || precision !in 1..SqlResultComparator.MAX_TEXT_CHARS) throw ComparisonRejected("remote_text_bound_unknown")
                    val value = invoke(resultClass, result, "getString", column) as String?
                    val isNull = invoke(resultClass, result, "wasNull") as Boolean
                    if (isNull) null else StringReader(value ?: throw ComparisonRejected("null_contract_mismatch"))
                }
                "getBigDecimal", "getString" -> {
                    val value = invoke(resultClass, result, method.name, *(args ?: emptyArray()))
                    if (invoke(resultClass, result, "wasNull") == true) null else value
                }
                else -> invoke(resultClass, result, method.name, *(args ?: emptyArray()))
            }
        } as ResultSet
        return SqlResultCollector.collect(jdbcResult, contextKey, ordered = false, maxRows = maxRows, checkCancelled = check)
    }
    override fun close() = release()
    private fun type(simple: String) = Class.forName("com.intellij.database.remote.jdbc.$simple", false, loader)

    companion object {
        fun open(project: Project, dataSource: Any, validate: () -> Unit): RemoteSqlComparisonBridge {
            validate()
            val loader = RemoteSqlComparisonBridge::class.java.classLoader
            fun type(name: String) = Class.forName(name, false, loader)
            val managerType = type("com.intellij.database.dataSource.DatabaseConnectionManager")
            val builderType = type("com.intellij.database.dataSource.DatabaseConnectionManager\$Builder")
            val refType = type("com.intellij.database.util.GuardedRef")
            val manager = managerType.getMethod("getInstance").invoke(null)
            val builder = managerType.getMethod("build", Project::class.java,
                type("com.intellij.database.dataSource.DatabaseConnectionPoint")).invoke(manager, project, dataSource)
            invoke(builderType, builder, "setAskPassword", false)
            validate()
            val ref = invoke(builderType, builder, "createBlocking") ?: throw ComparisonRejected("connection_unavailable")
            try {
                validate()
                val core = invoke(refType, ref, "get") ?: throw ComparisonRejected("connection_unavailable")
                val remote = invoke(type("com.intellij.database.dataSource.DatabaseConnectionCore"), core, "getRemoteConnection")
                    ?: throw ComparisonRejected("remote_unavailable")
                return RemoteSqlComparisonBridge(remote, loader) { cleanup { invoke(refType, ref, "close") } }
            } catch (failure: Throwable) {
                cleanup { invoke(refType, ref, "close") }; throw failure
            }
        }
        internal fun invoke(type: Class<*>, receiver: Any, name: String, vararg args: Any?): Any? {
            val types = args.map { when (it) { is Int -> Int::class.javaPrimitiveType!!; is Boolean -> Boolean::class.javaPrimitiveType!!; else -> requireNotNull(it).javaClass } }
            try { return type.getMethod(name, *types.toTypedArray()).invoke(receiver, *args) }
            catch (wrapped: InvocationTargetException) { throw wrapped.targetException }
        }
        internal fun cleanup(action: () -> Unit) {
            try { action() } catch (failure: Throwable) {
                val cause = (failure as? InvocationTargetException)?.targetException ?: failure
                if (FatalErrors.isFatal(cause)) throw cause
            }
        }
    }
}
