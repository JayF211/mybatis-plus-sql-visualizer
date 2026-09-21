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
 * P3 / T-McpArgLooseDecode：整型入参载体（maxTables）——形态与数值同时容错，取值非法同样交校验层
 * 报 {@code INVALID_ARGUMENT}。
 *
 * <p>Java 移植的形态约束与 {@link McpStringArg} 同源（{@code Companion} 静态字段 + 同名嵌套类 +
 * 零参 {@code serializer()}），理由见该类的 KDoc。</p>
 */
public final class McpIntArg implements McpArg {

    /** kotlinx 反射入口载体（字段名与静态性不可改，见 {@link McpStringArg}）。 */
    public static final Companion Companion = new Companion();

    private final McpArgKind kind;
    private final String text;

    public McpIntArg(McpArgKind kind, String text) {
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

        public McpIntArg number(int value) {
            return new McpIntArg(McpArgKind.NUMBER, Integer.toString(value));
        }

        /** 平台参数绑定的解析入口（反射查找，勿改名）。 */
        public KSerializer<McpIntArg> serializer() {
            return new McpIntArgSerializer();
        }
    }

    /** {@link McpIntArg} 的宽松解码器：永不抛（类型不符只记 {@link McpArgKind}）。 */
    private static final class McpIntArgSerializer implements KSerializer<McpIntArg> {

        private static final SerialDescriptor DESCRIPTOR = SerialDescriptorsKt.PrimitiveSerialDescriptor(
            "cn.song.mybatisplus.visualizer.mcp.McpIntArg", PrimitiveKind.INT.INSTANCE
        );

        @Override
        public SerialDescriptor getDescriptor() {
            return DESCRIPTOR;
        }

        @Override
        public void serialize(Encoder encoder, McpIntArg value) {
            // master: value.text.toIntOrNull() ?: 0
            int parsed;
            try {
                parsed = Integer.parseInt(value.getText().trim());
            } catch (NumberFormatException e) {
                parsed = 0;
            }
            encoder.encodeInt(parsed);
        }

        @Override
        public McpIntArg deserialize(Decoder decoder) {
            if (!(decoder instanceof JsonDecoder)) {
                try {
                    return new McpIntArg(McpArgKind.NUMBER, Integer.toString(decoder.decodeInt()));
                } catch (Throwable t) {
                    return new McpIntArg(McpArgKind.OTHER, "");
                }
            }
            JsonElement element = ((JsonDecoder) decoder).decodeJsonElement();
            McpArgKind kind = McpArgKind.of(element);
            // 数值文本原样带出（取值合法性由校验层判：非整数文本 / 越界都报 INVALID_ARGUMENT）
            String text = element instanceof JsonPrimitive && kind != McpArgKind.NULL
                ? ((JsonPrimitive) element).getContent()
                : "";
            return new McpIntArg(kind, text);
        }
    }
}
