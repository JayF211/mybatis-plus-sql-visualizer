package cn.song.mybatisplus.visualizer.log.agent

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Optional cmd=6 control evidence, never a COMPLETE verdict. The sender's successful write is
 * not a receiver acknowledgement. A STOP covers only this run's instrumented JDBC observations;
 * no request, transaction or JVM-exit boundary is claimed. Missing/malformed facts stay unknown.
 * Counters are cumulative across captureEpoch changes, not per IDE analysis generation.
 */
data class AgentCaptureState(
    val protocolVersion: Long?,
    val runId: String?,
    val kind: String?,
    val terminal: Boolean?,
    val captureEpoch: Long?,
    val generated: Long?,
    val lastSequence: Long?,
    val completed: Long?,
    val active: Long?,
    val omitted: Long?,
    val captureFailed: Long?,
    val queueDropped: Long?,
    val cleared: Long?,
    val encodingFailed: Long?,
    val transportUncertain: Long?,
    val written: Long?,
    val sampled: Long?,
    val coverage: String?,
    val capabilities: Set<String>,
    val limitations: Set<String>,
    /** Echo of the last valid CONFIG nonce; absent on legacy peers. Not a run/epoch identity. */
    val captureStateToken: String? = null,
    val controlGenerated: Long? = null,
    val controlDropped: Long? = null,
    val controlCleared: Long? = null,
    val controlEncodingFailed: Long? = null,
    val controlTransportUncertain: Long? = null,
    val controlWritten: Long? = null,
    val controlHookFailed: Long? = null,
    val requestTracing: Boolean? = null,
    val transactionTracing: Boolean? = null,
) {
    companion object {
        const val COMMAND = 6
        /** Add this boolean to existing CONFIG, without changing its disable/privacy fields. */
        const val CONFIG_KEY = "captureState"

        fun parse(body: String): AgentCaptureState? = runCatching {
            val o = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return null
            AgentCaptureState(
                protocolVersion = o.number("protocolVersion"), runId = o.text("runId"),
                kind = o.text("kind"),
                terminal = o.get("terminal")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean,
                captureEpoch = o.number("captureEpoch"), generated = o.number("generated"),
                lastSequence = o.number("lastSequence"), completed = o.number("completed"),
                active = o.number("active"), omitted = o.number("omitted"),
                captureFailed = o.number("captureFailed"), queueDropped = o.number("queueDropped"),
                cleared = o.number("cleared"), encodingFailed = o.number("encodingFailed"),
                transportUncertain = o.number("transportUncertain"), written = o.number("written"),
                sampled = o.number("sampled"), coverage = o.text("coverage"),
                capabilities = o.strings("capabilities"), limitations = o.strings("limitations"),
                captureStateToken = o.text("captureStateToken")?.takeIf { it.matches(Regex("[0-9a-fA-F]{1,64}")) },
                controlGenerated = o.number("controlGenerated"), controlDropped = o.number("controlDropped"),
                controlCleared = o.number("controlCleared"), controlEncodingFailed = o.number("controlEncodingFailed"),
                controlTransportUncertain = o.number("controlTransportUncertain"), controlWritten = o.number("controlWritten"),
                controlHookFailed = o.number("controlHookFailed"), requestTracing = o.flag("requestTracing"),
                transactionTracing = o.flag("transactionTracing"),
            )
        }.getOrNull()

        private fun JsonObject.text(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

        private fun JsonObject.number(key: String): Long? = runCatching {
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asBigDecimal?.longValueExact()?.takeIf { it >= 0 }
        }.getOrNull()

        private fun JsonObject.strings(key: String): Set<String> =
            get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
                it.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }?.asString
            }?.toSet() ?: emptySet()
    }
}
