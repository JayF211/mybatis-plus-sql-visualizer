package cn.song.mybatisplus.visualizer.mcp;

import kotlinx.serialization.KSerializer;
import kotlinx.serialization.descriptors.SerialDescriptor;
import kotlinx.serialization.descriptors.SerialDescriptorsKt;
import kotlinx.serialization.descriptors.PrimitiveKind;
import kotlinx.serialization.encoding.CompositeDecoder;
import kotlinx.serialization.encoding.CompositeEncoder;
import kotlinx.serialization.encoding.Decoder;
import kotlinx.serialization.encoding.Encoder;

/**
 * T-K2JWalkMcp262（KW-262B）：MCP 工具统一文本返回载体——**任意 JSON 值解出 text 字段**。
 *
 * <p><b>为什么必须存在</b>：262 mcpServer 新形态（{@code Schema_utilKt.returnTypeSchema}）要求
 * {@code @McpTool} 方法的返回类型可生成 <b>Properties（class kind）schema</b>——裸
 * {@code kotlin.String} 返回在 262 被拒（{@code IllegalStateException: Properties are expected
 * in return type}），十工具全部 {@code Cannot load} 未注册进平台（2026-10-03 沙盒活体实证 +
 * {@code tools/list} 对账 {@code sqlv_*} 为空）。本类把返回包成单字段对象
 * {@code {"text": "…"}}，客户端读到结构化对象。</p>
 *
 * <p><b>serializer 三件套（与 {@link McpStringArg} 同款手法，勿改形态）</b>：平台
 * {@code serializerOrNull} 反射链={@code getDeclaredField("Companion")} → {@code Field.get(null)}
 * → {@code Companion.serializer()}。descriptor 必须是 <b>class kind</b>（带 text 属性）——
 * primitive kind 会复刻裸 String 的拒因。运行时 kotlinx-serialization 由平台内置提供；
 * 243/251 无 mcpServer 新形态整线不加载，本类纯增量无影响。</p>
 *
 * <p>移植注记：master 侧同期修复（若回流）形态为 {@code @Serializable data class}；Java 侧
 * 无编译器插件，按 McpStringArg 先例手写 KSerializer。</p>
 */
public final class McpToolTextResult {

    /** 工具全文输出（原 String 返回语义逐字节迁入）。 */
    private final String text;

    public McpToolTextResult(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    /** Kotlin {@code companion object} 的 Java 等价形态（平台反射入口，勿改名）。 */
    public static final Companion Companion = new Companion();

    public static final class Companion {

        Companion() {
        }

        /** 平台返回类型反射的解析入口（勿改名）。 */
        public KSerializer<McpToolTextResult> serializer() {
            return new McpToolTextResultSerializer();
        }
    }

    /** class kind 序列器：单 text 属性（Properties 形态是 262 返回类型反射的硬要求）。 */
    private static final class McpToolTextResultSerializer implements KSerializer<McpToolTextResult> {

        private static final SerialDescriptor DESCRIPTOR = SerialDescriptorsKt.buildClassSerialDescriptor(
            "cn.song.mybatisplus.visualizer.mcp.McpToolTextResult",
            new SerialDescriptor[0],
            builder -> {
                builder.element(
                    "text",
                    SerialDescriptorsKt.PrimitiveSerialDescriptor("text", PrimitiveKind.STRING.INSTANCE),
                    java.util.Collections.emptyList(),
                    false
                );
                return kotlin.Unit.INSTANCE;
            }
        );

        private static final int TEXT_INDEX = 0;

        McpToolTextResultSerializer() {
        }

        @Override
        public SerialDescriptor getDescriptor() {
            return DESCRIPTOR;
        }

        @Override
        public void serialize(Encoder encoder, McpToolTextResult value) {
            CompositeEncoder out = encoder.beginStructure(DESCRIPTOR);
            out.encodeStringElement(DESCRIPTOR, TEXT_INDEX, value.text);
            out.endStructure(DESCRIPTOR);
        }

        @Override
        public McpToolTextResult deserialize(Decoder decoder) {
            CompositeDecoder in = decoder.beginStructure(DESCRIPTOR);
            String text = "";
            try {
                while (true) {
                    int index = in.decodeElementIndex(DESCRIPTOR);
                    if (index == CompositeDecoder.DECODE_DONE) {
                        break;
                    }
                    if (index == TEXT_INDEX) {
                        text = in.decodeStringElement(DESCRIPTOR, TEXT_INDEX);
                    }
                    // 未知索引（平台版本追加字段）按宽容语义跳过：交由 decodeElementIndex 驱动推进
                }
            } finally {
                in.endStructure(DESCRIPTOR);
            }
            return new McpToolTextResult(text);
        }
    }
}
