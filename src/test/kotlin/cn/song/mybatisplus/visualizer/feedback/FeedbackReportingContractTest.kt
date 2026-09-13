package cn.song.mybatisplus.visualizer.feedback

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反馈链路轻量契约单测（T-TestGuard-07 零覆盖补测：feedback/ErrorReportDialog 与
 * FeedbackReporting 此前零覆盖）。
 *
 * 弹窗/提交的网络与 UI 形态不可 headless 验证，这里锁定可纯测的契约面：
 * 分类清单与 bundle 文案对齐（缺键会原样显示键名）、IDE 版本读取兜底不抛。
 */
class FeedbackReportingContractTest {

    @Test
    fun `分类清单非空且无重复`() {
        assertTrue("分类清单不应为空", ErrorReportDialog.CATEGORIES.isNotEmpty())
        assertEquals("分类不应重复", ErrorReportDialog.CATEGORIES.size, ErrorReportDialog.CATEGORIES.toSet().size)
    }

    @Test
    fun `每个分类都有bundle文案`() {
        for (slug in ErrorReportDialog.CATEGORIES) {
            val key = "feedback.category.$slug"
            assertNotEquals("分类 $slug 缺 bundle 文案（$key 会原样显示键名）", key, MpSqlVisualizerBundle.message(key))
        }
    }

    @Test
    fun `IDE版本读取_兜底不抛异常`() {
        // 平台测试 worker 内 ApplicationInfo 可用（返回 IDE 全名）；纯环境 runCatching 兜底
        // null（提交载荷里 ideVersion 可空）——两者皆合法，契约是「永不抛异常、不返回空白串」
        val version = FeedbackReporting.ideVersion()
        assertTrue("ideVersion 应为 null 或非空平台名", version == null || version.isNotBlank())
    }
}
