package cn.song.mybatisplus.visualizer.mcp

import com.intellij.mcpserver.McpToolFilterProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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
 * 经反射取桥的 handler、以 null 环境实参直调 applyFilters 分发：测试环境命中桩哨兵
 * （IllegalStateException "stub…"——updateState 桩体未接平台真类）属预期，**唯独不允许出现
 * 空源性 NPE**——代理分发对 null clientInfo/invocationMode 的容忍度由此钉住。
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
        val context = McpToolFilterProvider.McpToolFilterContext(emptyList())
        try {
            bridgeHandler().invoke(ANY_PROXY, applyFiltersMethod(), arrayOf(context, null, null, null))
        } catch (e: NullPointerException) {
            if (e.message?.contains("non-null") == true) {
                fail("分发路径必须容忍 null 环境参数（平台无会话枚举路径传 null，真机 NPE 实证）")
            }
            throw e
        } catch (e: IllegalStateException) {
            assertTrue("测试环境命中桩哨兵属预期：${e.message}", e.message.orEmpty().startsWith("stub"))
        }
    }

    /** applyFilters 反射 Method（桩接口上取签名即可，handler 按名分发不看声明类）。 */
    private fun applyFiltersMethod(): java.lang.reflect.Method =
        McpToolFilterProvider::class.java.methods.first { it.name == "applyFilters" }

    private companion object {
        /** handler.invoke 的 proxy 形参只用于 equals 分支，任意实现即可。 */
        val ANY_PROXY = ProxyOf(McpToolFilterProvider::class.java)

        fun ProxyOf(iface: Class<*>) =
            java.lang.reflect.Proxy.newProxyInstance(McpGateFilterEpBridgeProxyTest::class.java.classLoader, arrayOf(iface)) { _, _, _ -> null }
    }
}
