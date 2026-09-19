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
 *
 * k2j 移植注：生产为 Java，SafeBrowser 从 Kotlin `object`（单例）改为 `final` 工具类 +
 * 私有构造器 + 静态方法——存在性守卫相应从「objectInstance != null」改为「类可加载且
 * 静态 browse(String) 在位、构造器私有（无实例入口）」，防「收口点被删/被改成实例 API」。
 */
class SafeBrowserTest {

    @Test
    fun `无平台Application环境判定为测试环境_短路成立`() {
        // 纯 JUnit 无平台 Application；isTestEnvironment 必为 true，browse 静默跳过不触达浏览器
        assertTrue(SafeBrowser.isTestEnvironment())
    }

    @Test
    fun `SafeBrowser存在性守卫`() {
        // 收口点必须存在且可空参调用（后续新浏览器调用一律经此处）。
        // Java 侧等价形态：public static void browse(String) 在位 + 构造器私有（无实例化入口）。
        val browse = SafeBrowser::class.java.getDeclaredMethod("browse", String::class.java)
        assertTrue("SafeBrowser 须提供静态 browse(String) 收口方法", java.lang.reflect.Modifier.isStatic(browse.modifiers))
        assertTrue("SafeBrowser.browse 须为 public（跨包调用）", java.lang.reflect.Modifier.isPublic(browse.modifiers))
        val ctors = SafeBrowser::class.java.declaredConstructors
        assertTrue("SafeBrowser 须声明构造器（Java 工具类形态）", ctors.isNotEmpty())
        assertTrue(
            "SafeBrowser 构造器须全部私有（Kotlin object 单例语义的 Java 等价：不允许外部实例化）",
            ctors.all { java.lang.reflect.Modifier.isPrivate(it.modifiers) },
        )
    }

    @Test
    fun `判定函数可重复调用且结果稳定`() {
        assertTrue(SafeBrowser.isTestEnvironment())
        assertTrue(SafeBrowser.isTestEnvironment())
        assertFalse(SafeBrowser.isTestEnvironment() xor SafeBrowser.isTestEnvironment())
    }
}
