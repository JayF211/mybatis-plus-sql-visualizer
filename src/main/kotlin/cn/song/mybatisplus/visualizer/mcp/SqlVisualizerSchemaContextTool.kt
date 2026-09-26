package cn.song.mybatisplus.visualizer.mcp

import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * sqlv_schema_context 工具（T-McpToolset2 / T-MTS2-00：一期方法体逐行迁入，按工具拆 toolset 类）。
 *
 * **P3 / T-McpArgLooseDecode：入参改用 [McpStringArg]/[McpIntArg] 载体而非裸 `String`/`Int`。**
 * 根因（2026-09-20 沙盒实测，栈见 idea.log）：平台参数绑定走
 * `Json.decodeFromJsonElement(serializerOrNull(paramType), element)`（262 `CallableBridge.call:52`），
 * 而 kotlinx 把「JSON 数组/对象 → String」解进 `TaggedDecoder.popTag(Tagged.kt:320)` 对**空 tag 栈**
 * 调 `ArrayList.remove(-1)` → 客户端只拿到 `MCP tool call has been failed: Index -1 out of bounds
 * for length 0`，**本工具方法体根本没进**（同一次调用 idea.log 里 `[MCP-AUDIT]` 行缺失可证）。
 * 载体形态下：`serializer()` 由 companion 提供（`serializerOrNull` 反射命中，无需 serialization
 * 编译器插件），descriptor 仍是 primitive STRING/INT——平台生成的 inputSchema 与改前**逐字节相同**，
 * 但任意 JSON 形态都能解进来，形态记进 [McpArgKind] 由校验层转 `[INVALID_ARGUMENT]`。
 */
open class SqlVisualizerSchemaContextTool : McpToolsetSupport("sqlv_schema_context") {

    @McpTool(name = "sqlv_schema_context", title = "Get compact table/column schema context")
    @McpDescription(
        "Return a compact table+column context (names, types, comments, NOT NULL marks) for the given " +
            "tables, resolved from the real data source configured in the IDE (credentials never leave the IDE). " +
            "Pass a comma-separated 'tables' whitelist (1-50 names) and optionally 'dataSource' display name " +
            "when several data sources exist. 'maxTables' caps how many tables are returned (default 8, hard limit 20). " +
            "Table names are matched inside the data source's own schema/database (taken from its JDBC URL); " +
            "same-named tables of other schemas (e.g. the mysql system database) are never mixed in. " +
            "Deterministic live-schema lookup; pairs with sqlv_render_sql when you need column types " +
            "before writing SQL. Read-only: metadata only, never executes SQL.",
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
    )
    suspend fun sqlv_schema_context(
        tables: McpStringArg,
        dataSource: McpStringArg = McpStringArg.string(""),
        maxTables: McpIntArg = McpIntArg.number(McpSchemaFormatter.DEFAULT_MAX_TABLES),
    ): String = audit("sqlv_schema_context") {
        val project = requireProject()
        requireSmart(project)
        // 形态与取值一律走参数校验（P3）：任一不符都是 [INVALID_ARGUMENT]，绝不落到平台内部异常
        val names = McpSchemaFormatter.parseTableNames(tables)
            ?: throwExpected(McpSchemaFormatter.tableNamesError(tables))
        val alias = McpSchemaFormatter.parseDataSource(dataSource)
            ?: throwExpected(McpSchemaFormatter.dataSourceError(dataSource))
        val limit = McpSchemaFormatter.parseMaxTables(maxTables)
            ?: throwExpected(McpSchemaFormatter.maxTablesError(maxTables))
        val outcome = McpSchemaContextService.buildContext(project, alias, names, limit)
        if (outcome.error != null) throwExpected(outcome.error)
        outcome.text ?: throwExpected(McpToolErrors.code(McpToolErrors.SCHEMA_NOT_AVAILABLE, "empty schema context."))
    }
}

// ------------------------------------------------------------------
// P3 / T-McpArgLooseDecode：MCP 入参容错载体
//
// 为什么放在工具文件而非纯逻辑层（McpSchemaFormatter）：载体带 kotlinx.serialization 依赖，
// 而纯逻辑层（McpToolErrors/McpSchemaFormatter/... 文件头约定）保持零第三方类型、单测免平台 classpath；
// 载体是**本工具的入参契约**，与工具同文件最贴近。
// ------------------------------------------------------------------

