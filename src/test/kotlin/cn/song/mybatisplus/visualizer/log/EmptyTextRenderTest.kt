package cn.song.mybatisplus.visualizer.log

import com.intellij.util.ui.StatusText
import java.awt.Font
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-AgentDiagWrap T-04：空态文案「文本 → 显示」的渲染层守卫。
 *
 * 守卫目标：面板空态用的是 `JBList.emptyText`，其类型是 `com.intellij.util.ui.StatusText`
 * （**不是** JLabel，也不解析 HTML），内容落在 `SimpleColoredComponent` 上——
 * `\n` 被当普通字符画成零宽（两段文字无声拼接），超宽只裁不折。
 * 平台侧唯一的多行手段是 `StatusText.appendLine`：每行一个 Fragment，`paintColumnInBounds`
 * 逐 Fragment 单独绘制。本用例锁住「渲染点确实逐视觉行写入」——
 * 一旦有人把渲染点改回 `status.text = 纯文本`（本次修复前的形态），
 * 换行会重新消失、Fragment 数退回 1，用例转红。
 *
 * 这里用真实 `StatusText` 而不 mock：mock 只能证明「我调了 appendLine」，
 * 证不了「StatusText 收到 `\n` 会不会换行」——而那正是本次修复要回答的问题。
 */
class EmptyTextRenderTest {

    /** 真实 StatusText（无宿主组件：appendLine 只走内存 Fragment 结构，不需要显示环境）。 */
    private fun bareStatusText(): StatusText =
        object : StatusText() {
            override fun isStatusVisible(): Boolean = true
        }.apply { setFont(Font(Font.SANS_SERIF, Font.PLAIN, 12)) }

    /** 每字符 10px 的宽度桩。 */
    private val stubMeasure: (String) -> Int = { it.length * 10 }

    /** 当前空态实际会被绘制成几行（StatusText 逐 Fragment 绘制，与 paintColumnInBounds 同源）。 */
    private fun paintedLineCount(status: StatusText): Int = status.wrappedFragmentsIterable.count()

    @Test
    fun test三个语义行写成三个Fragment而非整段交给Swing() {
        val status = bareStatusText()
        applyEmptyTextLines(status, "甲行\n乙行\n丙行", 4000, stubMeasure)
        assertTrue(
            "StatusText 自己的文本不得含裸 \\n（含则等于整段交给 Swing，换行会被无声吞掉）：" +
                status.text.replace("\n", "\\n"),
            !status.text.contains('\n'),
        )
        assertEquals(
            "\\n 必须由渲染层折成 3 个 Fragment；退回 1 说明又把换行交给了 Swing（它不认）",
            3,
            paintedLineCount(status),
        )
    }

    @Test
    fun test超宽语义行折成多行且行数随宽度变化() {
        val status = bareStatusText()
        val text = "控制台有输出但未见 MyBatis SQL 日志——请确认应用已开启 SQL 日志输出（mapper 包日志级别为 debug），或启用增强捕获"
        applyEmptyTextLines(status, text, 4000, stubMeasure)
        val wide = paintedLineCount(status)
        applyEmptyTextLines(status, text, 200, stubMeasure)
        val narrow = paintedLineCount(status)
        assertEquals("足够宽时单条语义行应占一行", 1, wide)
        assertTrue("窄窗必须折成多行（否则尾巴被裁掉）：narrow=$narrow", narrow > 1)
    }

    @Test
    fun test重排不叠加旧行() {
        val status = bareStatusText()
        applyEmptyTextLines(status, "甲行\n乙行\n丙行", 4000, stubMeasure)
        applyEmptyTextLines(status, "单行", 4000, stubMeasure)
        assertEquals("重排前必须清空旧 Fragment，否则旧文案会残留在空态上", 1, paintedLineCount(status))
    }

    @Test
    fun test空文案不抛异常() {
        val status = bareStatusText()
        applyEmptyTextLines(status, "", 400, stubMeasure)
        assertTrue("空文案不得抛异常，也不得留下裸换行", paintedLineCount(status) <= 1)
    }

    // ---------------- P1-4 摩擦 #1：空面板起步引导内容守卫 ----------------

    /** 从仓内任一工作目录向上定位仓库根（仿 SettingsRealityGuardTest 的 repoRoot 惯例）。 */
    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")!!)
        repeat(5) {
            val candidate = File(dir, rel)
            if (candidate.exists()) return candidate
            dir = dir!!.parentFile
        }
        error("repo file not found: $rel (cwd=${System.getProperty("user.dir")})")
    }

    @Test
    fun test空态起步引导三径齐全() {
        // P1-4 摩擦 #1：空面板起步引导必须三径同段——自动捕捉 / 手动粘贴 / 增强捕获发现径
        //（增强捕获是插件「运行即采集」的头条通道，空态只提两径时新用户无从得知其存在；
        //  引导不弹窗，弹窗克制红线不动 EnhancedCaptureConfirmDialog）。摘掉任一径本用例转红。
        val zhLine = Regex("^log\\.panel\\.empty=(.*)$", RegexOption.MULTILINE)
            .find(repoFile("src/main/resources/messages/MpSqlVisualizerBundle_zh.properties").readText())!!
            .groupValues[1]
        val enLine = Regex("^log\\.panel\\.empty=(.*)$", RegexOption.MULTILINE)
            .find(repoFile("src/main/resources/messages/MpSqlVisualizerBundle.properties").readText())!!
            .groupValues[1]
        assertTrue("zh 引导缺「捕捉」自动捕获径：$zhLine", zhLine.contains("捕捉"))
        assertTrue("zh 引导缺「日志转 SQL」粘贴径：$zhLine", zhLine.contains("日志转 SQL"))
        assertTrue("zh 引导缺「增强捕获」发现径（P1-4 补第三径，摘掉即红）：$zhLine", zhLine.contains("增强捕获"))
        assertTrue("en 引导缺 Capture 自动径：$enLine", enLine.contains("Capture"))
        assertTrue("en 引导缺 paste 粘贴径：$enLine", enLine.contains("paste"))
        assertTrue("en 引导缺 Enhanced Capture 发现径：$enLine", enLine.contains("Enhanced Capture"))
    }
}
