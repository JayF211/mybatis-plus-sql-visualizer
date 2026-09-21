// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerSchemaContextTool.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import kotlinx.serialization.json.JsonArray;
import kotlinx.serialization.json.JsonElement;
import kotlinx.serialization.json.JsonNull;
import kotlinx.serialization.json.JsonObject;
import kotlinx.serialization.json.JsonPrimitive;

/**
 * P3 / T-McpArgLooseDecode：入参 JSON 形态标签（契约要求 STRING；NUMBER 仅 maxTables 合法）。
 *
 * <p>移植注记：master 侧为同文件顶层 {@code enum class McpArgKind(val label: String)}；Java 一个文件
 * 只能有一个 public 顶层类型，故按 k2j 既定惯例（顶层声明拆件，见 {@code SqlSource} 先例）独立成件。
 * {@code label} 供校验层拼「期望形态 vs 实际 JSON 形态」的错误文案，取值须与 master 逐字一致
 * （agent 据此自我纠正）。</p>
 */
public enum McpArgKind {
    STRING("string"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    NULL("null"),
    OBJECT("object"),
    ARRAY("array"),
    OTHER("unknown");

    private final String label;

    McpArgKind(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * JSON 值的形态标签（master 侧为同文件 {@code private fun kindOf(element: JsonElement)}）。
     *
     * <p>{@code JsonNull} 是 {@code JsonPrimitive} 子类，**必须先判**；{@link #OTHER} 只由非 JSON
     * 解码器兜底产出，故此处不产出。</p>
     */
    public static McpArgKind of(JsonElement element) {
        if (element instanceof JsonObject) {
            return OBJECT;
        }
        if (element instanceof JsonArray) {
            return ARRAY;
        }
        if (element instanceof JsonNull) {
            return NULL;
        }
        if (element instanceof JsonPrimitive) {
            JsonPrimitive primitive = (JsonPrimitive) element;
            if (primitive.isString()) {
                return STRING;
            }
            String content = primitive.getContent();
            if ("true".equals(content) || "false".equals(content)) {
                return BOOLEAN;
            }
            return NUMBER;
        }
        return OTHER;
    }
}
