package cn.song.mybatisplus.visualizer.ui

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * R33：PG/KingBase JSON 计划树 → 可折叠树模型的**纯逻辑构建器**（无 IDE/UI 类型，裸 JUnit 可直测）。
 *
 * 输入形态（[cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor] T-ECH-03 同款真机实证）：
 * PG `EXPLAIN (FORMAT JSON, VERBOSE)` 返回单列 `QUERY PLAN`，值为数组
 * `[{ "Plan": { "Node Type": …, "Relation Name": …, "Plan Rows": …, "Plans": [ …子计划 ] } }]`。
 *
 * 折叠规则：
 *  - 带 `"Node Type"` 的对象 = 计划节点，标签 `Node Type on Relation (rows=N)`——关系名缺省退
 *    `"Alias"`（PG 对无表节点不出 Relation Name），两段各自缺省按段省略（不虚标）；
 *  - 行数 `"Plan Rows"` 缺省退 `"Actual Rows"`（EXPLAIN ANALYZE 才出现，容忍读取不改变口径）；
 *  - 标签键（Node Type/Relation Name/Alias/Plan Rows/Actual Rows）收进标签，不重复出现在子节点；
 *  - 单键且值为对象/数组的包装对象（顶层 `{"Plan": …}` 正是此形态）**塌缩**为其值节点——
 *    用户看到的根就是 `Limit`/`Seq Scan`，不是一层无信息量的 `Plan` 包装；
 *  - 标量键值入树为 `key: value` 叶节点；对象/数组键以键名为节点标签继续下钻；
 *  - **诚实降级**：单元格解析不出 JSON（2000 字符单元格预算截断是 T-ECH-03 实测最常见成因）→
 *    原文降级为文本叶节点，不猜不炸。
 */
object ExplainJsonPlanTree {

    /** 可展示树节点（纯数据；UI 层据此建 Swing TreeModel）。 */
    data class Node(val label: String, val children: List<Node> = emptyList())

    /** 计划标签键（收进标签，不重复出现在子节点）。 */
    private val LABEL_KEYS = setOf("Node Type", "Relation Name", "Alias", "Plan Rows", "Actual Rows")

    /**
     * 构建树根清单。
     *
     * @return 每个可解析单元格贡献一组根、每个解析失败单元格贡献一个文本叶节点；
     *   全部单元格空白 → null（调用方按空态处理，不建空树）
     */
    fun build(rows: List<List<String>>): List<Node>? {
        val out = mutableListOf<Node>()
        var sawAny = false
        for (row in rows) {
            for (cell in row) {
                val text = cell.trim()
                if (text.isEmpty()) continue
                sawAny = true
                val parsed = runCatching { JsonParser.parseString(text) }.getOrNull()
                if (parsed != null && (parsed.isJsonObject || parsed.isJsonArray)) {
                    out += fromElement(parsed)
                } else {
                    out += Node(text)
                }
            }
        }
        return if (sawAny) out.toList() else null
    }

    private fun fromElement(el: JsonElement): List<Node> = when {
        el.isJsonArray -> el.asJsonArray.flatMap { fromElement(it) }
        el.isJsonObject -> listOf(nodeFromObject(el.asJsonObject))
        else -> listOf(Node(el.asString))
    }

    private fun nodeFromObject(obj: com.google.gson.JsonObject): Node {
        planLabel(obj)?.let { label ->
            val children = obj.entrySet()
                .filter { it.key !in LABEL_KEYS }
                .flatMap { (key, value) ->
                    // 「Plans」类子计划数组：元素节点直接摊平进父节点——计划树用户关心的是
                    // 子计划节点本身，中间再垫一层 "Plans" 键包装只会多一次展开（真机 PG 形态）
                    if (value.isJsonArray && value.asJsonArray.size() > 0 && value.asJsonArray.all { it.isJsonObject }) {
                        fromElement(value)
                    } else {
                        listOf(entryNode(key, value))
                    }
                }
            return Node(label, children)
        }
        // 单键包装对象（顶层 {"Plan": …} 正是此形态）塌缩为其值节点
        if (obj.size() == 1) {
            val entry = obj.entrySet().first()
            val value = entry.value
            if (value.isJsonObject || value.isJsonArray) {
                val inner = fromElement(value)
                return if (inner.size == 1) inner[0] else Node(entry.key, inner)
            }
            return Node("${entry.key}: ${primitiveText(value)}")
        }
        return Node("{plan}", obj.entrySet().map { entryNode(it.key, it.value) })
    }

    private fun entryNode(key: String, value: JsonElement): Node = when {
        value.isJsonObject || value.isJsonArray -> Node(key, fromElement(value))
        else -> Node("$key: ${primitiveText(value)}")
    }

    /**
     * 计划节点标签（null = 非计划对象）：`Node Type on Relation (rows=N)`，两段各自缺省省略——
     * 关系名缺省退 Alias（不虚报表名，ExplainIndexAdvisor 同款口径）；行数 Plan Rows 缺省退 Actual Rows。
     */
    private fun planLabel(obj: com.google.gson.JsonObject): String? {
        val nodeType = stringOf(obj, "Node Type") ?: return null
        val relation = stringOf(obj, "Relation Name") ?: stringOf(obj, "Alias")
        val rows = longOf(obj, "Plan Rows") ?: longOf(obj, "Actual Rows")
        return buildString {
            append(nodeType)
            if (relation != null) append(" on ").append(relation)
            if (rows != null) append(" (rows=").append(rows).append(')')
        }
    }

