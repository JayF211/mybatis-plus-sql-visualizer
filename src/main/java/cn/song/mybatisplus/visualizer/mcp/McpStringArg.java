// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerSchemaContextTool.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import kotlinx.serialization.KSerializer;
import kotlinx.serialization.descriptors.PrimitiveKind;
import kotlinx.serialization.descriptors.SerialDescriptor;
import kotlinx.serialization.descriptors.SerialDescriptorsKt;
import kotlinx.serialization.encoding.Decoder;
import kotlinx.serialization.encoding.Encoder;
import kotlinx.serialization.json.JsonDecoder;
import kotlinx.serialization.json.JsonElement;
import kotlinx.serialization.json.JsonPrimitive;

/**
 * P3 / T-McpArgLooseDecode：字符串入参载体（tables / dataSource）——**任意 JSON 值都能解进来**，
 * 形态记进 {@link #getKind()}。
 *
 * <p>平台 {@code serializerOrNull(McpStringArg::class)} 反射命中 {@code Companion.serializer()}
 * （companion 形态无需 serialization 编译器插件，262 实测可用），故 descriptor 的 primitive STRING
 * 形态直接决定 inputSchema：与改前的裸 {@code String} 参数逐字节一致（{@code {"type":"string"}}，
 * 见 262 {@code Schema_utilKt.parametersSchema}）。</p>
 *
 * <p><b>Java 移植的关键形态约束（本轮 javap 实证，勿改）</b>：kotlinx
 * {@code PlatformKt.invokeSerializerOnDefaultCompanion} 的反射链是
 * {@code clazz.getDeclaredField("Companion")} → {@code setAccessible(true)} → {@code Field.get(null)}
 * （**静态字段**）→ {@code companion.getClass().getDeclaredMethod("serializer")} →
 * {@code Method.invoke(companion)}。因此本类必须同时具备：① 名为 {@code Companion} 的
 * {@code public static final} 字段；② 同名嵌套类 {@code Companion}；③ 该类上的零参
 * {@code serializer()} 方法。三者任一缺失，平台取不到 serializer，入参绑定即回落到
 * master 修复前的崩溃形态。</p>
 *
 * <p>移植注记：master 侧为 {@code class McpStringArg(override val kind, override val text)} +
 * {@code companion object} + 同文件 {@code private object McpStringArgSerializer}；Java 侧
 * companion 显式写成静态字段 + 嵌套类（见上），serializer 下沉为嵌套私有类。</p>
 */
public final class McpStringArg implements McpArg {

    /**
     * kotlinx 反射入口载体：{@code getDeclaredField("Companion").get(null)} 读的就是本字段。
     * **字段名与静态性均不可改**（改则平台静默取不到 serializer）。
     */
    public static final Companion Companion = new Companion();

    private final McpArgKind kind;
    private final String text;

    public McpStringArg(McpArgKind kind, String text) {
        this.kind = kind;
        this.text = text;
    }

    @Override
    public McpArgKind getKind() {
        return kind;
    }

    @Override
    public String getText() {
        return text;
    }

    /** Kotlin {@code companion object} 的 Java 等价形态。 */
    public static final class Companion {

        Companion() {
        }

        /** 合规形态（校验通过路径 / 默认值用）。 */
        public McpStringArg string(String text) {
            return new McpStringArg(McpArgKind.STRING, text);
        }

        /** 平台参数绑定的解析入口（反射查找，勿改名）。 */
        public KSerializer<McpStringArg> serializer() {
            return new McpStringArgSerializer();
        }
    }

    /** {@link McpStringArg} 的宽松解码器：永不抛（类型不符只记 {@link McpArgKind}）。 */
    private static final class McpStringArgSerializer implements KSerializer<McpStringArg> {

        private static final SerialDescriptor DESCRIPTOR = SerialDescriptorsKt.PrimitiveSerialDescriptor(
            "cn.song.mybatisplus.visualizer.mcp.McpStringArg", PrimitiveKind.STRING.INSTANCE
        );

        @Override
        public SerialDescriptor getDescriptor() {
            return DESCRIPTOR;
        }

        @Override
        public void serialize(Encoder encoder, McpStringArg value) {
            encoder.encodeString(value.getText());
        }

        @Override
        public McpStringArg deserialize(Decoder decoder) {
            if (!(decoder instanceof JsonDecoder)) {
                // master: runCatching { McpStringArg(STRING, decoder.decodeString()) }.getOrElse { OTHER, "" }
                try {
                    return new McpStringArg(McpArgKind.STRING, decoder.decodeString());
                } catch (Throwable t) {
                    return new McpStringArg(McpArgKind.OTHER, "");
                }
            }
            JsonElement element = ((JsonDecoder) decoder).decodeJsonElement();
            return new McpStringArg(
                McpArgKind.of(element),
                element instanceof JsonPrimitive && ((JsonPrimitive) element).isString()
                    ? ((JsonPrimitive) element).getContent()
                    : element.toString()
            );
        }
    }
}
