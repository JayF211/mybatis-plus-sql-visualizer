package cn.song.mybatisplus.visualizer.log.agent

import com.google.gson.JsonParser

/**
 * agent 事件模型 + JSON 解析（IDE 侧；与 agent 侧 util/Jsons.writeEvent 键位契约一一对应）。
 * 全字段可选容错：缺失/形态异常 → null/默认（协议演进向前兼容）。
 */
data class AgentEvent(
    /** SQL 文本（PreparedStatement 为 prepare 时 SQL，Statement 为 execute 参数，批为拼接）。 */
    val sql: String,
    /** 采集时间戳（毫秒）。 */
    val timestamp: Long,
    /** 精确耗时（纳秒）。 */
    val durationNanos: Long,
    /** 影响行数（查询 null）。 */
    val updateCount: Long?,
    /** 执行报错（类名: 消息；成功 null）。 */
    val errorText: String?,
    /** 调用栈过滤后的源文件（相对路径）。 */
    val sourceFile: String?,
    /** 行号（0 未知）。 */
    val sourceLine: Int,
    /** T-AgentSourceJump：源码位置候选（栈序 [file, line]；旧版 agent 无此键 → 空列表，跳转回退主位置）。 */
    val sourceCandidates: List<Pair<String, Int>> = emptyList(),
    /** 方法聚焦键 {SimpleClass}#{method}。 */
    val methodKey: String?,
    /** 请求聚合标签。 */
    val requestTag: String?,
    /** 参数快照 [index, valueText|null, type|null]（agent 侧已过脱敏层）。 */
    val params: List<Triple<Int, String?, String?>>,
    /** 执行线程名。 */
    val threadName: String?,
    /** connect 时连接 URL（T-AgentDialectLoop；旧版 agent 无此键 → null 容错）。 */
    val url: String? = null,
) {
    companion object {
        /** 解析 EVENT 帧 body；结构不符返回 null（丢弃并计数，不断流）。 */
        fun parse(body: String): AgentEvent? = runCatching {
            val o = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val sql = o.get("sql")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                ?: return null
            val params = mutableListOf<Triple<Int, String?, String?>>()
            o.get("p")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
                val arr = el.takeIf { it.isJsonArray }?.asJsonArray ?: return@forEach
                if (arr.size() >= 1 && arr.get(0).isJsonPrimitive) {
                    params.add(
                        Triple(
                            arr.get(0).asInt,
                            arr.getOrNull(1)?.takeIf { it.isJsonPrimitive }?.asString,
                            arr.getOrNull(2)?.takeIf { it.isJsonPrimitive }?.asString,
                        ),
                    )
                }
            }
            AgentEvent(
                sql = sql,
                timestamp = o.get("ts")?.asLong ?: System.currentTimeMillis(),
                durationNanos = o.get("dn")?.asLong ?: 0L,
                updateCount = o.get("u")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong,
                errorText = o.get("err")?.takeIf { it.isJsonPrimitive }?.asString,
                sourceFile = o.get("sf")?.takeIf { it.isJsonPrimitive }?.asString,
                sourceLine = o.get("sl")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                sourceCandidates = parseCandidates(o.get("cands")),
                methodKey = o.get("mk")?.takeIf { it.isJsonPrimitive }?.asString,
                requestTag = o.get("rt")?.takeIf { it.isJsonPrimitive }?.asString,
                params = params,
                threadName = o.get("th")?.takeIf { it.isJsonPrimitive }?.asString,
                url = o.get("url")?.takeIf { it.isJsonPrimitive }?.asString,
            )
        }.getOrNull()

        /** `cands` 数组解析（[file, line] 对；畸形项跳过——协议演进容错）。 */
        private fun parseCandidates(el: com.google.gson.JsonElement?): List<Pair<String, Int>> {
            val arr = el?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            val out = mutableListOf<Pair<String, Int>>()
            arr.forEach { item ->
                val pair = item.takeIf { it.isJsonArray }?.asJsonArray ?: return@forEach
                val file = pair.getOrNull(0)?.takeIf { it.isJsonPrimitive }?.asString
                if (file.isNullOrBlank()) return@forEach
                val line = pair.getOrNull(1)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                out.add(file to maxOf(line, 0))
            }
            return out
        }

        private fun com.google.gson.JsonArray.getOrNull(i: Int) =
            if (size() > i) get(i) else null
    }
}
