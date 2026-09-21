// T-SyncK2JMaster115 自 master src/test/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSchemaContextArgsTest.kt 移植
package cn.song.mybatisplus.visualizer.mcp

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializerOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.typeOf

/**
 * P3 / T-McpArgLooseDecode：sqlv_schema_context 入参形态容错与校验（纯逻辑层，无需平台）。
 *
 * **背景（根因回归护栏）**：`tables` 传 JSON 数组时，平台参数绑定
 * `Json.decodeFromJsonElement(serializerOrNull(String::class), element)` 会走进
 * `TaggedDecoder.popTag` 对空 tag 栈 `ArrayList.remove(-1)`，客户端只拿到
 * `MCP tool call has been failed: Index -1 out of bounds for length 0`（工具方法体根本没进）。
 * 本用例集两条线钉住修复：
 *  1. **解码线**：载体 serializer 对 string/array/object/number/boolean/null 六种形态都不抛
 *     （改前数组/对象/null 三种会炸）；
 *  2. **校验线**：四类问题输入（数组形态 / 空串 / 纯分隔符 / 超 50 个表名）一律
 *     `[INVALID_ARGUMENT]`，且文案里不含内部异常字样；
 *  3. **契约线**：载体 descriptor 仍是 primitive STRING/INT——平台 inputSchema 与改前逐字节一致。
 *
 * **T-SyncK2J 互操作注记（本轮 kotlinc 2.3.20 实证）**：k2j 侧载体是 Java 类，其 `Companion` 是
 * 「静态字段 + 同名嵌套类」而**非** Kotlin 伴生对象——Kotlin 不认它，`McpStringArg.serializer()`
 * 与 `McpStringArg.Companion.serializer()` 两种写法都报 `unresolved reference`；可用的形态是先把
 * 静态字段读进局部 val 再调其方法（见下方两个私有 val）。断言与 master 逐条一致，仅调用形态适配。
 */
class McpSchemaContextArgsTest {

    /** k2j 侧 `McpStringArg.Companion` 静态字段（kotlinx 反射入口载体，见生产类 KDoc）。 */
    private val stringArgCompanion = McpStringArg.Companion

    /** k2j 侧 `McpIntArg.Companion` 静态字段（同上）。 */
    private val intArgCompanion = McpIntArg.Companion

    private fun decodeString(json: String): McpStringArg =
        Json.decodeFromJsonElement(stringArgCompanion.serializer(), Json.parseToJsonElement(json))

    private fun decodeInt(json: String): McpIntArg =
        Json.decodeFromJsonElement(intArgCompanion.serializer(), Json.parseToJsonElement(json))

    private fun assertInvalidArgument(error: String?) {
        assertTrue("应为结构化参数错误: $error", error != null && error.startsWith("[${McpToolErrors.INVALID_ARGUMENT}]"))
        assertFalse("不得是内部异常文本: $error", error!!.contains("Index -1"))
        assertFalse("不得是内部异常文本: $error", error.contains("IndexOutOfBounds"))
        assertFalse("不得是内部异常文本: $error", error.contains("Exception"))
    }

    // ------------------------------------------------------------------
    // 解码线：平台绑定层不再抛内部异常
    // ------------------------------------------------------------------

    @Test
    fun `解码_数组形态不抛异常且形态可判`() {
        // 改前该输入在平台绑定层直接抛 Index -1 out of bounds for length 0
        val arg = decodeString("[\"user\"]")
        assertEquals(McpArgKind.ARRAY, arg.kind)
    }

    @Test
    fun `解码_对象与null形态不抛异常`() {
        assertEquals(McpArgKind.OBJECT, decodeString("{\"tables\":[\"user\"]}").kind)
        assertEquals(McpArgKind.NULL, decodeString("null").kind)
    }

    @Test
    fun `解码_标量形态标签正确`() {
        assertEquals(McpArgKind.STRING, decodeString("\"user\"").kind)
        assertEquals("user", decodeString("\"user\"").text)
        assertEquals(McpArgKind.NUMBER, decodeString("12").kind)
        assertEquals(McpArgKind.BOOLEAN, decodeString("true").kind)
    }

    @Test
    fun `解码_整型载体对数组形态不抛异常`() {
        val arg = decodeInt("[]")
        assertEquals(McpArgKind.ARRAY, arg.kind)
        assertNull("数组形态无取值", McpSchemaFormatter.parseMaxTables(arg))
    }

    // ------------------------------------------------------------------
    // 校验线：四类问题输入 → INVALID_ARGUMENT
    // ------------------------------------------------------------------

    @Test
    fun `校验_数组形态归INVALID_ARGUMENT`() {
        val arg = decodeString("[\"user\"]")
        assertNull("数组形态不得通过", McpSchemaFormatter.parseTableNames(arg))
        assertInvalidArgument(McpSchemaFormatter.tableNamesError(arg))
    }

