package cn.song.mybatisplus.visualizer.log.insight

import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.agent.AgentCaptureState
import cn.song.mybatisplus.visualizer.log.agent.AgentEvent
import java.security.MessageDigest

object AgentAnalysisProjection {
    fun observation(event: AgentEvent, entry: LogSqlEntry): SqlExecutionObservation? {
        val runId = event.runId ?: return null
        val executionId = event.executionId ?: return null
        val sequence = event.sequence ?: return null
        val epoch = event.captureEpoch ?: return null
        val nanos = event.startNanos ?: return null
        if (event.timestampSource != "ENTRY_WALL_MILLIS") return null
        return SqlExecutionObservation(
            runId, executionId, sequence, epoch, event.timestamp, nanos, event.durationNanos, event.threadId,
            digest(entry.structureKey), databaseIdentity(event),
            listOfNotNull(event.sourceFile, event.methodKey).joinToString(":").takeIf { it.isNotBlank() }?.let(::digest),
            event.requestTag?.let(::digest),
            when (event.resultStatus) {
                "SUCCESS" -> ExecutionObservationStatus.SUCCESS
                "ERROR" -> ExecutionObservationStatus.ERROR
                else -> ExecutionObservationStatus.UNKNOWN
            },
            requestId = event.requestId,
            parentRequestId = event.parentRequestId,
            connectionId = event.connectionId,
            transactionId = event.transactionId,
        )
    }

    /** Endpoint identity is not a UI badge. Unknown targets use explicitly labelled, run-local buckets. */
    private fun databaseIdentity(event: AgentEvent): String {
        val endpoint = event.url?.let(::canonicalJdbcTarget)
        if (endpoint != null) return "jdbc-target:" + digest(endpoint)
        val run = event.runId.orEmpty()
        val connection = event.connectionId?.takeIf { it.isNotBlank() && it.length <= 1024 }
        return if (connection != null) "unknown-connection:" + digest(identityTuple(run, connection))
        else "unknown-event:" + digest(identityTuple(run, event.executionId.orEmpty()))
    }

    /** Verified single-host network subset only. No DNS, credential retention or process-global parser flags. */
    private fun canonicalJdbcTarget(url: String): String? = try {
        if (url.length > 8192 || url.any { it.isISOControl() } || !url.startsWith("jdbc:", true)) null
        else {
            val uri = java.net.URI(url.substring(5))
            val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
            val defaultPort = when (scheme) { "mysql", "mariadb" -> 3306; "postgresql" -> 5432; else -> null }
            val host = uri.host?.lowercase(java.util.Locale.ROOT)
            val path = uri.rawPath
            val port = if (uri.port == -1) defaultPort else uri.port
            if (defaultPort == null || host.isNullOrBlank() || uri.rawFragment != null || port == null || port !in 1..65535 ||
                path == null || !path.startsWith('/') || path.length < 2 || path.substring(1).contains('/')) null
            else {
                // Preserve path case and quoting; conservatively reject encoded delimiters or ambiguous syntax.
                val database = java.net.URLDecoder.decode(path.substring(1).replace("+", "%2B"), "UTF-8")
                if (!database.matches(Regex("[A-Za-z0-9_.$-]{1,256}"))) null
                else {
                    var schema: String? = null
                    var supported = true
                    val schemaSeen = mutableSetOf<String>()
                    val safeOptions = setOf("user", "password", "ssl", "sslmode", "sslcert", "sslkey", "sslrootcert", "applicationname",
                        "connecttimeout", "sockettimeout", "tcpkeepalive", "useunicode", "characterencoding", "servertimezone",
                        "usessl", "requiressl", "verifyservercertificate", "allowpublickeyretrieval", "rewritebatchedstatements")
                    uri.rawQuery?.split('&')?.forEach { parameter ->
                        val name = java.net.URLDecoder.decode(parameter.substringBefore('='), "UTF-8").lowercase(java.util.Locale.ROOT)
                        when {
                            name == "currentschema" && scheme == "postgresql" -> {
                                val value = java.net.URLDecoder.decode(parameter.substringAfter('=', ""), "UTF-8")
                                if (!schemaSeen.add(name) || !value.matches(Regex("[A-Za-z_][A-Za-z0-9_$]{0,127}"))) supported = false
                                else schema = value
                            }
                            name !in safeOptions -> supported = false // options/sessionVariables may silently change namespace or routing
                        }
                    }
                    if (!supported) null else identityTuple(scheme!!, host, port.toString(), database, schema ?: "<schema-unknown>")
                }
            }
        }
    } catch (_: Exception) { null }

    private fun identityTuple(vararg parts: String): String = parts.joinToString("") { "${it.length}:$it" }

    fun boundary(state: AgentCaptureState): AnalysisCaptureBoundary? {
        if (state.protocolVersion != 2L || state.kind == "STOP") return null
        return AnalysisCaptureBoundary(state.runId?.takeIf { it.isNotBlank() } ?: return null,
            state.captureEpoch ?: return null, state.lastSequence ?: return null)
    }

    fun qualityIssues(state: AgentCaptureState): Set<String> = buildSet {
        val counters = listOf(
            state.queueDropped to "queue_dropped", state.encodingFailed to "encoding_failed",
            state.transportUncertain to "transport_uncertain", state.captureFailed to "capture_failed",
            state.omitted to "omitted_capture", state.sampled to "sampled", state.cleared to "scope_incomplete",
        )
        for ((value, reason) in counters) if (value == null || value > 0) add(reason)
        if (state.protocolVersion != 2L || state.coverage != "INSTRUMENTED_JDBC_ONLY") add("unsupported_capture")
        if (state.requestTracing == true || state.transactionTracing == true) {
            val controlLoss = listOf(state.controlDropped, state.controlCleared, state.controlEncodingFailed,
                state.controlTransportUncertain, state.controlHookFailed)
            if (controlLoss.any { it == null || it > 0 }) add("scope_incomplete")
        }
        // Run-level terminal counts do not establish request lifecycle or complete driver coverage.
        add("scope_incomplete")
    }

    fun shapeFingerprint(sql: String): String = digest(cn.song.mybatisplus.visualizer.log.structureFingerprint(sql))

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
