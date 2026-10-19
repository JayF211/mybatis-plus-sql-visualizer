package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-UI030：结构化 EXPLAIN 回退引导纯逻辑单测。
 *
 * 覆盖两组：
 *  1. 数据源绑定等待拍（无已绑数据源 → 直接弹既有选择器后，绑定/取消/超时的收口）；
 *  2. 开关关闭时是否值得提示「启用结构化并重试」（TEXT/XML/Community 不提示）。
 *
 * 面板接线（真的弹 prepareConsole、真的挂通知动作）由 `ExplainPanelWiringGuardTest` 源码
 * 扫描钉住——判定与接线分开回归，避免一边绿灯掩盖另一边死代码。
 */
class ExplainFallbackGuidanceTest {

    // ------------------------------------------------------------------
    // 第一组：数据源绑定等待拍
    // ------------------------------------------------------------------

    @Test
    fun `已绑定_立即重试`() {
        assertEquals(
            ExplainFallbackGuidance.DataSourceBindTick.RETRY,
            ExplainFallbackGuidance.dataSourceBindTick(
                bound = true, prepareInFlight = true, timeoutReached = false,
            ),
        )
    }

    @Test
    fun `已绑定优先于准备链收尾`() {
        // 准备链已不在飞也照样重试——数据源可用才是唯一判据，收尾时序不能吞掉已到手的绑定
        assertEquals(
            ExplainFallbackGuidance.DataSourceBindTick.RETRY,
            ExplainFallbackGuidance.dataSourceBindTick(
                bound = true, prepareInFlight = false, timeoutReached = true,
            ),
        )
    }

    @Test
    fun `准备链在飞且未绑定_继续等待`() {
        assertEquals(
            ExplainFallbackGuidance.DataSourceBindTick.WAIT,
            ExplainFallbackGuidance.dataSourceBindTick(
                bound = false, prepareInFlight = true, timeoutReached = false,
            ),
        )
    }

    @Test
    fun `用户取消选择_准备链终结即放弃且不再弹第二次`() {
        // 取消会复位单飞位（PrepareSingleFlightGate.abort）——下一拍 inFlight=false 且未绑定
        // 必须收口为 GIVE_UP，绝不能回到「再弹一次选择器」的分支（真机循环弹窗的根因形态）
        assertEquals(
            ExplainFallbackGuidance.DataSourceBindTick.GIVE_UP,
            ExplainFallbackGuidance.dataSourceBindTick(
                bound = false, prepareInFlight = false, timeoutReached = false,
            ),
        )
    }

    @Test
    fun `等待超时_放弃`() {
        assertEquals(
            ExplainFallbackGuidance.DataSourceBindTick.GIVE_UP,
            ExplainFallbackGuidance.dataSourceBindTick(
                bound = false, prepareInFlight = true, timeoutReached = true,
            ),
        )
    }

    @Test
    fun `入口有准备链在飞_只等待不另起新链`() {
        assertFalse(ExplainFallbackGuidance.shouldPromptForDataSource(prepareInFlight = true))
        assertTrue(ExplainFallbackGuidance.shouldPromptForDataSource(prepareInFlight = false))
    }

    // ------------------------------------------------------------------
    // 第二组：开关关闭时的引导可见性
    // ------------------------------------------------------------------

    @Test
    fun `开关关闭且方言有结构化面_提示启用`() {
        assertTrue(
            ExplainFallbackGuidance.shouldOfferStructuredEnable(
                enabled = false, format = ExplainOutputFormat.TABLE, databaseAvailable = true,
            ),
        )
        assertTrue(
            ExplainFallbackGuidance.shouldOfferStructuredEnable(
                enabled = false, format = ExplainOutputFormat.JSON, databaseAvailable = true,
            ),
        )
    }

    @Test
    fun `开关已开_不再提示`() {
        assertFalse(
            ExplainFallbackGuidance.shouldOfferStructuredEnable(
                enabled = true, format = ExplainOutputFormat.TABLE, databaseAvailable = true,
            ),
        )
    }

    @Test
    fun `TEXT与XML与未知格式_开启也没用故不提示`() {
        // 不硬解析红线：TEXT/XML 即便开关打开也只走原生控制台，提示只会变噪音
        for (format in listOf(ExplainOutputFormat.TEXT, ExplainOutputFormat.XML, null)) {
            assertFalse(
                "format=$format 不应提示启用结构化",
                ExplainFallbackGuidance.shouldOfferStructuredEnable(
                    enabled = false, format = format, databaseAvailable = true,
                ),
            )
        }
    }

    @Test
    fun `无Database插件_不提示`() {
        assertFalse(
            ExplainFallbackGuidance.shouldOfferStructuredEnable(
                enabled = false, format = ExplainOutputFormat.TABLE, databaseAvailable = false,
            ),
        )
    }
}
