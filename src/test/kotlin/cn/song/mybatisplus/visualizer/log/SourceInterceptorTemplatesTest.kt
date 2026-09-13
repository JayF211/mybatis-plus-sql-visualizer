package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SourceInterceptorTemplates 纯逻辑单测（T-TestGuard-07 零覆盖补测：F-44-7 注入模板
 * 此前无任何测试）。生成物是注入用户项目编译的 Java 源码，格式错误 = 用户项目编译失败，
 * 因此锁定关键结构。
 */
class SourceInterceptorTemplatesTest {

    @Test
    fun `拦截器模板_包名正确注入`() {
        val code = SourceInterceptorTemplates.generateInterceptorClass("com.demo.app")
        assertTrue("应有正确 package 声明", code.contains("package com.demo.app.config;"))
        assertTrue("类名应为 SqlSourceInterceptor", code.contains("public class SqlSourceInterceptor"))
        // 自跳过判断必须用注入包名的全限定名，否则捕获到拦截器自身帧
        assertTrue(
            "isSelf 应比对注入包名的全限定类名",
            code.contains("cn.equals(\"com.demo.app.config.SqlSourceInterceptor\")"),
        )
        // 配置类里 singleton 传的是「包名.」前缀
        assertTrue("标记行格式应与 SqlSourceMarker 一致", code.contains("SQLVIS-SOURCE|"))
    }

    @Test
    fun `拦截器模板_框架帧跳过含代理帧`() {
        val code = SourceInterceptorTemplates.generateInterceptorClass("com.demo")
        assertTrue("应跳过 com.sun.proxy 代理帧", code.contains("com.sun.proxy."))
        // 生成物必须是可直接编译的 Java：不应残留 Kotlin 模板转义形态（历史转义事故面）
        assertFalse("不应残留 Kotlin 模板插值原文", code.contains("\${"))
    }

    @Test
    fun `配置类模板_bean注册结构完整`() {
        val code = SourceInterceptorTemplates.generateSpringBootConfigClass("com.demo")
        assertTrue("应有正确 package 声明", code.contains("package com.demo.config;"))
        assertTrue("应有 @Configuration", code.contains("@Configuration"))
        assertTrue("应定义拦截器 bean", code.contains("public SqlSourceInterceptor sqlSourceInterceptor()"))
        assertTrue("appPackages 应为注入包名前缀", code.contains("Collections.singleton(\"com.demo.\")"))
        assertTrue("应有 SmartInitializingSingleton 注册器（避免 bean 时序问题）", code.contains("SmartInitializingSingleton"))
    }

    @Test
    fun `文件名常量稳定_注入路径依赖`() {
        assertEquals("SqlSourceInterceptor.java", SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME)
        assertEquals("SqlVisualizerConfig.java", SourceInterceptorTemplates.CONFIG_FILE_NAME)
    }
}