/** 入参 JSON 形态标签（契约要求 STRING；NUMBER 仅 maxTables 合法）。 */
enum class McpArgKind(val label: String) {
    STRING("string"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    NULL("null"),
    OBJECT("object"),
    ARRAY("array"),
    OTHER("unknown"),
}

/** 容错入参共同面：[kind] 供校验层判形态，[text] 为原始文本（形态不符时仅用于报错）。 */
interface McpArg {
    val kind: McpArgKind
    val text: String
}

/**
 * 字符串入参载体（tables / dataSource）：**任意 JSON 值都能解进来**，形态记进 [kind]。
 *
 * 平台 `serializerOrNull(McpStringArg::class)` 反射命中 [Companion.serializer]（companion 形态无需
 * serialization 编译器插件，262 实测可用），故 descriptor 的 primitive STRING 形态直接决定
 * inputSchema：与改前的裸 `String` 参数逐字节一致（`{"type":"string"}`，见 262 `Schema_utilKt.parametersSchema`）。
 */
class McpStringArg(override val kind: McpArgKind, override val text: String) : McpArg {
    companion object {
        /** 合规形态（校验通过路径 / 默认值用）。 */
        fun string(text: String): McpStringArg = McpStringArg(McpArgKind.STRING, text)

        /** 平台参数绑定的解析入口（反射查找，勿改名）。 */
        fun serializer(): KSerializer<McpStringArg> = McpStringArgSerializer
    }
}

/** 整型入参载体（maxTables）：形态与数值同时容错，取值非法同样交校验层报 INVALID_ARGUMENT。 */
class McpIntArg(override val kind: McpArgKind, override val text: String) : McpArg {
    companion object {
        fun number(value: Int): McpIntArg = McpIntArg(McpArgKind.NUMBER, value.toString())

        /** 平台参数绑定的解析入口（反射查找，勿改名）。 */
        fun serializer(): KSerializer<McpIntArg> = McpIntArgSerializer
    }
}

/** [McpStringArg] 的宽松解码器：永不抛（类型不符只记 [McpArgKind]）。 */
private object McpStringArgSerializer : KSerializer<McpStringArg> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("cn.song.mybatisplus.visualizer.mcp.McpStringArg", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: McpStringArg) = encoder.encodeString(value.text)

    override fun deserialize(decoder: Decoder): McpStringArg {
        val json = decoder as? JsonDecoder
            ?: return runCatching { McpStringArg(McpArgKind.STRING, decoder.decodeString()) }
                .getOrElse { McpStringArg(McpArgKind.OTHER, "") }
        val element = json.decodeJsonElement()
        return McpStringArg(
            kindOf(element),
            if (element is JsonPrimitive && element.isString) element.content else element.toString(),
        )
    }
}

/** [McpIntArg] 的宽松解码器：永不抛（类型不符只记 [McpArgKind]）。 */
private object McpIntArgSerializer : KSerializer<McpIntArg> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("cn.song.mybatisplus.visualizer.mcp.McpIntArg", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: McpIntArg) = encoder.encodeInt(value.text.toIntOrNull() ?: 0)

    override fun deserialize(decoder: Decoder): McpIntArg {
        val json = decoder as? JsonDecoder
            ?: return runCatching { McpIntArg(McpArgKind.NUMBER, decoder.decodeInt().toString()) }
                .getOrElse { McpIntArg(McpArgKind.OTHER, "") }
        val element = json.decodeJsonElement()
        val kind = kindOf(element)
        // 数值文本原样带出（取值合法性由校验层判：非整数文本 / 越界都报 INVALID_ARGUMENT）
        val text = if (element is JsonPrimitive && kind != McpArgKind.NULL) element.content else ""
        return McpIntArg(kind, text)
    }
}

/** JSON 值的形态标签（`JsonNull` 是 `JsonPrimitive` 子类，必须先判；[McpArgKind.OTHER] 只由非 JSON 解码器兜底产出）。 */
private fun kindOf(element: JsonElement): McpArgKind = when (element) {
    is JsonObject -> McpArgKind.OBJECT
    is JsonArray -> McpArgKind.ARRAY
    is JsonNull -> McpArgKind.NULL
    is JsonPrimitive -> when {
        element.isString -> McpArgKind.STRING
        element.content == "true" || element.content == "false" -> McpArgKind.BOOLEAN
        else -> McpArgKind.NUMBER
    }
}
