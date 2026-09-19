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
 *
 * T-RA-03 追加守卫：重放丢弃 / EP 短路两个计数（`SqlLogConsoleBridge` 侧原本只自增、无人读）必须在
 * 诊断行可见且数值正确，同时零值不得出现——否则「有行但被守卫/EP 吃掉」与「控制台没输出」
 * 在面板上仍是同一种沉默，正是本次审计要封的口。
 */
class CaptureDiagnosticsTest {

    private fun diag(
        capturing: Boolean = true,
        waiting: Boolean = false,
        entries: Int = 0,
        consoles: Int = 1,
        lines: Long = 0L,
        enhanced: String? = null,
        dropped: Long = 0L,
        shortCircuited: Long = 0L,
    ) = CaptureDiagnostics(capturing, waiting, entries, consoles, lines, enhanced, dropped, shortCircuited)

    @Test
    fun test未捕捉或无诊断必要时返回null() {
        assertNull("未捕捉 → 无需诊断行", CaptureDiagnostics.captureDiagnosticsText(diag(capturing = false)))
        assertNull("已有条目 → 无需诊断行", CaptureDiagnostics.captureDiagnosticsText(diag(entries = 3)))
    }

    @Test
    fun test捕捉中零条目输出通道状态与计数() {
        val text = CaptureDiagnostics.captureDiagnosticsText(diag(consoles = 2, lines = 150L, enhanced = "已挂载 pid=123"))!!
        assertTrue("应含「捕捉中」：$text", text.contains("捕捉中"))
        assertTrue("应含增强通道状态：$text", text.contains("已挂载 pid=123"))
        assertTrue("应含控制台挂载数：$text", text.contains("2"))
        assertTrue("应含已扫描行数：$text", text.contains("150"))
    }

    @Test
    fun test增强未开启时显示未启用() {
        val text = CaptureDiagnostics.captureDiagnosticsText(diag(enhanced = null))!!
        assertTrue("增强未开启应显式标注未启用：$text", text.contains("未启用"))
    }

    @Test
    fun test等待控制台时提示等待项目运行() {
        val text = CaptureDiagnostics.captureDiagnosticsText(diag(waiting = true, consoles = 0))!!
        assertTrue("应提示等待项目运行：$text", text.contains("等待项目运行"))
    }

    @Test
    fun test控制台已挂载但无输出时提示去运行应用() {
        val text = CaptureDiagnostics.captureDiagnosticsText(diag(waiting = false, consoles = 1, lines = 0L))!!
        assertTrue("应提示控制台暂无输出：$text", text.contains("控制台暂无输出"))
    }

    @Test
    fun test控制台有输出但无SQL时指向日志级别配置() {
        val text = CaptureDiagnostics.captureDiagnosticsText(diag(consoles = 1, lines = 4200L))!!
        assertTrue("应指出有输出但未见 SQL 日志：$text", text.contains("未见 MyBatis SQL 日志"))
        assertTrue("应给出可执行下一步（日志级别/增强捕获）：$text", text.contains("debug"))
    }

    @Test
    fun test三档提示互不串味() {
        val waiting = CaptureDiagnostics.captureDiagnosticsText(diag(waiting = true, consoles = 0))!!
        val noOutput = CaptureDiagnostics.captureDiagnosticsText(diag(waiting = false, lines = 0L))!!
        val noSql = CaptureDiagnostics.captureDiagnosticsText(diag(waiting = false, lines = 10L))!!
        assertTrue("等待档不得出现「控制台暂无输出」", !waiting.contains("控制台暂无输出"))
        assertTrue("无输出档不得出现「未见 MyBatis SQL 日志」", !noOutput.contains("未见 MyBatis SQL 日志"))
        assertTrue("有输出档不得出现「等待项目运行」", !noSql.contains("等待项目运行"))
    }

    // ---------- T-RA-03：丢弃/短路计数出口 ----------

    @Test
    fun test计数为零时不显示丢弃与短路() {
        val text = CaptureDiagnostics.captureDiagnosticsText(diag(consoles = 1, lines = 30L))!!
        assertTrue("零丢弃不得出现重放守卫计数：$text", !text.contains("重放守卫"))
        assertTrue("零短路不得出现 EP 计数：$text", !text.contains("EP 通道"))
    }

    @Test
    fun test计数非零时显示且数值正确() {
        val text = CaptureDiagnostics.captureDiagnosticsText(diag(lines = 30L, dropped = 7L, shortCircuited = 128L))!!
        assertTrue("应显示重放丢弃行数 7：$text", text.contains("重放守卫已丢弃 7 行"))
        assertTrue("应显示 EP 短路行数 128：$text", text.contains("EP 通道已短路 128 行"))
        assertTrue("两个计数不得互相串值：$text", !text.contains("重放守卫已丢弃 128"))
    }

    @Test
    fun test仅一档非零时另一档不出现() {
        val onlyDropped = CaptureDiagnostics.captureDiagnosticsText(diag(lines = 5L, dropped = 2L))!!
        assertTrue("仅丢弃非零：应显示丢弃：$onlyDropped", onlyDropped.contains("重放守卫已丢弃 2 行"))
        assertTrue("仅丢弃非零：不得出现 EP 计数：$onlyDropped", !onlyDropped.contains("EP 通道"))

        val onlyShort = CaptureDiagnostics.captureDiagnosticsText(diag(lines = 5L, shortCircuited = 3L))!!
        assertTrue("仅短路非零：应显示短路：$onlyShort", onlyShort.contains("EP 通道已短路 3 行"))
        assertTrue("仅短路非零：不得出现丢弃计数：$onlyShort", !onlyShort.contains("重放守卫"))
    }

    @Test
    fun test计数不干扰三档提示选择() {
        // 计数与提示档位正交：有行 + 有丢弃时仍走「有输出但未见 SQL」档，不得退化成「无输出」档
        val text = CaptureDiagnostics.captureDiagnosticsText(diag(lines = 30L, dropped = 1L, shortCircuited = 1L))!!
        assertTrue("提示档位应由行数决定，不被计数改写：$text", text.contains("未见 MyBatis SQL 日志"))
        assertTrue("不得出现无输出档提示：$text", !text.contains("控制台暂无输出"))
    }
}