    @Test
    fun `校验_空串归INVALID_ARGUMENT`() {
        val arg = decodeString("\"\"")
        assertEquals(McpArgKind.STRING, arg.kind)
        assertNull(McpSchemaFormatter.parseTableNames(arg))
        assertEquals(
            "[${McpToolErrors.INVALID_ARGUMENT}] tables must contain 1..${McpSchemaFormatter.MAX_TABLE_NAMES} table names (comma-separated).",
            McpSchemaFormatter.tableNamesError(arg),
        )
    }

    @Test
    fun `校验_纯分隔符归INVALID_ARGUMENT`() {
        val arg = decodeString("\",,;;\\n\\n\"")
        assertNull(McpSchemaFormatter.parseTableNames(arg))
        assertInvalidArgument(McpSchemaFormatter.tableNamesError(arg))
    }

    @Test
    fun `校验_超50个表名归INVALID_ARGUMENT`() {
        val over = (1..McpSchemaFormatter.MAX_TABLE_NAMES + 1).joinToString(",") { "t$it" }
        val arg = decodeString("\"$over\"")
        assertNull(McpSchemaFormatter.parseTableNames(arg))
        assertInvalidArgument(McpSchemaFormatter.tableNamesError(arg))
    }

    @Test
    fun `校验_合法清单通过且保序去重`() {
        val arg = decodeString("\" user , role ; USER \"")
        assertEquals(listOf("user", "role"), McpSchemaFormatter.parseTableNames(arg))
    }

    // ------------------------------------------------------------------
    // 同族参数：dataSource / maxTables 的形态与取值
    // ------------------------------------------------------------------

    @Test
    fun `校验_dataSource形态不符归INVALID_ARGUMENT`() {
        val arg = decodeString("[\"mp_demo@localhost\"]")
        assertNull(McpSchemaFormatter.parseDataSource(arg))
        assertInvalidArgument(McpSchemaFormatter.dataSourceError(arg))
        // 空串是合法取值（= 未指定）
        assertEquals("", McpSchemaFormatter.parseDataSource(decodeString("\"\"")))
    }

    @Test
    fun `校验_maxTables形态与取值`() {
        // k2j 侧 parseMaxTables 返回 Java Integer（master 侧为 Int?），期望值装箱以避开 Int/Integer 重载二义
        assertEquals(Integer.valueOf(8), McpSchemaFormatter.parseMaxTables(decodeInt("8")))
        // 数值字符串照旧接受（平台绑定对字符串→Int 本就有宽松转换，改前可用）
        assertEquals(Integer.valueOf(8), McpSchemaFormatter.parseMaxTables(decodeInt("\"8\"")))
        assertEquals(Integer.valueOf(8), McpSchemaFormatter.parseMaxTables(intArgCompanion.number(8)))
        val array = decodeInt("[]")
        assertNull(McpSchemaFormatter.parseMaxTables(array))
        assertInvalidArgument(McpSchemaFormatter.maxTablesError(array))
        val fraction = decodeInt("2.5")
        assertNull("非整数文本应拒", McpSchemaFormatter.parseMaxTables(fraction))
        assertInvalidArgument(McpSchemaFormatter.maxTablesError(fraction))
    }

    // ------------------------------------------------------------------
    // 契约线：inputSchema 形态不变
    // ------------------------------------------------------------------

    @Test
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun `契约_载体描述符仍是primitive形态`() {
        // 平台 inputSchema 由 descriptor 生成（262 Schema_utilKt.parametersSchema）：primitive STRING
        // → {"type":"string"}、primitive INT → {"type":"integer"}，与改前裸 String/Int 参数一致。
        assertEquals(PrimitiveKind.STRING, stringArgCompanion.serializer().descriptor.kind)
        assertEquals(PrimitiveKind.INT, intArgCompanion.serializer().descriptor.kind)
    }

    @Test
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun `契约_平台能按KType解析到载体序列化器`() {
        // 复核补护栏（20260920）：平台生成 inputSchema 的链是 parametersSchema →
        // SerializationSteps.analyzeTypeUsingKotlinxSerialization → `serializerOrNull(KType)`，
        // 而 kotlinx 侧对无泛型类是**反射查 Companion**（PlatformKt.invokeSerializerOnDefaultCompanion：
        // getDeclaredField("Companion") + 伴生对象上的 `serializer()`）。上一条只断言
        // `McpStringArg.serializer()` 能被直接调用，覆盖不到「平台能否按 KType 找到它」——
        // 载体若被改成内部类/去掉伴生对象，上一条仍绿而生产 inputSchema 生成期会炸。
        assertNotNull(
            "平台须能按 KType 解析到载体序列化器（否则 inputSchema 生成期失败）",
            serializerOrNull(typeOf<McpStringArg>()),
        )
        assertNotNull("同族参数载体同理", serializerOrNull(typeOf<McpIntArg>()))
    }
}
