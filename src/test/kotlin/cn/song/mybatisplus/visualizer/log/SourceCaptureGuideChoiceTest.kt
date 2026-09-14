package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

/**
 * T-JumpCapture：[SourceCaptureGuide] 引导编排的纯函数与文案落地测试。
 *
 * 弹框本体（Messages.showDialog 模态）headless 不可测，家规「UI 只做编排 + 纯函数单测」：
 *  - [SourceCaptureGuide.resolveChoice] 返回码 → 选择的映射（关闭/负值兜底取消）；
 *  - 引导弹框与注入结果全部键在 bundle 有真实文案（缺键回退键名原样显示 = UI 事故）；
 *  - 实时面板 MissingSource（引导）与 NoSource（MP COUNT 纯提示）语义边界由
 *    SqlLogPanelLiveJumpWithSourceTest 锁定，本测试补键面（noSource 文案已换 MP COUNT 语义）。
 */
class SourceCaptureGuideChoiceTest : BasePlatformTestCase() {

    fun test返回码映射打开设置与立即注入() {
        assertEquals(SourceCaptureGuide.GuideChoice.OpenSettings, SourceCaptureGuide.resolveChoice(0))
        assertEquals(SourceCaptureGuide.GuideChoice.InjectNow, SourceCaptureGuide.resolveChoice(1))
    }

    fun test取消关闭与负值一律Cancel() {
        assertEquals(SourceCaptureGuide.GuideChoice.Cancel, SourceCaptureGuide.resolveChoice(2))
        // 关闭对话框（-1）与任何异常返回码必须兜底取消，不得误触设置/注入
        assertEquals(SourceCaptureGuide.GuideChoice.Cancel, SourceCaptureGuide.resolveChoice(-1))
        assertEquals(SourceCaptureGuide.GuideChoice.Cancel, SourceCaptureGuide.resolveChoice(99))
    }

    fun test引导弹框键有真实文案() {
        for (key in listOf(
            "log.jump.guide.title",
            "log.jump.guide.message",
            "log.jump.guide.openSettings",
            "log.jump.guide.injectNow",
            "log.jump.guide.cancel",
        )) {
            val text = MpSqlVisualizerBundle.message(key)
            assertNotEquals("键 $key 缺 bundle 文案（会原样显示键名）", key, text)
            assertTrue("键 $key 文案为空", text.isNotBlank())
        }
    }

    fun test注入结果键有真实文案() {
        for (key in listOf(
            "log.inject.title",
            "log.inject.alreadyExists",
            "log.inject.unsupported",
            "log.inject.cannotDetect",
            "log.inject.noProject",
        )) {
            val text = MpSqlVisualizerBundle.message(key)
            assertNotEquals("键 $key 缺 bundle 文案（会原样显示键名）", key, text)
            assertTrue("键 $key 文案为空", text.isNotBlank())
        }
        // success 带三参格式化：占位符全部替换、无残留
        val formatted = MpSqlVisualizerBundle.message("log.inject.success", "A.java", "B.java", "com.example")
        assertNotEquals("log.inject.success", formatted)
        assertTrue("success 文案参数未全部就位：$formatted", !formatted.contains("{0}") && !formatted.contains("{1}") && !formatted.contains("{2}"))
    }

    fun test多模块聚合成功键有真实文案() {
        // T-JumpPath-04：successMulti 四参（模块数、模块清单、两个文件名），双语 parity 由守卫测试兜底
        val formatted = MpSqlVisualizerBundle.message(
            "log.inject.successMulti", "2", "mod-a、mod-b", "SqlSourceInterceptor.java", "SqlVisualizerConfig.java",
        )
        assertNotEquals("log.inject.successMulti", formatted)
        assertTrue("successMulti 文案参数未全部就位：$formatted", (0..3).none { formatted.contains("{$it}") })
    }

    fun testMP计数纯提示文案不含拦截器引导语义() {
        // 防回归：NoSource（MP COUNT）文案若被改回「未记录源码位置」类表述，会与 MissingSource 引导混淆
        val text = MpSqlVisualizerBundle.message("log.panel.jumpToSource.noSource")
        assertTrue("COUNT 提示应说明分页插件自动发出：$text", text.contains("COUNT") || text.contains("分页"))
    }
}
