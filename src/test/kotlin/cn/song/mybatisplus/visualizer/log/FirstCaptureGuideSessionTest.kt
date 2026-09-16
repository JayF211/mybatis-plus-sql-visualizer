package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 首次捕获指路「会话入口」测试（T-UxOnboard T-UX-03）：headless（isUnitTestMode=true，
 * 平台通知 API 测试进程降级路径）下 onEntryAccepted 真实入口不炸且保持静默——
 * 决策矩阵见 FirstCaptureGuideTest（纯函数）。
 */
class FirstCaptureGuideSessionTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            FirstCaptureGuide.resetForTest()
        } finally {
            super.tearDown()
        }
    }

    fun `test_headless入口不炸且保持静默`() {
        assertTrue("测试进程应为 headless（isUnitTestMode）", com.intellij.openapi.application.ApplicationManager.getApplication().isUnitTestMode)
        FirstCaptureGuide.resetForTest()
        val buffer = SqlLogEntryBuffer()
        buffer.addEntry(LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList()))
        // headless：入口应短路——不抛异常、会话记忆不置位（通知零发出）
        FirstCaptureGuide.onEntryAccepted(project, buffer)
        assertFalse("headless 下不应进入已指路态", FirstCaptureGuide.alreadyGuided)
    }
}
