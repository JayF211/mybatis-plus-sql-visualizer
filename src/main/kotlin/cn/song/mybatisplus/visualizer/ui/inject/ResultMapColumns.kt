package cn.song.mybatisplus.visualizer.ui.inject

import kotlin.math.min

/**
 * T-XmlSynHi D5 后半：resultMap `column` 属性补全的候选提取与排序（纯函数）。
 *
 * 候选来源 = mapper 文本中全部语句（select）的**投影列名**提取：
 *  - `SELECT a, b, c FROM …` 逗号分段取末段标识符（`t.col`/`a AS x` 取别名或末段）；
 *  - `SELECT *` 不可枚举（跳过）；
 *  - `t.col` 形态剥前缀取 `col`，同时保留 `t.col` 原形作为次选。
 *
 * 排序 = 前缀精确命中 > 前缀前缀命中 > 其余；同级按与已输入的 Levenshtein 距离升序
 * （计划 §六 D5「下划线驼峰建议」：`order_id` 与 `orderId` 双向互为近邻建议）。
 */
object ResultMapColumns {

    // T-PerfSweep16 F40：Regex 提为伴生对象单例（原每模式每次调用现场编译——主正则每补全一次、
    // 段内 `[\\w*]+` 每投影列分段一次）
    private val SELECT_PROJECTION = Regex("(?is)\\bselect\\b(.*?)(?:\\bfrom\\b|\$)")
    private val COLUMN_IDENT = Regex("[\\w*]+")

    // T-XmlGap W5-10：`<select … resultMap="X" …>…</select>` 逐条匹配（属性段 + 标签体）
    private val SELECT_STATEMENT = Regex("(?is)<select\\b([^>]*)>(.*?)</select>")
    private val RESULT_MAP_ATTR = Regex("""(?i)\bresultMap\s*=\s*"([^"]*)"""")

    /** 从 mapper 全文中提取 SELECT 投影列名（去重保序）。 */
    fun extractColumns(mapperText: String): List<String> {
        val out = LinkedHashSet<String>()
        SELECT_PROJECTION.findAll(mapperText).forEach { m ->
            out += projectionColumns(m.groupValues[1])
        }
        return out.toList()
    }

    /**
     * T-XmlGap W5-10：**只**解析「引用了指定 resultMap 的那些 `<select>` 语句」的投影列
     * （候选来源修正：原实现取 mapper 全文 SELECT，会混入与该 resultMap 无关的列）。
     *
     * 引用判定：`<select … resultMap="X">` 中 `X` 精确等于 [resultMapId]，或为其 `namespace.X`
     * 尾段形态（跨 namespace 引用时 XML 里写全限定 `ns.id`）。
     * 无任何 `<select>` 引用该 resultMap → 空（不再回退全文，避免无关列污染）。
     */
    fun extractColumnsForResultMap(mapperText: String, resultMapId: String): List<String> {
        if (resultMapId.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        SELECT_STATEMENT.findAll(mapperText).forEach { m ->
            val ref = RESULT_MAP_ATTR.find(m.groupValues[1])?.groupValues?.get(1)?.trim()
            if (ref != null && refMatches(ref, resultMapId)) {
                // 语句体先按 SELECT…FROM 截出投影段（否则 `FROM t` 的 `t` 会被当列名）
                val projection = SELECT_PROJECTION.find(m.groupValues[2])?.groupValues?.get(1).orEmpty()
                out += projectionColumns(projection)
            }
        }
        return out.toList()
    }

    /** resultMap 引用匹配：精确 / `ns.id` 尾段（`.` 分隔段的完整尾匹配）。 */
    private fun refMatches(reference: String, resultMapId: String): Boolean =
        reference == resultMapId || reference.endsWith(".$resultMapId")

    /** 投影段 → 列名（逗号分段取末 token 的末段标识符；`*` 跳过）。 */
    private fun projectionColumns(projection: String): List<String> {
        val out = LinkedHashSet<String>()
        projection.split(",").forEach { seg ->
            val col = seg.trim()
                .split(' ').last { it.isNotBlank() } // 末 token：AS 别名或列本身（取别名语义）
                .substringAfterLast('.')             // `t.col` → `col`
                .trim()
            if (col.isNotEmpty() && col.matches(COLUMN_IDENT) && col != "*") out += col
        }
        return out.toList()
    }

    /** 补全建议：前缀过滤 + 排序（下划线/驼峰双向近邻提升）。 */
    fun suggest(columns: List<String>, typed: String): List<String> {
        val t = typed.trim()
        if (t.isEmpty()) return columns
        val scored = columns.mapNotNull { col ->
            val lower = col.lowercase()
            val target = t.lowercase()
            when {
                lower.startsWith(target) -> col to 0
                camelSnakeMatch(col, t) -> col to 1
                else -> null
            }
        }.sortedWith(compareBy({ it.second }, { levenshtein(it.first.lowercase(), t.lowercase()) }, { it.first }))
            .map { it.first }
        return scored
    }

    /** 下划线-驼峰近邻：`order_id` vs `orderId` 同形（归一化 `_` 与大小写后比较前缀）。 */
    private fun camelSnakeMatch(col: String, typed: String): Boolean {
        val normCol = col.replace("_", "").lowercase()
        val normTyped = typed.replace("_", "").lowercase()
        return normCol.startsWith(normTyped)
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        var prev = IntArray(n + 1) { it }
        var cur = IntArray(n + 1)
        for (i in 1..m) {
            cur[0] = i
            for (j in 1..n) {
                cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[n]
    }
}
