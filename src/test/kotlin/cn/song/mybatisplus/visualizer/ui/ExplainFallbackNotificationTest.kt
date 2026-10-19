package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-UI030：结构化开关关闭通知的**动作可见性**测试（纯逻辑，无真机事件）。
 *
 * 覆盖：
 *  1. 通知恒挂且只挂一个「启用结构化并重试」动作；
 *  2. 动作文案与 bundle 同 key（改文案不脱钩）；
 *  3. 点击动作确实触发「启用并重试」回调（通知动作本身只能由平台事件驱动，这里测其纯逻辑载体）。
 */
class ExplainFallbackNotificationTest {

    @Test
    fun `开关关闭通知挂启用并重试动作`() {
        val notification = buildStructuredExplainDisabledNotification {}
        assertEquals("通知应恰好挂一个「启用结构化并重试」动作", 1, notification.actions.size)
        assertEquals(
            MpSqlVisualizerBundle.message("panel.explain.structured.enableAndRetry"),
            notification.actions.single().templatePresentation.text,
        )
        assertEquals(
            MpSqlVisualizerBundle.message("panel.explain.structured.disabled"),
            notification.content,
        )
    }

    @Test
    fun `动作点击触发启用并重试回调`() {
        var fired = false
        structuredExplainDisabledActions { fired = true }.single().run()
        assertTrue("点击「启用结构化并重试」必须触发启用+重试回调", fired)
    }

    @Test
    fun `取消数据源选择文案不再声称已回退原生控制台`() {
        // 用户取消选择时并没有执行任何语句——如实说「未执行」并给出下一步，
        // 不能沿用旧文案「已回退原生控制台」（会让人以为计划已经跑过了）
        val declined = MpSqlVisualizerBundle.message("panel.explain.structured.noDataSourceDeclined")
        assertTrue(declined.contains("未执行"))
        assertTrue(!declined.contains("已回退"))
    }
}
