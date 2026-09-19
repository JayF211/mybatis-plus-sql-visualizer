package cn.song.mybatisplus.visualizer.log.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-CaptureTruth T-CT-01：增强捕获状态行纯函数五态测试（运行期，非源码扫描）。
 *
 * 立项动因回归守卫：原 `statusSummary()` 把「开关开着但从未授权」与「开着且正常」一起收敛成
 * null（设置页渲染成空白而复选框仍勾选），用户据此判断「增强是开着的」，实际一条 SQL 都捕不到。
 * 本测试直接钉住「未授权两态必须非空且带指引」。
 *
 * bundle 在无平台服务环境下兜底中文（见 [cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle]
 * KDoc），故断言用中文特征串；若文案键缺失，message() 返回 key 本身，断言即失败（防「键被删」假绿）。
 */
class AgentStatusTextTest {

    private fun snap(
        enabled: Boolean = true,
        confirmed: Boolean = false,
        cooldownMs: Long = 0L,
        livePids: List<Long> = emptyList(),
        everAttached: Boolean = false,
        heartbeatExpired: Boolean = false,
    ) = AgentStatusSnapshot(enabled, confirmed, cooldownMs, livePids, everAttached, heartbeatExpired)

    @Test
    fun test关闭态无叙事() {
        assertNull("增强关闭时状态行应为空（复选框未勾选，无需叙事）", AgentManagerKt.agentStatusText(snap(enabled = false)))
    }

    @Test
    fun test有活会话显示已挂载pid() {
        val text = AgentManagerKt.agentStatusText(snap(confirmed = true, livePids = listOf(11L, 22L)))
        assertTrue("应含「已挂载」：$text", text!!.contains("已挂载"))
        assertTrue("应含 pid=11：$text", text.contains("pid=11"))
        assertTrue("应含 pid=22：$text", text.contains("pid=22"))
    }

    @Test
    fun test未授权冷却中给出剩余小时与恢复指引() {
        val text = AgentManagerKt.agentStatusText(snap(cooldownMs = 24L * 60 * 60 * 1000))
        assertTrue("未授权态不得为 null（T-CaptureTruth 缺陷核心）：$text", text != null)
        assertTrue("应含「未授权注入」：$text", text!!.contains("未授权注入"))
        assertTrue("应含剩余小时 24：$text", text.contains("24"))
        assertTrue("应含恢复指引「重新授权」：$text", text.contains("重新授权"))
    }

    @Test
    fun test冷却剩余不足一小时也按一小时显示() {
        val text = AgentManagerKt.agentStatusText(snap(cooldownMs = 1L))
        assertTrue("不足 1 小时应向上取整为 1：$text", text!!.contains("1"))
        assertTrue("应含「冷却中」：$text", text.contains("冷却中"))
    }

    @Test
    fun test未授权无冷却提示下次会询问() {
        val text = AgentManagerKt.agentStatusText(snap(cooldownMs = 0L))
        assertTrue("未授权无冷却态不得为 null：$text", text != null)
        assertTrue("应含「未授权注入」：$text", text!!.contains("未授权注入"))
        assertTrue("应提示下次启动主配置会询问：$text", text.contains("下次启动主配置"))
    }

    @Test
    fun test已授权从未挂载提示等待进程() {
        val text = AgentManagerKt.agentStatusText(snap(confirmed = true, everAttached = false))
        assertTrue("应含「已授权」：$text", text!!.contains("已授权"))
        assertTrue("应提示等待应用进程启动：$text", text.contains("等待应用进程启动"))
    }

    @Test
    fun test已授权曾挂载后中断显示回落() {
        val text = AgentManagerKt.agentStatusText(snap(confirmed = true, everAttached = true))
        assertEquals("连接中断，已回落控制台捕获", text)
    }

    @Test
    fun test心跳过期回落文案与断连区分() {
        val heartbeat = AgentManagerKt.agentStatusText(snap(confirmed = true, everAttached = true, heartbeatExpired = true))
        val disconnect = AgentManagerKt.agentStatusText(snap(confirmed = true, everAttached = true))
        assertTrue("心跳过期应明示心跳中断：$heartbeat", heartbeat!!.contains("心跳中断"))
        assertFalse("心跳态不得与断连态同文案：$heartbeat", heartbeat == disconnect)
    }

    @Test
    fun test五态文案互不重复() {
        val texts = listOf(
            AgentManagerKt.agentStatusText(snap(enabled = false)),
            AgentManagerKt.agentStatusText(snap(confirmed = true, livePids = listOf(1L))),
            AgentManagerKt.agentStatusText(snap(cooldownMs = 3_600_000L)),
            AgentManagerKt.agentStatusText(snap(cooldownMs = 0L)),
            AgentManagerKt.agentStatusText(snap(confirmed = true, everAttached = false)),
            AgentManagerKt.agentStatusText(snap(confirmed = true, everAttached = true)),
            AgentManagerKt.agentStatusText(snap(confirmed = true, everAttached = true, heartbeatExpired = true)),
        )
        val nonNull = texts.filterNotNull()
        assertEquals("五个非空态文案应互不相同（防两态收敛成同一句）", nonNull.size, nonNull.toSet().size)
    }
}
