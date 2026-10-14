package cn.song.mybatisplus.visualizer.mcp

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * T-McpGateDynamicFilter（O-68）→ T-MarketCompatProxy 过滤链回归测试。
 *
 * 真机实证（2026-09-23 IU 262.10968，idea.log 两次 SEVERE）：平台「公开的工具」设置页在无
 * 客户端会话时以 **clientInfo=null** 调 applyFilters——入口判空即 NPE，设置页整面空白。
 *
 * T-MarketCompatProxy 后实现形态改为**动态代理**（[McpGateFilterEpBridge] 全反射分发，字节码
 * 零平台类型引用），空源性风险**结构性消除**（handler 对 args 判空 + runCatching 兜底）。本用例
 * 经反射取桥的 handler、以 null 环境实参直调 applyFilters 分发。测试使用本地同签名
 * context 替身，不静态链接仅在新平台运行时提供的 MCP 接口；验证 updateState 真正被调用，
 * 且 null clientInfo/invocationMode 不导致空源性 NPE。
 */
class McpGateFilterEpBridgeProxyTest : BasePlatformTestCase() {

    /** 反射调桥对象的私有 providerHandler() 拿分发体（测试为防回归需要直达 InvocationHandler）。
     *  Kotlin object 的实例方法 invoke 时 receiver 必须是单例实例（INSTANCE），传 Class 对象即
     *  IllegalArgumentException: object is not an instance of declaring class（首跑实测）。 */
    private fun bridgeHandler(): java.lang.reflect.InvocationHandler {
        val bridgeClass = McpGateFilterEpBridge::class.java
        val instance = bridgeClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        val m = bridgeClass.getDeclaredMethod("providerHandler").apply { isAccessible = true }
        return m.invoke(instance) as java.lang.reflect.InvocationHandler
    }

    fun test空clientInfo与会话选项的分发路径不得抛空源性NPE() {
        val context = FakeFilterContext()
        try {
            bridgeHandler().invoke(Any(), FakeFilter::class.java.methods.first { it.name == "applyFilters" },
                arrayOf(context, null, null, null))
        } catch (e: NullPointerException) {
            if (e.message?.contains("non-null") == true) {
                fail("分发路径必须容忍 null 环境参数（平台无会话枚举路径传 null，真机 NPE 实证）")
            }
            throw e
        }
        assertTrue("分发必须调用 context.updateState", context.called)
        assertFalse("过滤状态应关闭默认路由", context.enabled ?: true)
    }

    interface FakeFilter {
        fun applyFilters(context: FakeFilterContext?, clientInfo: Any?, invocationMode: Any?, options: Any?)
    }

    class FakeFilterContext {
        var called = false
        var enabled: Boolean? = null

        fun updateState(enabled: Boolean?, routerOnly: Boolean?, predicate: (Any?) -> Boolean) {
            called = true
            this.enabled = enabled
            check(routerOnly == null)
            check(!predicate(null))
        }
    }
}
