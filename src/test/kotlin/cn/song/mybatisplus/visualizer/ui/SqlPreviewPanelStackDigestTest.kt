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
 * 已知未接通项（本测试不掩盖、也不改变生产行为）：[buildStackDigestMetaText] 的 `inlineLine`
 * 目前只进 `metaLabel.toolTipText` 首行，而可见行文本 `metaLineText`（T-PreviewTopOverlap F3
 * 宽度预算截断的输入）只拼 `meta + warningLine`，未并入该摘要——即验收项前半句「meta 行显示
 * 调用栈」在**可见行**上尚未接通（真机走查第 1 步会看不到「调用栈: …」）。详见
 * [test现状_可见meta行未含调用栈摘要_仅tooltip有] 的说明。
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
     * 现状锁定：可见 meta 行（`metaLabel.text`）**不含**调用栈摘要——摘要目前只进 tooltipText。
     *
     * 这正是验收项前半句「meta 行显示调用栈」在真机走查第 1 步会落空的原因（第 2 步悬停可见完整链
     * 已由 [test面板悬停tooltip_含完整调用链与截断摘要] 锁定）。本用例把「接线缺口」写成可执行断言，
     * 使缺口一旦被修（把 `stackText.inlineLine` 并入 `metaLineText`）本用例立即转红，从而强制同步
     * 更新本测试与验收文档——避免缺口被静默当成「已通过」。
     */
    fun test现状_可见meta行未含调用栈摘要_仅tooltip有() {
        val panel = SqlPreviewPanel(project)
        try {
            val digest = chainOf(300)
            panel.showResult(makeResult(digest))
            waitForEdt()
            // 未布局分支（headless）：metaLabel.text 取 fullDisplayText() = metaLineText
            panel.applyMetaTruncationForTest()
            waitForEdt()

            val visible = panel.metaLabelForTest()
            assertFalse(
                "现状：可见 meta 行不含调用栈摘要（缺口未修）——若此处失败说明生产已接通，" +
                    "请同步更新本测试与验收文档判定卡",
                visible.contains(label()),
            )
            // 反证摘要本身已产出且进了 tooltip，缺口仅在「可见行未拼接」
            assertTrue((panel.metaTooltipForTest() ?: "").contains(label()))
        } finally {
            panel.clear()
        }
    }
}
