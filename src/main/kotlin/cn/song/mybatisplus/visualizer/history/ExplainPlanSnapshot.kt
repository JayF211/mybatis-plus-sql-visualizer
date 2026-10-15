package cn.song.mybatisplus.visualizer.history

import cn.song.mybatisplus.visualizer.log.structureFingerprint
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.util.xmlb.XmlSerializer
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.UUID

/** R90：只存值，不持有 PSI/Swing/JDBC、连接串、数据源显示名或凭据。旧 XML 缺字段即空。 */
data class ExplainPlanSnapshot(
    var id: String = "",
    var capturedAt: Long = 0,
    var projectIdentity: String = "",
    var dataSourceUuid: String = "",
    var dialect: String = "",
    var format: String = "",
    var templateFingerprint: String = "",
    var exactFingerprint: String = "",
    var payload: String = "",
) {
    fun key(): ExplainPlanKey = ExplainPlanKey(
        projectIdentity, dataSourceUuid, dialect, format, templateFingerprint, exactFingerprint,
    )

    /** JSON 仅编码列头、行及采集截断标记；不持久化驱动 notices（可能含连接诊断）。 */
    fun raw(): McpExplainFormatter.RawResult? = runCatching {
        val json = JsonParser.parseString(payload).asJsonObject
        McpExplainFormatter.RawResult(
            json.getAsJsonArray("headers").map { it.asString },
            json.getAsJsonArray("rows").map { row -> row.asJsonArray.map { it.asString } },
            json.get("truncated").asBoolean,
        )
    }.getOrNull()

    /** 按真实 XML 序列化 UTF-8 字节计费，包括标签与 XML 转义；不是仅算 SQL/JSON 字符数。 */
    fun serializedBytes(): Int = org.jdom.output.XMLOutputter()
        .outputString(XmlSerializer.serialize(this)).toByteArray(Charsets.UTF_8).size

    companion object {
        fun create(key: ExplainPlanKey, raw: McpExplainFormatter.RawResult): ExplainPlanSnapshot =
            ExplainPlanSnapshot(
                UUID.randomUUID().toString(), System.currentTimeMillis(), key.projectIdentity,
                key.dataSourceUuid, key.dialect, key.format, key.templateFingerprint, key.exactFingerprint,
                Gson().toJson(mapOf("headers" to raw.headers, "rows" to raw.rows, "truncated" to raw.truncated)),
            )
    }
}

/** 模板键供后续消费者检索；比较必须同时命中 exact，不能把不同参数冒充同一 SQL。 */
data class ExplainPlanKey(
    val projectIdentity: String,
    val dataSourceUuid: String,
    val dialect: String,
    val format: String,
    val templateFingerprint: String,
    val exactFingerprint: String,
) {
    val persistable: Boolean get() = projectIdentity.isNotBlank() &&
        UUID_PATTERN.matches(dataSourceUuid) && dialect.isNotBlank() &&
        format in setOf(ExplainOutputFormat.TABLE.name, ExplainOutputFormat.JSON.name) &&
        templateFingerprint.isNotBlank() && exactFingerprint.isNotBlank()

    companion object {
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        fun projectIdentity(basePath: String?): String = basePath?.takeIf { it.isNotBlank() }?.let {
            hash(Paths.get(it).toAbsolutePath().normalize().toString().replace('\\', '/'))
        }.orEmpty()

        fun create(projectIdentity: String, uuid: String?, dialect: String, format: ExplainOutputFormat, sql: String) =
            ExplainPlanKey(projectIdentity, uuid.orEmpty().lowercase(java.util.Locale.ROOT), dialect, format.name,
                hash(structureFingerprint(sql)), hash(sql))

        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** 仅本次提交持有；清空/重新加载会使 generation 失效，条目删除后也绝不复活。 */
data class ExplainPlanCapture(val entryId: String, val generation: Long, val key: ExplainPlanKey)

enum class ExplainPlanSaveResult { SAVED, SESSION_ONLY, TOO_LARGE, CLEARED_OR_REMOVED }
