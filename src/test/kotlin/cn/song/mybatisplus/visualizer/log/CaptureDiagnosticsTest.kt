package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-CaptureTruth T-CT-03：零产出诊断行纯函数测试。
 *
 * 守卫目标：捕捉中且零条目时，面板必须给出「卡在哪一环」的可读结论——
 * 等待控制台 / 控制台无输出 / 有输出但没匹配到 SQL 三档提示不可互相串味（现场排障全靠这三档分流）。
 * 有条目或未捕捉时返回 null（面板还原默认空态文案）。
 */
class CaptureDiagnosticsTest {

    private fun diag(
        capturing: Boolean = true,
        waiting: Boolean = false,
        entries: Int = 0,
        consoles: Int = 1,
        lines: Long = 0L,
        enhanced: String? = null,
    ) = CaptureDiagnostics(capturing, waiting, entries, consoles, lines, enhanced)

    @Test
    fun test未捕捉或无诊断必要时返回null() {
        assertNull("未捕捉 → 无需诊断行", captureDiagnosticsText(diag(capturing = false)))
        assertNull("已有条目 → 无需诊断行", captureDiagnosticsText(diag(entries = 3)))
    }

    @Test
    fun test捕捉中零条目输出通道状态与计数() {
        val text = captureDiagnosticsText(diag(consoles = 2, lines = 150L, enhanced = "已挂载 pid=123"))!!
        assertTrue("应含「捕捉中」：$text", text.contains("捕捉中"))
        assertTrue("应含增强通道状态：$text", text.contains("已挂载 pid=123"))
        assertTrue("应含控制台挂载数：$text", text.contains("2"))
        assertTrue("应含已扫描行数：$text", text.contains("150"))
    }

    @Test
    fun test增强未开启时显示未启用() {
        val text = captureDiagnosticsText(diag(enhanced = null))!!
        assertTrue("增强未开启应显式标注未启用：$text", text.contains("未启用"))
    }

    @Test
    fun test等待控制台时提示等待项目运行() {
        val text = captureDiagnosticsText(diag(waiting = true, consoles = 0))!!
        assertTrue("应提示等待项目运行：$text", text.contains("等待项目运行"))
    }

    @Test
    fun test控制台已挂载但无输出时提示去运行应用() {
        val text = captureDiagnosticsText(diag(waiting = false, consoles = 1, lines = 0L))!!
        assertTrue("应提示控制台暂无输出：$text", text.contains("控制台暂无输出"))
    }

    @Test
    fun test控制台有输出但无SQL时指向日志级别配置() {
        val text = captureDiagnosticsText(diag(consoles = 1, lines = 4200L))!!
        assertTrue("应指出有输出但未见 SQL 日志：$text", text.contains("未见 MyBatis SQL 日志"))
        assertTrue("应给出可执行下一步（日志级别/增强捕获）：$text", text.contains("debug"))
    }

    @Test
    fun test三档提示互不串味() {
        val waiting = captureDiagnosticsText(diag(waiting = true, consoles = 0))!!
        val noOutput = captureDiagnosticsText(diag(waiting = false, lines = 0L))!!
        val noSql = captureDiagnosticsText(diag(waiting = false, lines = 10L))!!
        assertTrue("等待档不得出现「控制台暂无输出」", !waiting.contains("控制台暂无输出"))
        assertTrue("无输出档不得出现「未见 MyBatis SQL 日志」", !noOutput.contains("未见 MyBatis SQL 日志"))
        assertTrue("有输出档不得出现「等待项目运行」", !noSql.contains("等待项目运行"))
    }
}
