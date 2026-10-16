package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement
import java.util.UUID

/** REAL platform Remote* interfaces adapted to a real local H2 fixture, not guessed mirror stubs. */
class RemoteSqlComparisonBridgeTest {
    private val loader = javaClass.classLoader
    private fun type(name: String) = Class.forName("com.intellij.database.remote.jdbc.$name", false, loader)
    private fun wrap(api: String, delegate: Any, readOnly: Boolean): Any = Proxy.newProxyInstance(loader, arrayOf(type(api))) { _, method, args ->
        if (api == "RemoteConnection" && method.name == "isReadOnly") readOnly
        else {
            val jdbc = when (api) { "RemoteConnection" -> Connection::class.java; "RemoteStatement" -> Statement::class.java
                "RemoteResultSet" -> ResultSet::class.java; else -> ResultSetMetaData::class.java }
            val value = try { jdbc.getMethod(method.name, *method.parameterTypes).invoke(delegate, *(args ?: emptyArray())) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
            when (value) {
                is Statement -> wrap("RemoteStatement", value, readOnly)
                is ResultSet -> wrap("RemoteResultSet", value, readOnly)
                is ResultSetMetaData -> wrap("RemoteResultSetMetaData", value, readOnly)
                else -> value
            }
        }
    }
    @Test fun `remote interface preserves SQL null empty and exact numeric`() = fixture { connection ->
        // H2 ignores setReadOnly; only the facade claim is injected. This proves Remote API and
        // typed result transport, NOT H2 read-only enforcement.
        RemoteSqlComparisonBridge(wrap("RemoteConnection", connection, true), loader) {}.use { bridge ->
            val result = bridge.query("SELECT CAST(NULL AS VARCHAR(10)) AS n, CAST('' AS VARCHAR(10)) AS e, CAST(123456789.123 AS DECIMAL(12,3)) AS d",
                "fixture", 10, 2, ComparisonCancellation()) {}
            assertEquals(SqlResultValue.Null, result.rows.single()[0])
            assertEquals(SqlResultValue.Text(""), result.rows.single()[1])
            assertEquals("123456789.123", (result.rows.single()[2] as SqlResultValue.Number).value.toPlainString())
        }
    }
    @Test fun `writable or active connection is refused without executing SQL`() = fixture { connection ->
        try { RemoteSqlComparisonBridge(wrap("RemoteConnection", connection, false), loader) {}; fail() }
        catch (expected: ComparisonRejected) { assertEquals("read_only_connection_required", expected.message) }
        connection.autoCommit = false
        try { RemoteSqlComparisonBridge(wrap("RemoteConnection", connection, true), loader) {}; fail() }
        catch (expected: ComparisonRejected) { assertEquals("active_transaction_not_allowed", expected.message) }
    }
    @Test fun `remote row cap detects extra row and preserves repeated values`() = fixture { connection ->
        RemoteSqlComparisonBridge(wrap("RemoteConnection", connection, true), loader) {}.use { bridge ->
            val result = bridge.query("SELECT 1 AS x FROM SYSTEM_RANGE(1,3)", "fixture", 2, 2, ComparisonCancellation()) {}
            assertTrue(result.truncated); assertEquals(2, result.rows.size); assertEquals(result.rows[0], result.rows[1])
        }
    }
    @Test fun `pre-cancelled read refuses execution`() = fixture { connection ->
        val cancellation = ComparisonCancellation()
        val bridge = RemoteSqlComparisonBridge(wrap("RemoteConnection", connection, true), loader) {}
        cancellation.cancel()
        try { bridge.query("SELECT 1", "fixture", 2, 2, cancellation) { cancellation.check() }; fail() }
        catch (_: java.util.concurrent.CancellationException) { }
        bridge.close()
    }
    @Test fun `unbounded remote text is rejected rather than allocated`() = fixture { connection ->
        RemoteSqlComparisonBridge(wrap("RemoteConnection", connection, true), loader) {}.use { bridge ->
            try { bridge.query("SELECT CAST('x' AS VARCHAR) AS x", "fixture", 2, 2, ComparisonCancellation()) {}; fail() }
            catch (expected: ComparisonRejected) { assertEquals("remote_text_bound_unknown", expected.message) }
        }
    }
    @Test fun `fatal open error skips cleanup and preserves unwrapped identity`() {
        val fatal = OutOfMemoryError("fixture fatal")
        var releases = 0
        try {
            RemoteSqlComparisonBridge.releaseAfterOpenFailure(InvocationTargetException(InvocationTargetException(fatal))) { releases++ }
        } catch (actual: OutOfMemoryError) { assertSame(fatal, actual) }
        assertEquals(0, releases)
    }
    @Test fun `ordinary cleanup failure is suppressed without masking open failure`() {
        val original = java.sql.SQLException("open failed")
        val close = java.sql.SQLException("close failed")
        var releases = 0
        try {
            RemoteSqlComparisonBridge.releaseAfterOpenFailure(InvocationTargetException(original)) { releases++; throw close }
        } catch (actual: java.sql.SQLException) {
            assertSame(original, actual); assertArrayEquals(arrayOf(close), actual.suppressed)
        }
        assertEquals(1, releases)
    }
    @Test fun `cleanup fatal is not downgraded to ordinary open failure`() {
        val fatal = LinkageError("cleanup fatal")
        try {
            RemoteSqlComparisonBridge.releaseAfterOpenFailure(java.sql.SQLException("open failed")) { throw InvocationTargetException(fatal) }
        } catch (actual: LinkageError) { assertSame(fatal, actual) }
    }

    private fun fixture(block: (Connection) -> Unit) {
        Class.forName("org.h2.Driver")
        DriverManager.getConnection("jdbc:h2:mem:remote_compare_${UUID.randomUUID()}").use(block)
    }
}
