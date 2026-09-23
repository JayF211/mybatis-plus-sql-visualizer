package cn.song.mybatisplus.visualizer.mcp

import com.intellij.mcpserver.McpToolFilterProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * T-McpGateDynamicFilter（O-68）过滤提供者回归测试。
 *
 * 真机实证（2026-09-23 IU 262.10968，idea.log 14:01:46 / 14:02:52 两次 SEVERE）：平台
 * 「公开的工具」设置页在无客户端会话时以 **clientInfo=null** 调用 applyFilters——若桩/实现把
 * 参数标成非空，方法入口的 Intrinsics 判空即抛 NPE，沿 getMcpToolsFiltered →
 * McpToolFilterConfigurable.createComponent 上抛，设置页整面空白。
 *
 * 本用例以 null 实参直调三个「环境型」参数；测试环境命中桩哨兵（IllegalStateException "stub…"）
 * 属预期（updateState 桩体未接平台真类），**唯独不允许出现空源性 NPE**。
 * 注：桩参数空源性无法用 javap 判读（藏在 Kotlin @Metadata）——2026-09-23 教训入桩头注释。
 */
class SqlVisualizerToolGateFilterProviderTest : BasePlatformTestCase() {

    fun test空clientInfo与会话选项的枚举路径不得抛空源性NPE() {
        val provider = SqlVisualizerToolGateFilterProvider()
        val context = McpToolFilterProvider.McpToolFilterContext(emptyList())
        try {
            provider.applyFilters(context, clientInfo = null, sessionOptions = null, invocationMode = null)
        } catch (e: NullPointerException) {
            if (e.message?.contains("non-null") == true) {
                fail("clientInfo/sessionOptions/invocationMode 必须可空（平台无会话枚举路径传 null，真机 NPE 实证）")
            }
            throw e
        } catch (e: IllegalStateException) {
            assertTrue("测试环境命中桩哨兵属预期：${e.message}", e.message.orEmpty().startsWith("stub"))
        }
    }
}
