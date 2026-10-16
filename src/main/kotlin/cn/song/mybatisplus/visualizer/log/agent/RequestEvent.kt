package cn.song.mybatisplus.visualizer.log.agent

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Control sequence is independent of SQL sequence. Missing nonce/quality means unknown coverage. */
data class TraceEventEnvelope(
    val runId: String?, val captureStateToken: String?, val captureEpoch: Long?,
    val sequence: Long?, val threadId: Long?, val timestamp: Long?,
)

/** Servlet-thread observation only, never evidence for the entire HTTP request's completeness. */
data class RequestEvent(
    val envelope: TraceEventEnvelope,
    val kind: String?, val requestId: String?, val parentRequestId: String?,
    val startNanos: Long?, val durationNanos: Long?, val status: String?,
    val coverage: String?, val errorClass: String?, val asyncStarted: Boolean?,
    val generatedSql: Long?, val completedSql: Long?, val lastSqlSequence: Long?,
    val observedSqlFailures: Long?, val sqlCaptureLost: Long?,
) {
    companion object {
        const val COMMAND = 7
        const val CONFIG_KEY = "requestTracing"
        fun parse(body: String): RequestEvent? = TraceJson.parse(body) { o ->
            RequestEvent(TraceJson.envelope(o), o.text("kind"), o.text("requestId"),
                o.text("parentRequestId"), o.number("startNanos"), o.number("durationNanos"),
                o.text("status"), o.text("coverage"), o.text("errorClass"), o.flag("asyncStarted"),
                o.number("generatedSql"), o.number("completedSql"), o.number("lastSqlSequence"),
                o.number("observedSqlFailures"), o.number("sqlCaptureLost"))
        }
    }
}

internal object TraceJson {
    fun <T> parse(body: String, read: (JsonObject) -> T): T? = runCatching {
        JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject?.let(read)
    }.getOrNull()
    fun envelope(o: JsonObject) = TraceEventEnvelope(o.text("runId"), o.text("captureStateToken"),
        o.number("captureEpoch"), o.number("sequence"), o.number("threadId"), o.number("ts"))
}
internal fun JsonObject.text(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
internal fun JsonObject.number(key: String): Long? = runCatching {
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asBigDecimal?.longValueExact()
}.getOrNull()
internal fun JsonObject.flag(key: String): Boolean? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
