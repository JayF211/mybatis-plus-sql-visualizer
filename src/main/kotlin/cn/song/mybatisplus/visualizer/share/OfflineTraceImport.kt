package cn.song.mybatisplus.visualizer.share

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.net.URI
import java.nio.charset.StandardCharsets

/** OTLP JSON ExportTraceServiceRequest (v1): a bounded projection, never a complete trace claim. */
data class ImportedDatabaseSpan(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String?,
    val service: String?,
    val operation: String?,
    val databaseSystem: String?,
    val startUnixNanos: Long,
    val durationNanos: Long,
    val status: ImportedSpanStatus,
)

enum class ImportedSpanStatus { OK, ERROR, UNSET }

data class OfflineTraceSnapshot(
    val spans: List<ImportedDatabaseSpan>,
    val skippedSpans: Int,
    val sourceFormat: String = "otlp-json-trace-v1",
    val completeness: String = "unknown_external_coverage",
)

object OfflineTraceImport {
    const val MAX_BYTES = 1024 * 1024
    const val MAX_SPANS = 2000
    private val TRACE_ID = Regex("[0-9a-fA-F]{32}")
    private val SPAN_ID = Regex("[0-9a-fA-F]{16}")
    private const val MAX_DEPTH = 32
    private const val MAX_TOKENS = 100_000

    sealed interface Result {
        data class Accepted(val snapshot: OfflineTraceSnapshot) : Result
        data class Rejected(val reason: String) : Result
    }

    fun parse(json: String): Result {
        if (json.length > MAX_BYTES || json.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) {
            return Result.Rejected("file_budget")
        }
        if (!validateStructure(json)) return Result.Rejected("invalid_or_oversized_json")
        val root = try {
            JsonParser.parseString(json) as? JsonObject
        } catch (_: RuntimeException) {
            null
        } ?: return Result.Rejected("object_required")
        val resources = root.get("resourceSpans")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return Result.Rejected("resource_spans_required")
        val spans = mutableListOf<ImportedDatabaseSpan>()
        var skipped = 0
        var seen = 0
        val identities = HashSet<Pair<String, String>>()
        for (resource in resources) {
            val resourceObject = resource.asObject() ?: return Result.Rejected("invalid_resource")
            val service = attributes(resourceObject.get("resource")?.asObject())["service.name"]
            val scopes = resourceObject.get("scopeSpans")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return Result.Rejected("scope_spans_required")
            for (scope in scopes) {
                val entries = scope.asObject()?.get("spans")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: return Result.Rejected("spans_required")
                for (entry in entries) {
                    if (++seen > MAX_SPANS) return Result.Rejected("span_budget")
                    val span = entry.asObject() ?: return Result.Rejected("invalid_span")
                    val attrs = attributes(span)
                    val databaseSystem = attrs["db.system.name"] ?: attrs["db.system"]
                    if (databaseSystem == null) {
                        skipped++
                        continue
                    }
                    val traceId = span.string("traceId")?.lowercase()
                    val spanId = span.string("spanId")?.lowercase()
                    val parent = span.string("parentSpanId")?.takeIf { it.isNotEmpty() }?.lowercase()
                    if (traceId == null || !validTraceId(traceId) || spanId == null || !validSpanId(spanId) ||
                        (parent != null && !validSpanId(parent))) return Result.Rejected("invalid_span_identity")
                    if (!identities.add(traceId to spanId)) return Result.Rejected("duplicate_span_identity")
                    val start = span.string("startTimeUnixNano")?.toLongOrNull()
                    val end = span.string("endTimeUnixNano")?.toLongOrNull()
                    if (start == null || end == null || start < 0 || end < start) return Result.Rejected("invalid_span_time")
                    val code = span.get("status")?.asObject()?.string("code")
                    val status = when (code) {
                        "1", "STATUS_CODE_OK" -> ImportedSpanStatus.OK
                        "2", "STATUS_CODE_ERROR" -> ImportedSpanStatus.ERROR
                        null, "0", "STATUS_CODE_UNSET" -> ImportedSpanStatus.UNSET
                        else -> return Result.Rejected("invalid_span_status")
                    }
                    // Arbitrary names, queries, parameters, peer URLs and error messages are never retained.
                    spans += ImportedDatabaseSpan(traceId, spanId, parent,
                        service?.let { opaqueServiceId(it) }, safeOperation(attrs["db.operation.name"] ?: attrs["db.operation"]),
                        safeDatabaseSystem(databaseSystem), start, end - start, status)
                }
            }
        }
        return Result.Accepted(OfflineTraceSnapshot(spans.toList(), skipped))
    }

    fun traceLink(template: String, traceId: String): URI? {
        if (!validTraceId(traceId) || template.length > 2048 || template.split("{traceId}").size != 2) return null
        val uri: URI
        val pattern: URI
        try {
            uri = URI(template.replace("{traceId}", traceId.lowercase(java.util.Locale.ROOT)))
            pattern = URI(template.replace("{traceId}", "trace"))
        } catch (_: Exception) { return null }
        return uri.takeIf {
            it.scheme == "https" && !it.host.isNullOrBlank() && it.rawUserInfo == null &&
                it.port in listOf(-1, 443) && it.host == pattern.host
        }
    }

    private fun validTraceId(value: String): Boolean = TRACE_ID.matches(value) && value.any { it != '0' }
    private fun validSpanId(value: String): Boolean = SPAN_ID.matches(value) && value.any { it != '0' }
    private fun opaqueServiceId(value: String): String = "service_" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
    private fun safeDatabaseSystem(value: String): String? = value.lowercase(java.util.Locale.ROOT).takeIf {
        it in setOf("mysql", "postgresql", "mariadb", "oracle", "mssql", "microsoft.sql_server", "h2", "sqlite", "db2", "other_sql")
    }
    private fun safeOperation(value: String?): String? = value?.uppercase(java.util.Locale.ROOT)?.takeIf {
        it in setOf("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "EXECUTE", "CALL", "COMMIT", "ROLLBACK")
    }
    private fun JsonElement.asObject(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun attributes(owner: JsonObject?): Map<String, String> {
        val array = owner?.get("attributes")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyMap()
        val allowed = setOf("service.name", "db.system", "db.system.name", "db.operation", "db.operation.name")
        val result = LinkedHashMap<String, String>()
        for (entry in array) {
            val attribute = entry.asObject() ?: continue
            val key = attribute.string("key")?.takeIf { it in allowed } ?: continue
            val value = attribute.get("value")?.asObject()?.string("stringValue") ?: continue
            if (value.length <= 128) result[key] = value
        }
        return result
    }

    private fun validateStructure(json: String): Boolean = try {
        JsonReader(StringReader(json)).use { reader ->
            reader.isLenient = false
            var tokens = 0
            fun visit(depth: Int): Boolean {
                if (depth > MAX_DEPTH || ++tokens > MAX_TOKENS) return false
                when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject()
                        val names = HashSet<String>()
                        while (reader.hasNext()) {
                            if (!names.add(reader.nextName()) || !visit(depth + 1)) return false
                        }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        reader.beginArray()
                        while (reader.hasNext()) if (!visit(depth + 1)) return false
                        reader.endArray()
                    }
                    JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> reader.nextNull()
                    else -> return false
                }
                return true
            }
            visit(0) && reader.peek() == JsonToken.END_DOCUMENT
        }
    } catch (_: Exception) {
        false
    }
}
