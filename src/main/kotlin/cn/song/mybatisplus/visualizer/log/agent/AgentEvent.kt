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
    /** T-SourceJumpFunnel：候选帧类名（与 sourceCandidates 栈序对齐，可能少于候选数——旧 agent 无类名列）。 */
    val candidateClasses: List<String> = emptyList(),
    /** T-SourceJumpFunnel：候选帧方法名（与 sourceCandidates 栈序对齐）。 */
    val candidateMethods: List<String> = emptyList(),
    /** 方法聚焦键 {SimpleClass}#{method}。 */
    val methodKey: String?,
    /** 请求聚合标签。 */
    val requestTag: String?,
    /** T-SourceJumpFunnel P2-2：调用栈快照「类#方法:行 ← …」（栈顶在左；旧版 agent 无此键 → null）。 */
    val stackDigest: String? = null,
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
                candidateClasses = parseCandidateRows(o.get("cands")).mapNotNull { it.className },
                candidateMethods = parseCandidateRows(o.get("cands")).mapNotNull { it.method },
                methodKey = o.get("mk")?.takeIf { it.isJsonPrimitive }?.asString,
                requestTag = o.get("rt")?.takeIf { it.isJsonPrimitive }?.asString,
                stackDigest = o.get("sd")?.takeIf { it.isJsonPrimitive }?.asString,
                params = params,
                threadName = o.get("th")?.takeIf { it.isJsonPrimitive }?.asString,
                url = o.get("url")?.takeIf { it.isJsonPrimitive }?.asString,
            )
        }.getOrNull()

        /** `cands` 数组解析（[file, line] 对；畸形项跳过——协议演进容错）。 */
        private fun parseCandidates(el: com.google.gson.JsonElement?): List<Pair<String, Int>> =
            parseCandidateRows(el).map { it.file to it.line }

        /**
         * T-SourceJumpFunnel：`cands` 全列解析（[file, line, className, method] 四列；
         * 列尾追加协议——旧 agent 两列时 className/method 为 null 不入名单）。
         */
        private fun parseCandidateRows(el: com.google.gson.JsonElement?): List<CandidateRow> {
            val arr = el?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            val out = mutableListOf<CandidateRow>()
            arr.forEach { item ->
                val pair = item.takeIf { it.isJsonArray }?.asJsonArray ?: return@forEach
                val file = pair.getOrNull(0)?.takeIf { it.isJsonPrimitive }?.asString
                if (file.isNullOrBlank()) return@forEach
                val line = pair.getOrNull(1)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                val cn = pair.getOrNull(2)?.takeIf { it.isJsonPrimitive }?.asString
                val mn = pair.getOrNull(3)?.takeIf { it.isJsonPrimitive }?.asString
                out.add(CandidateRow(file, maxOf(line, 0), cn, mn))
            }
            return out
        }

        /** 候选行（T-SourceJumpFunnel）：file/line 必有，className/method 旧 agent 缺省。 */
        internal data class CandidateRow(val file: String, val line: Int, val className: String?, val method: String?)

        private fun com.google.gson.JsonArray.getOrNull(i: Int) =
            if (size() > i) get(i) else null
    }
}