    private fun stringOf(obj: com.google.gson.JsonObject, key: String): String? =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }

    private fun longOf(obj: com.google.gson.JsonObject, key: String): Long? =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asLong }.getOrNull() ?: runCatching { it.asString.trim().toLongOrNull() }.getOrNull()
        }

    private fun primitiveText(el: JsonElement): String = runCatching { el.asString }.getOrDefault(el.toString())

    // ---------- R61（V113①）：计划节点稳定标识与结构化提取 ----------

    /**
     * R61（V113①）：JSON 计划节点的**结构化视图**（跨次对比的对齐底座）。
     *
     * 节点稳定标识双档：
     *  - [PlanNodeInfo.path]＝结构位置路径（`"0"`、`"0.2"`——根序.子序，同次结果内唯一；
     *  - [PlanNodeInfo.matchKey]＝对齐键 `nodeType on relation#出现序`（同键多次出现按前序
     *    遍历出现序消歧）——跨次对比按本键对齐，兄弟增删只挪 path 不影响同键节点配对。
     */
    data class PlanNodeInfo(
        /** 结构位置路径（根序.子序.……，同次结果内稳定唯一）。 */
        val path: String,
        /** 对齐键（nodeType + relation + 出现序——跨次对比的节点身份）。 */
        val matchKey: String,
        /** 人读标签（与展示树 planLabel 同口径）。 */
        val label: String,
        val nodeType: String,
        val relation: String?,
        /** Plan Rows，缺省退 Actual Rows（EXPLAIN ANALYZE）。 */
        val rows: Long?,
        /** Total Cost（PG JSON 档；缺 null 不虚标）。 */
        val cost: Double?,
        /** 其余标量键值（diff 细节比对面；对象/数组值跳过——标量才可比）。 */
        val extras: Map<String, String>,
        val estimatedRows: Long? = null,
        val actualRowsPerLoop: Double? = null,
    )

    /**
     * 把 EXPLAIN 结果行扁平化为计划节点清单（前序遍历；解析失败/非计划对象 → 空清单——
     * 诚实降级：没有可对齐的结构就没有对比，调用方按无可比处理）。
     */
    fun flattenPlanNodes(rows: List<List<String>>): List<PlanNodeInfo> {
        val out = mutableListOf<PlanNodeInfo>()
        val ordinals = HashMap<String, Int>()
        var rootOrdinal = 0
        for (row in rows) {
            for (cell in row) {
                val text = cell.trim()
                if (text.isEmpty()) continue
                val parsed = runCatching { JsonParser.parseString(text) }.getOrNull() ?: continue
                collectPlanObjects(parsed) { obj ->
                    val nodeType = stringOf(obj, "Node Type") ?: return@collectPlanObjects
                    val relation = stringOf(obj, "Relation Name") ?: stringOf(obj, "Alias")
                    val base = buildString {
                        append(nodeType)
                        if (relation != null) append(" on ").append(relation)
                    }
                    val ord = ordinals.merge(base, 1, Int::plus)!! - 1
                    val rowsVal = longOf(obj, "Plan Rows") ?: longOf(obj, "Actual Rows")
                    val costVal = doubleOf(obj, "Total Cost")
                    val extras = LinkedHashMap<String, String>()
                    obj.entrySet().forEach { (k, v) ->
                        if (k !in LABEL_KEYS && k != "Plans" && k != "Total Cost" && v.isJsonPrimitive) {
                            extras[k] = primitiveText(v)
                        }
                    }
                    out += PlanNodeInfo(
                        path = "$rootOrdinal",
                        matchKey = "$base#$ord",
                        label = buildString {
                            append(base)
                            if (rowsVal != null) append(" (rows=").append(rowsVal).append(')')
                        },
                        nodeType = nodeType,
                        relation = relation,
                        rows = rowsVal,
                        cost = costVal,
                        extras = extras,
                        estimatedRows = longOf(obj, "Plan Rows"),
                        actualRowsPerLoop = doubleOf(obj, "Actual Rows"),
                    )
                    rootOrdinal++
                }
            }
        }
        return out
    }

    /** 递归收集计划对象（顶层包装塌缩语义与展示树一致：数组摊平、`{"Plan": …}` 单键包装下钻）。 */
    private fun collectPlanObjects(el: JsonElement, sink: (com.google.gson.JsonObject) -> Unit) {
        when {
            el.isJsonArray -> el.asJsonArray.forEach { collectPlanObjects(it, sink) }
            el.isJsonObject -> {
                val obj = el.asJsonObject
                if (obj.get("Node Type") != null) {
                    sink(obj)
                    obj.get("Plans")?.let { plans -> collectPlanObjects(plans, sink) }
                } else if (obj.size() == 1) {
                    collectPlanObjects(obj.entrySet().first().value, sink)
                }
            }
            else -> Unit
        }
    }

    private fun doubleOf(obj: com.google.gson.JsonObject, key: String): Double? =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asDouble }.getOrNull() ?: runCatching { it.asString.trim().toDoubleOrNull() }.getOrNull()
        }
}
