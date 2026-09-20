package cn.song.mybatisplus.visualizer.onboarding

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.messages.MessageBusConnection
import java.lang.reflect.Proxy

/**
 * T-PS5-15：引导监听器弹后注销守护测试（平台用例——真实 fixture 文件 + 连接替身探针）。
 *
 * 缺陷背景（第八轮报告发现 16）：`OnboardingAnchorTip` 的 FileEditorManagerListener 连接残留至
 * 项目关闭，引导已弹过（一次性语义已耗尽）后每次文件打开仍付 `shouldProbe`/`isProbeTarget`
 * 两次布尔判断。现于一次性展示权领取点（单飞点）注销连接。
 *
 * 判据用**连接替身**（MessageBusConnection 接口代理）而非总线投递计数：替身直调
 * [OnboardingAnchorTip.onFileOpened] 的注销路径，断言确定性不受跨用例残留订阅影响。
 */
class OnboardingAnchorTipListenerTest : BasePlatformTestCase() {

    /** 连接替身：记录 disconnect/dispose 是否被调用。 */
    private class ConnectionProbe {
        var disconnected = false

        fun connection(): MessageBusConnection = Proxy.newProxyInstance(
            MessageBusConnection::class.java.classLoader,
            arrayOf(MessageBusConnection::class.java),
        ) { p, method, _ ->
            when (method.name) {
                "disconnect", "dispose" -> {
                    disconnected = true
                    null
                }

                "toString" -> "ConnectionProbe"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> false
                else -> null
            }
        } as MessageBusConnection
    }

    override fun setUp() {
        super.setUp()
        val state = PluginSettings.getInstance().state
        state.onboardingAnchorTipEnabled = true
        state.onboardingAnchorTipShown = false
    }

    override fun tearDown() {
        val state = PluginSettings.getInstance().state
        state.onboardingAnchorTipEnabled = true
        state.onboardingAnchorTipShown = false
        super.tearDown()
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
        fail("等待超时：$what")
    }

    fun test弹过之后连接注销() {
        val probe = ConnectionProbe()
        val file = myFixture.addFileToProject(
            "src/OnboardingProbe.java",
            "class OnboardingProbe { QueryWrapper<User> w; }",
        ).virtualFile

        OnboardingAnchorTip.onFileOpened(project, file, probe.connection())
        awaitTrue("引导展示权被领取（后台扫描 + 启发式命中）") {
            PluginSettings.getInstance().state.onboardingAnchorTipShown
        }
        awaitTrue("弹过即注销连接（单飞点）") { probe.disconnected }
    }

    fun test未弹过路径不注销() {
        val probe = ConnectionProbe()
        // 启发式不命中的文件：探测跑完但未领取展示权
        val plain = myFixture.addFileToProject("src/Plain.java", "class Plain { int a = 1; }").virtualFile

        OnboardingAnchorTip.onFileOpened(project, plain, probe.connection())
        Thread.sleep(300) // 等后台探测跑完（无命中即返回）
        assertFalse("未领取展示权（启发式未命中）", PluginSettings.getInstance().state.onboardingAnchorTipShown)
        assertFalse("未弹过 → 连接保持挂载（后续文件仍可触达）", probe.disconnected)
    }

    fun test开关关闭路径不注销() {
        val state = PluginSettings.getInstance().state
        state.onboardingAnchorTipEnabled = false
        val probe = ConnectionProbe()
        val file = myFixture.addFileToProject(
            "src/Disabled.java",
            "class Disabled { QueryWrapper<User> w; }",
        ).virtualFile

        OnboardingAnchorTip.onFileOpened(project, file, probe.connection())
        Thread.sleep(200)
        assertFalse("开关关 → 全程静默，连接不注销", probe.disconnected)
        assertFalse("开关关 → 展示权不领取", state.onboardingAnchorTipShown)
    }
}
