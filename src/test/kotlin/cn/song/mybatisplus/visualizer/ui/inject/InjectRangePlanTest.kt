package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.openapi.util.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-XmlGap W1：注入区间互补的纯逻辑单测（[InjectRangePlan]）。
 *
 * 这些用例是「让位下沉到区间级」的判别位——原形态（宿主级让位）在这里必须全红：
 * 只要有任何占用区间就整宿主放弃，下面的「部分占用 → 余下区间仍注入」一个都过不去。
 */
class InjectRangePlanTest {

    private fun r(s: Int, e: Int) = TextRange(s, e)

    // ------------------------------------------------------------------
    // 互补（核心）
    // ------------------------------------------------------------------

    /** 无占用 → 整段一个区间（等价旧形态的「不让位」）。 */
    @Test
    fun testW1_无占用_整段原样() {
        val text = "SELECT * FROM t WHERE a = #{id}"
        assertEquals(listOf(r(0, text.length)), InjectRangePlan.plan(text, r(0, text.length), emptyList()))
    }

    /** 语句中间一个 `#{}` 被占用 → 切成前后两段（旧形态在这里整宿主放弃）。 */
    @Test
    fun testW1_中间占一个参数_切前后两段() {
        val text = "SELECT * FROM t WHERE a = #{id} AND b = 2"
        val open = text.indexOf("#{")
        val occupied = listOf(r(open, open + 5)) // #{id} → 5 字符
        val plan = InjectRangePlan.plan(text, r(0, text.length), occupied)
        assertEquals(
            "占用段被跳过，余下前后两段",
            listOf(r(0, open), r(open + 5, text.length)),
            plan,
        )
        // 注意：这里用显式偏移比较而不是 TextRange.intersects —— 后者对本仓平台版本而言
        // 「端点相接」也算相交（(0,26) 与 (26,31) 判相交），语义上并非「重叠」。
        assertTrue(
            "互补区间不得与占用区间重叠",
            plan.none { it.startOffset < occupied[0].endOffset && occupied[0].startOffset < it.endOffset },
        )
    }

    /** 全段被占用（哨兵形态）→ 空表（调用方据此零注入）。 */
    @Test
    fun testW1_全覆盖_空表() {
        val text = "SELECT 1"
        assertTrue(InjectRangePlan.plan(text, r(0, text.length), InjectRangePlan.yieldAll(text.length)).isEmpty())
    }

    /** 占用区间在开头/结尾 → 只余一侧，且不留零长区间。 */
    @Test
    fun testW1_首尾占用_只余一侧且无零长() {
        val text = "#{a}SELECT 1"
        val plan = InjectRangePlan.plan(text, r(0, text.length), listOf(r(0, 4)))
        assertEquals(listOf(r(4, text.length)), plan)
        val plan2 = InjectRangePlan.plan(text, r(0, text.length), listOf(r(0, text.length)))
        assertTrue(plan2.isEmpty())
    }

    /** 多个占用区间 → 多段互补（顺序、互不重叠、非空；末段恰好止于文本尾则不入表）。 */
    @Test
    fun testW1_多占用_多段互补() {
        val text = "a=#{x} AND b=\${y} AND c=#{z}"
        val occupied = listOf(r(2, 6), r(13, 17), r(24, 28))
        val plan = InjectRangePlan.plan(text, r(0, text.length), occupied)
        assertEquals(listOf(r(0, 2), r(6, 13), r(17, 24)), plan)
        assertEquals("互补段按序", plan.sortedBy { it.startOffset }, plan)
        for (i in 0 until plan.size - 1) {
            assertTrue("互补段互不重叠", plan[i].endOffset < plan[i + 1].startOffset)
        }
    }

    /** 乱序 + 重叠 + 越界的占用区间 → 归一后仍产出正确互补（不重复切、不越界）。 */
    @Test
    fun testW1_乱序重叠越界_归一后互补() {
        val text = "0123456789"
        val plan = InjectRangePlan.plan(
            text,
            r(0, text.length),
            listOf(r(6, 9), r(2, 4), r(3, 7), r(0, 1), r(8, 999)),
        )
        assertEquals(
            "归一：[0,1)、{2..4 与 3..7 与 6..9 与 8..999 合并} → 余 [1,2)",
            listOf(r(1, 2)),
            plan,
        )
    }

