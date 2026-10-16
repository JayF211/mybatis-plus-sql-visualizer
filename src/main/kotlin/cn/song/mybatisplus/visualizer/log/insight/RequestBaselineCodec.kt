package cn.song.mybatisplus.visualizer.log.insight

import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** Strict bounded JSON interchange. No file/network side effects; caller owns explicit save/load UI. */
object RequestBaselineCodec {
    const val MAX_BYTES = 256 * 1024
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val knownIssues = setOf("invalid_scope_event", "scope_identity_missing", "duration_overflow", "scope_not_closed",
        "cross_thread_coverage_unknown", "capture_coverage_unknown", "terminal_watermark_missing", "terminal_count_mismatch",
        "loss_not_zero_or_unknown", "children_not_closed", "event_after_terminal_watermark", "unknown_result",
        "truncated_event", "capture_quality_incomplete")
    sealed interface DecodeResult {
        data class Ready(val baseline: RequestBaseline) : DecodeResult
        data class Rejected(val reason: String) : DecodeResult
    }
    sealed interface EncodeResult {
        data class Ready(val json: String) : EncodeResult
        data class Rejected(val reason: String) : EncodeResult
    }

    fun encode(baseline: RequestBaseline): EncodeResult {
        val failure = invalid(baseline)
        if (failure != null) return EncodeResult.Rejected(failure)
        val json = Gson().toJson(baseline)
        return if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) EncodeResult.Rejected("byte_budget") else EncodeResult.Ready(json)
    }

    fun decode(json: String, isCancelled: () -> Boolean = { false }): DecodeResult {
        if (json.length > MAX_BYTES || json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return DecodeResult.Rejected("byte_budget")
        return try {
            JsonReader(StringReader(json)).use { reader ->
                reader.isLenient = false
                var tokens = 0
                fun check() {
                    require(!isCancelled()) { "cancelled" }
                    require(++tokens <= 20_000) { "token_budget" }
                }
                fun text(): String {
                    check(); require(reader.peek() == JsonToken.STRING) { "string_required" }
                    return reader.nextString()
                }
                fun number(): Long {
                    check(); require(reader.peek() == JsonToken.NUMBER) { "number_required" }
                    val raw = reader.nextString()
                    require(Regex("0|[1-9][0-9]*").matches(raw)) { "integer_required" }
                    return raw.toLongOrNull() ?: throw IllegalArgumentException("integer_overflow")
                }
                fun intValue(): Int = number().also { require(it <= Int.MAX_VALUE) { "integer_overflow" } }.toInt()
                val required = setOf("schemaVersion", "environmentLabel", "contextDigest", "sourceRunDigest", "sourceRequestDigest",
                    "shapeCounts", "executions", "cumulativeNanos", "errors", "coverageIssues", "complete")
                val seen = mutableSetOf<String>()
                var version = 0; var environment = ""; var context = ""; var run = ""; var request = ""
                val shapes = linkedMapOf<String, Int>(); val issues = linkedSetOf<String>()
                var executions = 0; var nanos = 0L; var errors = 0; var complete = false
                check(); require(reader.peek() == JsonToken.BEGIN_OBJECT) { "object_required" }; reader.beginObject()
                while (reader.hasNext()) {
                    check()
                    val name = reader.nextName()
                    require(name in required && seen.add(name)) { "unknown_or_duplicate_field" }
                    when (name) {
                        "schemaVersion" -> version = intValue()
                        "environmentLabel" -> environment = text()
                        "contextDigest" -> context = text()
                        "sourceRunDigest" -> run = text()
                        "sourceRequestDigest" -> request = text()
                        "executions" -> executions = intValue()
                        "cumulativeNanos" -> nanos = number()
                        "errors" -> errors = intValue()
                        "complete" -> { check(); require(reader.peek() == JsonToken.BOOLEAN) { "boolean_required" }; complete = reader.nextBoolean() }
                        "shapeCounts" -> {
                            require(reader.peek() == JsonToken.BEGIN_OBJECT) { "shapes_object_required" }; reader.beginObject()
                            while (reader.hasNext()) {
                                check(); require(shapes.size < 1000) { "shape_budget" }
                                val shape = reader.nextName()
                                require(!shapes.containsKey(shape) && digestPattern.matches(shape)) { "invalid_or_duplicate_shape" }
                                shapes[shape] = intValue()
                            }
                            reader.endObject()
                        }
                        "coverageIssues" -> {
                            require(reader.peek() == JsonToken.BEGIN_ARRAY) { "issues_array_required" }; reader.beginArray()
                            while (reader.hasNext()) {
                                check(); require(issues.size < 128) { "issue_budget" }
                                require(issues.add(text())) { "duplicate_issue" }
                            }
                            reader.endArray()
                        }
                    }
                }
                reader.endObject()
                require(reader.peek() == JsonToken.END_DOCUMENT && seen == required) { "missing_fields_or_trailing_data" }
                val baseline = RequestBaseline(version, environment, context, run, request, shapes, executions, nanos, errors, issues, complete)
                val failure = invalid(baseline)
                if (failure != null) DecodeResult.Rejected(failure) else DecodeResult.Ready(baseline)
            }
        } catch (_: Exception) { DecodeResult.Rejected(if (isCancelled()) "cancelled" else "invalid_baseline_json") }
    }

    private fun invalid(value: RequestBaseline): String? = when {
        value.schemaVersion != 1 -> "unsupported_schema_version"
        !RequestBaselines.safeLabel(value.environmentLabel) -> "unsafe_environment_label"
        listOf(value.contextDigest, value.sourceRunDigest, value.sourceRequestDigest).any { !digestPattern.matches(it) } -> "invalid_identity_digest"
        value.shapeCounts.size > 1000 || value.shapeCounts.any { !digestPattern.matches(it.key) || it.value !in 1..10_000 } -> "invalid_shape_counts"
        value.executions !in 0..10_000 || value.cumulativeNanos < 0 || value.errors !in 0..value.executions -> "invalid_metrics"
        value.shapeCounts.values.sumOf { it.toLong() } != value.executions.toLong() -> "shape_count_mismatch"
        value.coverageIssues.size > 128 || value.coverageIssues.any { it !in knownIssues } -> "unknown_quality_reason"
        value.complete && value.coverageIssues.isNotEmpty() -> "contradictory_complete_flag"
        !value.complete && value.coverageIssues.isEmpty() -> "missing_incomplete_reason"
        else -> null
    }
}
