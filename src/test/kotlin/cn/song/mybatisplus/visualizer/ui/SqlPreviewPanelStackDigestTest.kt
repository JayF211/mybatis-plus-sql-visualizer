package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * T-SourceJumpFunnel §3.3「预览 meta 行显示调用栈、悬停见完整链」的自动化断言。
 *
 * 验收项原文（`docs/验收文档/20260917-2058-T-SourceJumpFunnel-自研框架汇聚入口跳源码上溯适配-验收文档.md`
 * 判定卡）：「预览 meta 行显示调用栈、悬停见完整链（§3.3）」——此前标注「需真机：截断展示与悬停
 * 全文为纯视觉判断」。本测试把「截断展示 / 悬停全文」的**文本契约**固化：
 *  - 生产侧文案组装抽为顶层纯函数 [buildStackDigestMetaText]（组装表达式与原内联实现逐字一致，
 *    零行为变更），直接断言摘要截断（≤120 字符 + 「…」）与 tooltip 全文节点；
 *  - 面板侧用真实 [SqlPreviewPanel.showResult] 断言 tooltip 接线（headless 下 `toolTipText`
 *    就是 Swing 悬停展示的文本；真实鼠标悬停动作本身不可自动化）。
 *
 * 覆盖边界：>120 截断、==120 不截断、121 恰好越界、<120 原样、null 无节点。
 *
 * P5 修复（本测试同步更新）：[buildStackDigestMetaText] 的 `inlineLine` 已并入**可见行**
 * `metaLineText`（此前只进 `metaLabel.toolTipText`）——验收项前半句「meta 行显示调用栈」在可见行
 * 上接通，见 [test可见meta行含调用栈摘要_截断到120字符]。
 *
 * P5 复核修正（20260920，本测试再次同步）：摘要并入后**不得反过来挤掉主体**。首版把「主体 + 摘要」
 * 当单段文本交给 F3 的中段省略（保留尾部 30%），而默认宽度预算仅够 15~25 字符、摘要上限 120 字符
 * → 窄窗下主体（表名 / wrapper 类型）被整段挤掉，只剩「…」+ 栈底帧（信息量最低的一端）。
 * 现按段组装（[metaLineWithinBudget]：主体优先、摘要吃剩余、剩余不足 [META_DIGEST_MIN_PX] 则整段
 * 丢弃），纯函数规则见 `test组装_*` 五例，面板侧接线见 [test窄窗_主体完整保留_摘要截尾_tooltip恒全文]。
 */
class SqlPreviewPanelStackDigestTest : BasePlatformTestCase() {

    private fun waitForEdt() = UIUtil.dispatchAllInvocationEvents()

    /** `   调用栈: ` 前缀（三空格 + bundle 文案 + 空格），与原实现的字面拼接同源，避免文案改动误伤断言。 */
    private fun prefix(): String =
        "   " + MpSqlVisualizerBundle.message("panel.meta.stackDigest") + " "

    /** 纯文案前缀（tooltip 全文节点用，无缩进）。 */
    private fun label(): String = MpSqlVisualizerBundle.message("panel.meta.stackDigest")

    /** 造一条长度恰为 [length] 的调用链（栈顶在左；**不含省略号**，便于区分截断标记来源）。 */
    private fun chainOf(length: Int): String {
        val unit = "com.corp.biz.Svc#m:1 <- "
        val sb = StringBuilder()
        while (sb.length < length) sb.append(unit)
        return sb.substring(0, length)
    }

    private fun makeResult(stackDigest: String?): PreviewResult = PreviewResult(
        sql = "SELECT id\nFROM user\nWHERE username = ?",
        compactSql = "SELECT id FROM user WHERE username = ?",
        unresolvedParams = listOf("username"),
        paramTable = null,
        wrapperType = WrapperType.LAMBDA_QUERY,
        tableName = "user",
        columns = listOf("id", "username"),
        stackDigest = stackDigest,
    )

    // ------------------------------------------------------------------
    // 一、纯函数契约（摘要截断 + tooltip 全文节点）
    // ------------------------------------------------------------------

    fun test长调用栈_meta行摘要截断到120字符并带省略号() {
        val digest = chainOf(300)
        val text = buildStackDigestMetaText(digest)

        // 摘要 = 前 120 字符 + 截断标记（逐字期望值，非仅长度断言）
        assertEquals(prefix() + digest.substring(0, 120) + "…", text.inlineLine)
        assertTrue("摘要应带截断标记", text.inlineLine.endsWith("…"))
        // 调用栈本体长度 = 120 + 标记 1 字符，未超上限
        assertEquals(121, text.inlineLine.removePrefix(prefix()).length)
        assertTrue(
            "摘要（去前缀后）不得超过 ${STACK_DIGEST_MAX_CHARS} + 标记长度",
            text.inlineLine.length <= prefix().length + STACK_DIGEST_MAX_CHARS + 1,
        )
        assertFalse("摘要不得含完整链", text.inlineLine.contains(digest))
    }

