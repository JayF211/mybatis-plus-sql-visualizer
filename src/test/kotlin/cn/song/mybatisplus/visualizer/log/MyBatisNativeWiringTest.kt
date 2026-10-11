package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R24：原生 MyBatis（非 Spring Boot）装配的**纯决策层**单测——
 * [MyBatisNativeWiring.decide] fail-closed 矩阵、[MyBatisNativeWiring.pluginFqcn]、
 * 候选文件名集、模板判别断言。XML PSI 编辑行为见 [MyBatisNativeConfigXmlEditTest]。
 */
class MyBatisNativeWiringTest {

    // ---------- decide：fail-closed 矩阵 ----------

    @Test
    fun `决策_无config_不注入`() {
        assertEquals(
            MyBatisNativeWiring.Decision.NO_CONFIG,
            MyBatisNativeWiring.decide(configCount = 0, rootTagName = null, alreadyRegistered = false),
        )
    }

    @Test
    fun `决策_config多处_不注入`() {
        assertEquals(
            MyBatisNativeWiring.Decision.AMBIGUOUS_CONFIG,
            MyBatisNativeWiring.decide(configCount = 2, rootTagName = "configuration", alreadyRegistered = false),
        )
    }

    @Test
    fun `决策_根标签非configuration_不注入`() {
        assertEquals(
            MyBatisNativeWiring.Decision.NOT_MYBATIS_CONFIG,
            MyBatisNativeWiring.decide(configCount = 1, rootTagName = "beans", alreadyRegistered = false),
        )
        assertEquals(
            "无根标签（解析不出根）同样拒绝",
            MyBatisNativeWiring.Decision.NOT_MYBATIS_CONFIG,
            MyBatisNativeWiring.decide(configCount = 1, rootTagName = null, alreadyRegistered = false),
        )
    }

    @Test
    fun `决策_已注册_幂等跳过`() {
        assertEquals(
            MyBatisNativeWiring.Decision.ALREADY_REGISTERED,
            MyBatisNativeWiring.decide(configCount = 1, rootTagName = "configuration", alreadyRegistered = true),
        )
    }

    @Test
    fun `决策_唯一config且根合法且未注册_放行`() {
        assertEquals(
            MyBatisNativeWiring.Decision.INJECT,
            MyBatisNativeWiring.decide(configCount = 1, rootTagName = "configuration", alreadyRegistered = false),
        )
    }

    // ---------- 命名与候选 ----------

    @Test
    fun `插件全限定名落在config包`() {
        assertEquals(
            "com.example.app.config.SqlVisualizerNativePlugin",
            MyBatisNativeWiring.pluginFqcn("com.example.app"),
        )
    }

    @Test
    fun `候选文件名只收官方惯用名`() {
        assertEquals(sortedSetOf("mybatis-config.xml", "mybatis.xml"), MyBatisNativeWiring.CONFIG_FILE_CANDIDATE_NAMES)
    }

    // ---------- 模板判别（纯文本断言：生成物自包含关键装配点） ----------

    @Test
    fun `拦截器模板支持包装器自跳帧扩展参数`() {
        // 原生包装类的 intercept 帧在调用栈上且落在 appPackages 内——若无扩展自跳帧表，
        // locateSource 会把包装类自己当成调用点上报（标记行指到生成文件，跳转全错）
        val template = SourceInterceptorTemplates.generateInterceptorClass("com.example.app")
        assertTrue(
            "拦截器模板必须带 extraSelfClassNames 可参构造（原生包装器帧跳过依赖它）",
            template.contains("String... extraSelfClassNames"),
        )
        assertTrue(
            "isSelf 必须消费扩展自跳帧表",
            template.contains("selfClassNames"),
        )
        // 既有升级判据不回归：新模板仍含运行时比对（当前形态副本永不误升级）
        assertTrue(template.contains("getClass().getName()"))
    }

    @Test
    fun `原生包装模板_持有委托并注册三签名`() {
        val template = SourceInterceptorTemplates.generateNativePluginClass("com.example.app")
        assertTrue("包装类必须按检测到的 basePackage 构造委托", template.contains("com.example.app."))
        assertTrue("包装类必须实现 org.apache.ibatis.plugin.Interceptor（<plugin> 注册的硬前提）", template.contains("implements Interceptor"))
        assertTrue("Executor.query 四参签名", template.contains("RowBounds.class, ResultHandler.class"))
        assertTrue("Executor.update 签名", template.contains("method = \"update\""))
        assertTrue("必须注册到 SqlSourceInterceptor 本体", template.contains("SqlSourceInterceptor"))
    }
}