    /** 我方范围本身被部分占用且我方范围是子段（属性值形态：宿主 == 范围）。 */
    @Test
    fun testW1_我方范围是子段() {
        val text = "\"${'$'}{a} AND b\""
        val our = r(1, text.length - 1)
        val plan = InjectRangePlan.plan(text, our, listOf(r(1, 6)))
        assertEquals(listOf(r(6, text.length - 1)), plan)
    }

    // ------------------------------------------------------------------
    // 参数占位整段扩张
    // ------------------------------------------------------------------

    /** 竞品 `${}` 注入器只报 `${` 之后到 `}` 之前 —— 扩张须包住整个 `${...}`，免得切出悬空定界符。 */
    @Test
    fun testW1_扩张_美元占位只报内段_扩到整段() {
        val text = "WHERE a = ${'$'}{col} AND b = 1"
        val inner = r(text.indexOf("{col}") + 1, text.indexOf("}"))
        val expanded = InjectRangePlan.expandToParamSpan(text, inner)
        assertEquals("扩张到 \${...} 整段", r(text.indexOf("${'$'}{"), text.indexOf("}") + 1), expanded)
    }

    /** 已是整段（`#{}` 注入器报全段）→ 原样。 */
    @Test
    fun testW1_扩张_已是整段_原样() {
        val text = "a = #{id} AND b"
        val full = r(text.indexOf("#{"), text.indexOf("}") + 1)
        assertEquals(full, InjectRangePlan.expandToParamSpan(text, full))
    }

    /** 不在参数占位内 → 原样（不动普通文本区间）。 */
    @Test
    fun testW1_扩张_不在占位内_原样() {
        val text = "SELECT * FROM t"
        val range = r(7, 9)
        assertEquals(range, InjectRangePlan.expandToParamSpan(text, range))
    }

    /** 越过上一个参数收尾（区间前遇到 `}`）→ 判定不在占位内，原样。 */
    @Test
    fun testW1_扩张_越过前一个参数收尾_原样() {
        val text = "#{a}bbb"
        val range = r(4, 7)
        assertEquals(range, InjectRangePlan.expandToParamSpan(text, range))
    }

    /** 转义形态 `\#{` 不视为占位起点 → 不扩张。 */
    @Test
    fun testW1_扩张_转义不扩张() {
        val text = "\\#{a}bb"
        val range = r(4, 7)
        assertEquals(range, InjectRangePlan.expandToParamSpan(text, range))
    }

    /** 未闭合占位（无 `}`）→ 不外扩（避免吞掉后续整段文本）。 */
    @Test
    fun testW1_扩张_未闭合_不外扩() {
        val text = "a = #{open"
        val inner = r(text.indexOf("{open") + 1, text.length)
        val expanded = InjectRangePlan.expandToParamSpan(text, inner)
        assertEquals("无收尾 } → 只包到区间终点", r(text.indexOf("#{"), text.length), expanded)
    }

    // ------------------------------------------------------------------
    // 哨兵与退化输入
    // ------------------------------------------------------------------

    @Test
    fun testW1_哨兵_空宿主为空表() {
        assertTrue(InjectRangePlan.yieldAll(0).isEmpty())
        assertEquals(listOf(r(0, 5)), InjectRangePlan.yieldAll(5))
    }

    @Test
    fun testW1_退化输入_空范围与空文本() {
        assertTrue(InjectRangePlan.plan("", r(0, 0), emptyList()).isEmpty())
        assertTrue(InjectRangePlan.plan("abc", r(1, 1), emptyList()).isEmpty())
    }

    /** 我方范围越出文本 → 裁剪后再互补，不抛、不产越界区间。 */
    @Test
    fun testW1_我方范围越界_裁剪() {
        val plan = InjectRangePlan.plan("abcdef", r(0, 999), listOf(r(2, 4)))
        assertEquals(listOf(r(0, 2), r(4, 6)), plan)
    }
}
