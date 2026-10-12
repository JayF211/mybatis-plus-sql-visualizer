package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.openapi.util.TextRange

/**
 * T-XmlGap W1：注入区间互补的纯逻辑（无平台依赖，便于单测）。
 *
 * 背景（计划 §六 W1 行 + §四 决策 1-A）：原让位判定是**宿主级**的——外来注入器只要对该宿主
 * 调用过 `startInjecting`，本插件就整宿主放弃（`SqlMapperInjector` 直接 return），于是「对方只占了
 * 一个 `#{}` 区间」也会导致**整条语句**的 SQL 关键字/注释/字符串全部无色。本轮把让位下沉到
 * **区间级**：算出外来注入器实际占用的区间，我方只注入**未被子集覆盖的余下区间**。
 *
 * 两个要点：
 *  1. **占用区间先扩张到包住它的 `#{...}` / `${...}` 整段**——竞品的 `${}` 注入器只报
 *     「`${` 之后到 `}` 之前」的区间（前缀合成 `%{`），若照原样求补，会把 `${` 与 `}` 两个定界符
 *     切给我方单独着色成一个运算符 token，观感错乱。扩张后切割点落在参数占位边界上。
 *  2. 补集产出**按序、互不重叠、非空**；全被覆盖时返回空表（调用方据此零注入）。
 */
object InjectRangePlan {

    /** 全覆盖哨兵：探测失败时用它表达「让位（保守）」，调用方无需额外分支。 */
    fun yieldAll(hostLength: Int): List<TextRange> =
        if (hostLength <= 0) emptyList() else listOf(TextRange(0, hostLength))

    /**
     * 我方应注入的区间表：`ourRange` 去掉 `occupied` 覆盖的部分。
     *
     * @param text     宿主文本（用于扩张 `#{...}`/`${...}` 整段边界）
     * @param ourRange 我方打算注入的整段（宿主坐标系）
     * @param occupied 外来注入器占用的区间（宿主坐标系，可为空/乱序/越界）
     */
    fun plan(text: CharSequence, ourRange: TextRange, occupied: List<TextRange>): List<TextRange> {
        if (ourRange.isEmpty) return emptyList()
        val clipStart = maxOf(0, ourRange.startOffset)
        val clipEnd = minOf(text.length, ourRange.endOffset)
        if (clipStart >= clipEnd) return emptyList()
        val blocks = mergeBlocks(text, occupied)
        if (blocks.isEmpty()) return listOf(TextRange(clipStart, clipEnd))

        val out = mutableListOf<TextRange>()
        var cursor = clipStart
        for (b in blocks) {
            if (b.endOffset <= cursor) continue
            if (b.startOffset >= clipEnd) break
            val gapEnd = minOf(b.startOffset, clipEnd)
            if (gapEnd > cursor) out += TextRange(cursor, gapEnd)
            cursor = maxOf(cursor, minOf(b.endOffset, clipEnd))
            if (cursor >= clipEnd) break
        }
        if (cursor < clipEnd) out += TextRange(cursor, clipEnd)
        return out
    }

    /** 占用区间归一：裁剪到宿主范围 → 扩张到参数占位整段 → 按起点排序去空 → 合并重叠/相邻。 */
    private fun mergeBlocks(text: CharSequence, occupied: List<TextRange>): List<TextRange> {
        if (occupied.isEmpty()) return emptyList()
        val normalized = occupied
            .mapNotNull { r ->
                val s = maxOf(0, r.startOffset)
                val e = minOf(text.length, r.endOffset)
                if (s >= e) null else expandToParamSpan(text, TextRange(s, e))
            }
            .sortedBy { it.startOffset }
        if (normalized.isEmpty()) return emptyList()
        val merged = mutableListOf<TextRange>()
        var cur = normalized.first()
        for (r in normalized.drop(1)) {
            cur = if (r.startOffset <= cur.endOffset) TextRange(cur.startOffset, maxOf(cur.endOffset, r.endOffset)) else {
                merged += cur
                r
            }
        }
        merged += cur
        return merged
    }

    /**
     * 把区间扩张到包住它的 `#{...}` / `${...}` 整段；不在任何参数占位内则原样返回。
     *
     * 向左找最近的未转义 `#{`/`${`；途中遇到 `}` 说明已越过上一个参数的收尾，判定为「不在参数内」。
     * 未闭合（找不到 `}`）时扩张到区间自身终点——不外扩，避免把后续整段文本吞掉。
     */
    fun expandToParamSpan(text: CharSequence, range: TextRange): TextRange {
        var open = -1
        var k = range.startOffset
        while (k >= 2) {
            if ((text[k - 2] == '#' || text[k - 2] == '$') && text[k - 1] == '{' &&
                (k - 2 == 0 || text[k - 3] != '\\')
            ) {
                open = k - 2
                break
            }
            if (text[k - 1] == '}') break
            k--
        }
        if (open < 0) return range
        val close = indexOf(text, '}', open + 2)
        val spanEnd = if (close >= 0) close + 1 else range.endOffset
        if (spanEnd < range.endOffset) return range
        return TextRange(open, spanEnd)
    }

    private fun indexOf(text: CharSequence, c: Char, from: Int): Int {
        for (i in from until text.length) if (text[i] == c) return i
        return -1
    }
}
