// T-SyncK2JMaster482 波D2 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ExplainJsonPlanTree.kt
// （KOTLIN_TO_JAVA_RULES §2：Kotlin object 仅静态成员 → Java 全 static 承载类；嵌套 data class → 嵌套 record）
package cn.song.mybatisplus.visualizer.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R33：PG/KingBase JSON 计划树 → 可折叠树模型的**纯逻辑构建器**（无 IDE/UI 类型，裸 JUnit 可直测）。
 *
 * <p>输入形态（{@code cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor} T-ECH-03 同款真机实证）：
 * PG {@code EXPLAIN (FORMAT JSON, VERBOSE)} 返回单列 {@code QUERY PLAN}，值为数组
 * {@code [{ "Plan": { "Node Type": …, "Relation Name": …, "Plan Rows": …, "Plans": [ …子计划 ] } }]}。</p>
 *
 * <p>折叠规则：</p>
 * <ul>
 *  <li>带 {@code "Node Type"} 的对象 = 计划节点，标签 {@code Node Type on Relation (rows=N)}——关系名缺省退
 *    {@code "Alias"}（PG 对无表节点不出 Relation Name），两段各自缺省按段省略（不虚标）；</li>
 *  <li>行数 {@code "Plan Rows"} 缺省退 {@code "Actual Rows"}（EXPLAIN ANALYZE 才出现，容忍读取不改变口径）；</li>
 *  <li>标签键（Node Type/Relation Name/Alias/Plan Rows/Actual Rows）收进标签，不重复出现在子节点；</li>
 *  <li>单键且值为对象/数组的包装对象（顶层 {@code {"Plan": …}} 正是此形态）**塌缩**为其值节点——
 *    用户看到的根就是 {@code Limit}/{@code Seq Scan}，不是一层无信息量的 {@code Plan} 包装；</li>
 *  <li>标量键值入树为 {@code key: value} 叶节点；对象/数组键以键名为节点标签继续下钻；</li>
 *  <li>**诚实降级**：单元格解析不出 JSON（2000 字符单元格预算截断是 T-ECH-03 实测最常见成因）→
 *    原文降级为文本叶节点，不猜不炸。</li>
 * </ul>
 */
public final class ExplainJsonPlanTree {

    /** 可展示树节点（纯数据；UI 层据此建 Swing TreeModel）。 */
    public record Node(
            /** 节点标签（计划标签 / 键名 / {@code key: value} 叶文本）。 */
            @NotNull String label,
            /** 子节点（叶节点为空表）。 */
            @NotNull List<Node> children) {

        /** Kotlin 默认参数 {@code children = emptyList()} 的等价便捷构造。 */
        public Node(@NotNull String label) {
            this(label, List.of());
        }
    }

    /** 计划标签键（收进标签，不重复出现在子节点）。 */
    private static final Set<String> LABEL_KEYS = Set.of("Node Type", "Relation Name", "Alias", "Plan Rows", "Actual Rows");

    private ExplainJsonPlanTree() {
    }

    /**
     * 构建树根清单。
     *
     * @return 每个可解析单元格贡献一组根、每个解析失败单元格贡献一个文本叶节点；
     *   全部单元格空白 → null（调用方按空态处理，不建空树）
     */
    @Nullable
    public static List<Node> build(@NotNull List<List<String>> rows) {
        List<Node> out = new ArrayList<>();
        boolean sawAny = false;
        for (List<String> row : rows) {
            for (String cell : row) {
                String text = cell.trim();
                if (text.isEmpty()) continue;
                sawAny = true;
                JsonElement parsed;
                try {
                    parsed = JsonParser.parseString(text);
                } catch (Throwable t) {
                    parsed = null;
                }
                if (parsed != null && (parsed.isJsonObject() || parsed.isJsonArray())) {
                    out.addAll(fromElement(parsed));
                } else {
                    out.add(new Node(text));
                }
            }
        }
        return sawAny ? List.copyOf(out) : null;
    }

    private static List<Node> fromElement(JsonElement el) {
        if (el.isJsonArray()) {
            List<Node> acc = new ArrayList<>();
            for (JsonElement item : el.getAsJsonArray()) acc.addAll(fromElement(item));
            return acc;
        }
        if (el.isJsonObject()) {
            return List.of(nodeFromObject(el.getAsJsonObject()));
        }
        return List.of(new Node(el.getAsString()));
    }

    private static Node nodeFromObject(JsonObject obj) {
        String label = planLabel(obj);
        if (label != null) {
            List<Node> children = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                String key = e.getKey();
                JsonElement value = e.getValue();
                if (LABEL_KEYS.contains(key)) continue;
                // 「Plans」类子计划数组：元素节点直接摊平进父节点——计划树用户关心的是
                // 子计划节点本身，中间再垫一层 "Plans" 键包装只会多一次展开（真机 PG 形态）
                if (value.isJsonArray() && value.getAsJsonArray().size() > 0 && allObjects(value)) {
                    children.addAll(fromElement(value));
                } else {
                    children.add(entryNode(key, value));
                }
            }
            return new Node(label, children);
        }
        // 单键包装对象（顶层 {"Plan": …} 正是此形态）塌缩为其值节点
        if (obj.size() == 1) {
            Map.Entry<String, JsonElement> entry = obj.entrySet().iterator().next();
            JsonElement value = entry.getValue();
            if (value.isJsonObject() || value.isJsonArray()) {
                List<Node> inner = fromElement(value);
                return inner.size() == 1 ? inner.get(0) : new Node(entry.getKey(), inner);
            }
            return new Node(entry.getKey() + ": " + primitiveText(value));
        }
        List<Node> children = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            children.add(entryNode(e.getKey(), e.getValue()));
        }
        return new Node("{plan}", children);
    }

    private static Node entryNode(String key, JsonElement value) {
        if (value.isJsonObject() || value.isJsonArray()) {
            return new Node(key, fromElement(value));
        }
        return new Node(key + ": " + primitiveText(value));
    }

    /**
     * 计划节点标签（null = 非计划对象）：{@code Node Type on Relation (rows=N)}，两段各自缺省省略——
     * 关系名缺省退 Alias（不虚报表名，ExplainIndexAdvisor 同款口径）；行数 Plan Rows 缺省退 Actual Rows。
     */
    @Nullable
    private static String planLabel(JsonObject obj) {
        String nodeType = stringOf(obj, "Node Type");
        if (nodeType == null) return null;
        String relation = stringOf(obj, "Relation Name");
        if (relation == null) relation = stringOf(obj, "Alias");
        Long rows = longOf(obj, "Plan Rows");
        if (rows == null) rows = longOf(obj, "Actual Rows");
        StringBuilder sb = new StringBuilder();
        sb.append(nodeType);
        if (relation != null) sb.append(" on ").append(relation);
        if (rows != null) sb.append(" (rows=").append(rows).append(')');
        return sb.toString();
    }

    @Nullable
    private static String stringOf(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return null;
        try {
            return el.getAsString();
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static Long longOf(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return null;
        try {
            return el.getAsLong();
        } catch (Throwable t) {
            try {
                return Long.parseLong(el.getAsString().trim());
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    private static String primitiveText(JsonElement el) {
        try {
            return el.getAsString();
        } catch (Throwable t) {
            return el.toString();
        }
    }

    /** {@code value.asJsonArray.all { it.isJsonObject }} 等价。 */
    private static boolean allObjects(JsonElement arrayEl) {
        for (JsonElement item : arrayEl.getAsJsonArray()) {
            if (!item.isJsonObject()) return false;
        }
        return true;
    }
}