    fun test长调用栈_tooltip含完整链不截断() {
        val digest = chainOf(300)
        val text = buildStackDigestMetaText(digest)

        assertEquals("\n" + label() + " " + digest, text.tooltipTail)
        assertTrue("tooltip 节点应含完整调用链", text.tooltipTail.contains(digest))
        assertFalse("tooltip 全文节点不得带截断标记", text.tooltipTail.endsWith("…"))
        // 全文节点比摘要长：二者不可互相替代
        assertTrue(text.tooltipTail.length > text.inlineLine.length)
    }

    fun test短调用栈_不截断不加标记() {
        val digest = chainOf(60)
        assertTrue("构造前提：短栈 < ${STACK_DIGEST_MAX_CHARS}", digest.length < STACK_DIGEST_MAX_CHARS)
        val text = buildStackDigestMetaText(digest)

        assertEquals(prefix() + digest, text.inlineLine)
        assertFalse("短栈摘要不应出现截断标记", text.inlineLine.contains("…"))
        assertEquals("\n" + label() + " " + digest, text.tooltipTail)
        assertFalse("短栈 tooltip 不应出现截断标记", text.tooltipTail.contains("…"))
    }

    fun test边界_120字符不截断_121字符截断() {
        val at120 = chainOf(STACK_DIGEST_MAX_CHARS)
        assertEquals(STACK_DIGEST_MAX_CHARS, at120.length)
        assertEquals(prefix() + at120, buildStackDigestMetaText(at120).inlineLine)

        val at121 = chainOf(STACK_DIGEST_MAX_CHARS + 1)
        assertEquals(STACK_DIGEST_MAX_CHARS + 1, at121.length)
        val truncated = buildStackDigestMetaText(at121).inlineLine
        assertEquals(prefix() + at121.substring(0, STACK_DIGEST_MAX_CHARS) + "…", truncated)
        // 边界外仅 1 字符，仍按「截到 120 + 标记」处理（严格大于阈值才截断）
        assertEquals(121, truncated.removePrefix(prefix()).length)
    }

    fun test无调用栈_两段文案皆空() {
        val text = buildStackDigestMetaText(null)
        assertEquals("", text.inlineLine)
        assertEquals("", text.tooltipTail)
    }

    // ------------------------------------------------------------------
    // 二、面板接线（真实 showResult → metaLabel.toolTipText）
    // ------------------------------------------------------------------

    fun test面板悬停tooltip_含完整调用链与截断摘要() {
        val panel = SqlPreviewPanel(project)
        try {
            val digest = chainOf(300)
            panel.showResult(makeResult(digest))
            waitForEdt()

            val tooltip = panel.metaTooltipForTest()
            assertNotNull("meta 行应有 tooltip（悬停载体）", tooltip)
            // §3.3 第 2 步：悬停见完整链（不截断）
            assertTrue("tooltip 应含完整调用链", tooltip!!.contains(digest))
            assertTrue("tooltip 全文节点应为换行 + 文案 + 完整链", tooltip.contains("\n" + label() + " " + digest))
            // 首行仍保留截断摘要（不因全文节点而丢失）
            assertTrue(
                "tooltip 首行应含截断摘要（120 + …）",
                tooltip.contains(prefix() + digest.substring(0, 120) + "…"),
            )
        } finally {
            panel.clear()
        }
    }

    fun test面板无调用栈_tooltip不含调用栈节点() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(makeResult(null))
            waitForEdt()

