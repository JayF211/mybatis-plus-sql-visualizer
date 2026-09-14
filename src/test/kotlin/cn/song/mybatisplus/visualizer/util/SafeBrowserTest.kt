package cn.song.mybatisplus.visualizer.util

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SafeBrowser 沙盒短路守卫（T-TestGuard-03）。
 *
 * BrowserUtil.browse 直接 ShellExecute 拉起宿主机浏览器（pitfall-test-sandbox-browser-leak），
 * SafeBrowser 在单元测试环境（无平台 Application，或 isUnitTestMode=true）必须静默跳过——
 * 本测试环境恰好就是「无 Application」形态，browse 不抛异常即代表没有触达系统浏览器。
 */
class SafeBrowserTest {

    @Test
    fun `单元测试环境browse静默跳过_不抛异常不弹浏览器`() {
        // 纯 JUnit 无平台 Application；若短路失效，BrowserUtil 会在测试期拉起宿主机默认浏览器
        SafeBrowser.browse("https://sql-visualizer.jvtools.cn/about/feedback")
        SafeBrowser.browse("https://example.invalid/never-opened")
    }

    @Test
    fun `SafeBrowser存在性守卫`() {
        // 收口点必须存在且可空参调用（后续新浏览器调用一律经此处）
        assertNotNull(SafeBrowser::class.java)
        // browse(String) 收口方法必须在
        assertTrue(
            SafeBrowser::class.java.declaredMethods.any {
                it.name == "browse" && it.parameterTypes.size == 1
            },
        )
    }
}
