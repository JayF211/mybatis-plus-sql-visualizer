package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 工具集动态壳生成器单测（T-McpDynamicToolset）：不依赖平台/EP，用假接口+假基类直测
 * [McpToolsetDynamicRegistrar.shellBytes] 的字节码生成与 [McpToolsetDynamicRegistrar.ShellClassLoader]
 * 装载——真机端到端（平台工具发现/注解扫描/suspend 契约）在 262 沙盒 runIde 批次验证。
 */
class McpToolsetShellFactoryTest {

    /** 模拟平台 McpToolset 接口（空标记接口即可，壳 implements 的类型解析只要求可达）。 */
    interface FakeToolset

    /** 模拟工具基类：三钩子与业务方法全在此层（壳继承、不生成）。 */
    open class FakeTool {
        fun isEnabled(): Boolean = true
        fun isExperimental(): Boolean = false
        fun alwaysIncluded(): Boolean = false
        fun business(): String = "ok"
    }

    @Test
    fun `壳类生成_装载_实例化与继承语义`() {
        val shellName = "cn.song.mybatisplus.visualizer.mcp.gen.FakeTool" + System.nanoTime()
        val bytes = McpToolsetDynamicRegistrar.shellBytes(
            FakeTool::class.java,
            FakeToolset::class.java,
            shellName,
        )

        // 类版本 V17（major 61，offset 6-7；魔数 0xCAFEBABE 之后 2 字节 minor + 2 字节 major）
        val major = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        assertEquals("壳类字节码版本应为 major 61（Java 17）", 61, major)

        val shell = McpToolsetDynamicRegistrar.ShellClassLoader.define(shellName, bytes)
        assertEquals("壳 extends 业务类（工具方法/三钩子全继承）", FakeTool::class.java, shell.superclass)
        assertTrue(
            "壳 implements 指定接口",
            shell.interfaces.any { it.name == FakeToolset::class.java.name },
        )
        assertTrue("simpleName 应以业务类名开头（唯一后缀为防重 define），实际 ${shell.simpleName}", shell.simpleName.startsWith("FakeTool"))

        val instance = shell.getDeclaredConstructor().newInstance()
        val tool = instance as FakeTool
        assertTrue("三钩子继承可调（接口实现由继承满足）", tool.isEnabled())
        assertTrue(!tool.isExperimental())
        assertTrue(!tool.alwaysIncluded())
        assertEquals("业务方法继承可调", "ok", tool.business())
    }

    /** F29 反向验证探针：同 FQN 二次 define 必 LinkageError（ShellClassLoader 单例、define 不可逆）——
     *  台账（definedShells/epRegistered）防的就是这个：无台账的部分失败重试必撞此形态。 */
    @Test
    fun testF29_同FQN二次define必LinkageError() {
        val shellName = "cn.song.mybatisplus.visualizer.mcp.gen.FakeToolF29" + System.nanoTime()
        val bytes = McpToolsetDynamicRegistrar.shellBytes(
            FakeTool::class.java,
            FakeToolset::class.java,
            shellName,
        )
        McpToolsetDynamicRegistrar.ShellClassLoader.define(shellName, bytes)
        try {
            McpToolsetDynamicRegistrar.ShellClassLoader.define(shellName, bytes)
            fail("同 FQN 二次 define 应抛 LinkageError——该形态即 F29 台账要防的重试炸点")
        } catch (expected: LinkageError) {
            // 预期
        }
    }

    /** F29 台账语义：registerToolset 经 define 缓存复用——同 FQN 两次调用不炸、Class 实例同一（无重 define）。 */
    @Test
    fun testF29_台账复用_二次调用不重define() {
        val fqn = "cn.song.mybatisplus.visualizer.mcp.gen.FakeToolLedger" + System.nanoTime()
        val iface = FakeToolset::class.java
        // 平台 EP 不存在时注册步会失败（runCatching 于 registerOnce 兜底）——本测试直接驱动 registerToolset
        // 的 define/台账面：两次调用第二次必须走缓存（assertSame 钉死无重 define，LinkageError 即红）。
        val first = runCatching { McpToolsetDynamicRegistrar.registerToolsetForTest(FakeTool::class.java, iface, fqn) }
        val cls1 = McpToolsetDynamicRegistrar.definedShells[fqn]
        assertTrue("首次调用应完成 define 入台账", cls1 != null)
        runCatching { McpToolsetDynamicRegistrar.registerToolsetForTest(FakeTool::class.java, iface, fqn) }
        val cls2 = McpToolsetDynamicRegistrar.definedShells[fqn]
        assertSame("二次调用必须复用缓存实例（重 define = LinkageError = F29 形态）", cls1, cls2)
    }
}
