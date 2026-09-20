package cn.song.mybatisplus.visualizer.log.agent

import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * T-PS5-04：推断 URL 轻量持有者测试（普通 JUnit，零平台 fixture——项目用恒等语义代理桩）。
 *
 * 锁定：写读清语义、空值即清条目、**多项目按各自会话 URL 互不覆盖**（解耦不得把项目级语义
 * 降级为全局单值）。持有者不引用 AgentManager（源码守卫见 [AgentUrlDecoupleGuardTest]）。
 */
class AgentUrlRegistryTest {

    /** 恒等语义项目桩：`equals`/`hashCode` 按引用（WeakHashMap 键语义所依赖）。 */
    private fun fakeProject(): Project {
        lateinit var proxy: Project
        proxy = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { p, method, args ->
            when (method.name) {
                "equals" -> args?.isNotEmpty() == true && args[0] === p
                "hashCode" -> System.identityHashCode(p)
                "toString" -> "FakeProject"
                else -> null
            }
        } as Project
        return proxy
    }

    @Test
    fun `写读清语义`() {
        val p = fakeProject()
        try {
            assertNull("未写入 → null（推断回落数据源 URL）", AgentUrlRegistry.lastConnectionUrl(p))
            AgentUrlRegistry.set(p, "jdbc:mysql://localhost:3306/db")
            assertEquals("jdbc:mysql://localhost:3306/db", AgentUrlRegistry.lastConnectionUrl(p))
            AgentUrlRegistry.set(p, null)
            assertNull("显式清空", AgentUrlRegistry.lastConnectionUrl(p))
            AgentUrlRegistry.set(p, "jdbc:postgresql://localhost:5432/db")
            AgentUrlRegistry.set(p, "   ")
            assertNull("空白值视同清空", AgentUrlRegistry.lastConnectionUrl(p))
        } finally {
            AgentUrlRegistry.clearAllForTest()
        }
    }

    @Test
    fun `多项目互不覆盖`() {
        val a = fakeProject()
        val b = fakeProject()
        try {
            AgentUrlRegistry.set(a, "jdbc:mysql://a:3306/x")
            AgentUrlRegistry.set(b, "jdbc:postgresql://b:5432/y")
            assertEquals("jdbc:mysql://a:3306/x", AgentUrlRegistry.lastConnectionUrl(a))
            assertEquals("jdbc:postgresql://b:5432/y", AgentUrlRegistry.lastConnectionUrl(b))
            AgentUrlRegistry.set(a, null)
            assertNull("清 A 不影响 B", AgentUrlRegistry.lastConnectionUrl(a))
            assertEquals("jdbc:postgresql://b:5432/y", AgentUrlRegistry.lastConnectionUrl(b))
        } finally {
            AgentUrlRegistry.clearAllForTest()
        }
    }

    @Test
    fun `项目为null安全`() {
        AgentUrlRegistry.set(null, "jdbc:mysql://x")
        assertNull(AgentUrlRegistry.lastConnectionUrl(null))
    }
}