            val tooltip = panel.metaTooltipForTest() ?: ""
            assertFalse("无快照时 tooltip 不应出现调用栈文案", tooltip.contains(label()))
            assertFalse("无快照时可见 meta 行不应出现调用栈文案", panel.metaLabelForTest().contains(label()))
        } finally {
            panel.clear()
        }
    }

    /**
     * P5 已接通：可见 meta 行（`metaLabel.text`）**含**调用栈摘要——`metaLineText` 已并入
     * [buildStackDigestMetaText] 的 `inlineLine`（未布局分支取全文，无宽度预算截断）。
     *
     * 本用例由原「现状锁定」用例改写而来（原文断言可见行**不含**摘要，缺口一修即转红，
     * 此处即该转红的同步更新）：断言可见行含 120 字符截断摘要且不含完整链（全文归悬停 tooltip）。
     */
    fun test可见meta行含调用栈摘要_截断到120字符() {
        val panel = SqlPreviewPanel(project)
        try {
            val digest = chainOf(300)
            panel.showResult(makeResult(digest))
            waitForEdt()
            // 未布局分支（headless）：metaLabel.text 取 fullDisplayText() = metaLineText
            panel.applyMetaTruncationForTest()
            waitForEdt()

            val visible = panel.metaLabelForTest()
            assertTrue("可见 meta 行应含调用栈文案节点", visible.contains(label()))
            assertTrue("可见行应含 120 字符截断摘要（带截断标记）", visible.contains(digest.substring(0, 120) + "…"))
            assertFalse("可见行不应含完整链（全文归悬停 tooltip）", visible.contains(digest))
            // 反证摘要仍在 tooltip 内（悬停契约不因可见行接通而退化）
            assertTrue((panel.metaTooltipForTest() ?: "").contains(digest))
        } finally {
            panel.clear()
        }
    }

    /**
     * P5 + F3 宽度预算：窄窗下**主体优先**——可见行保留 meta 主体（表名 / wrapper 类型），
     * 调用栈摘要按剩余宽度截尾；完整链仍恒在悬停 tooltip 内。
     *
     * 本用例由首版 `test窄窗_可见行中段省略_tooltip恒全文` 改写（原文断言「省略后保留摘要尾段」，
     * 即把「主体被挤掉、只剩栈底帧」当预期行为锁定——复核 20260920 判定为优先级倒挂，转红即此处同步）。
     *
     * headless 无 componentResized，按 SqlPreviewTopRowLayoutTest 的既有做法手动逐层 doLayout
     * 后直调 [SqlPreviewPanel.applyMetaTruncationForTest]。
     */
    fun test窄窗_主体完整保留_摘要截尾_tooltip恒全文() {
        val panel = SqlPreviewPanel(project)
        try {
            val digest = chainOf(300)
            panel.showResult(makeResult(digest))
            waitForEdt()
            panel.setSize(640, 420)
            panel.doLayout()
            panel.topRowForTest().parent?.doLayout()
            panel.topRowForTest().doLayout()
            panel.applyMetaTruncationForTest()
            waitForEdt()

            val visible = panel.metaLabelForTest()
            assertTrue("窄窗应触发截断", visible.contains("…"))
            assertFalse("窄窗可见行不得含完整链", visible.contains(digest))
            // 复核回归点：主体（表名）不得被摘要挤掉——首版实现此处会转红（可见行只剩「…」+ 栈底帧）
            assertTrue("主体（表名）必须仍在可见行内: $visible", visible.contains("user"))
            assertTrue("tooltip 恒全文（宽度预算不影响悬停）", (panel.metaTooltipForTest() ?: "").contains(digest))
        } finally {
            panel.clear()
        }
    }

    // ------------------------------------------------------------------
    // P5 复核修正：meta 行宽度预算组装（纯函数，脱 FontMetrics）
    // ------------------------------------------------------------------

    /** 1 字符 = 1px 的度量替身：宽度断言可直接读成字符数。 */
    private val widthOf: (String) -> Int = { it.length }

    private fun headText() = "表: user 类型: LambdaQueryWrapper"

    private fun digestText() = "   调用栈: " + chainOf(120)

    fun test组装_放得下则整段原样返回() {
        val head = headText()
        val digest = digestText()
        assertEquals(head + digest, metaLineWithinBudget(head + digest, head, digest, widthOf, 200))
    }

    fun test组装_无摘要时退回F3中段省略() {
        val head = headText()
        val out = metaLineWithinBudget(head, head, "", widthOf, 12)
        assertEquals("无快照路径须与 F3 原行为逐字一致", ellipsizeMiddle(head, widthOf, 12), out)
        assertTrue("应中段省略: $out", out.contains("…"))
    }

    fun test组装_主体自身超预算_主体中段省略() {
        val head = headText()
        val digest = digestText()
        // 预算 12 < 主体宽：摘要无从谈起，退回主体自身的 F3 中段省略
        assertEquals(ellipsizeMiddle(head, widthOf, 12), metaLineWithinBudget(head + digest, head, digest, widthOf, 12))
    }

    fun test组装_主体放得下_摘要截尾且主体不被挤掉() {
        val head = headText()
        val digest = digestText()
        val budget = head.length + META_DIGEST_MIN_PX + 4
        val out = metaLineWithinBudget(head + digest, head, digest, widthOf, budget)
        assertTrue("主体必须完整保留（复核回归点）: $out", out.startsWith(head))
        assertTrue("摘要应被截尾（带省略号）: $out", out.endsWith("…"))
        assertFalse("摘要不得整段保留: $out", out.contains(digest))
        assertTrue("总宽不得超预算: ${widthOf(out)} > $budget", widthOf(out) <= budget)
    }

    fun test组装_剩余宽度不足_整段丢弃摘要() {
        val head = headText()
        val digest = digestText()
        val budget = head.length + META_DIGEST_MIN_PX - 1
        assertEquals(
            "剩余不足应整段丢摘要（不留孤零零的省略号）",
            head,
            metaLineWithinBudget(head + digest, head, digest, widthOf, budget),
        )
    }
}
