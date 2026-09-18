package cn.song.mybatisplus.visualizer.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SafeBrowser 沙盒短路守卫（T-TestGuard-03，T-BrowserLeak2 断言换形）。
 *
 * BrowserUtil.browse 直接 ShellExecute 拉起宿主机浏览器（pitfall-test-sandbox-browser-leak），
 * 守卫是 fail-safe 默认拦：无平台 Application（本测试环境形态）或 isUnitTestMode=true 一律短路。
 * 旧版直调真实 URL 依赖「不抛异常」做验证不可观测——守卫放行时每次跑测试都真开浏览器
 * （example.invalid/never-opened 被真机 Edge 打开实证，2026-09-18），现改为直接断言判定结果。
 */
class SafeBrowserTest {

    @Test
    fun `无平台Application环境判定为测试环境_短路成立`() {
        // 纯 JUnit 无平台 Application；isTestEnvironment 必为 true，browse 静默跳过不触达浏览器
        assertTrue(SafeBrowser.isTestEnvironment())
    }

    @Test
    fun `SafeBrowser存在性守卫`() {
        // 收口点必须存在且可空参调用（后续新浏览器调用一律经此处）
        assertTrue(SafeBrowser::class.objectInstance != null)
    }

    @Test
    fun `判定函数可重复调用且结果稳定`() {
        assertTrue(SafeBrowser.isTestEnvironment())
        assertTrue(SafeBrowser.isTestEnvironment())
        assertFalse(SafeBrowser.isTestEnvironment() xor SafeBrowser.isTestEnvironment())
    }
}
