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
        // T-JumpPath-01：自跳过判断必须用运行时类名——生成期包名常量在拷贝/改包后与运行类分叉，
        // 拦截器自身帧被当成调用点上报（真机实锤：位置=SqlSourceInterceptor.java:62）
        assertTrue(
            "isSelf 应比对运行时 getClass().getName()",
            code.contains("cn.equals(getClass().getName())"),
        )
        assertFalse(
            "isSelf 不得残留注入包名全限定常量（防回退）",
            code.contains("cn.equals(\"com.demo.app.config.SqlSourceInterceptor\")"),
        )
        // 标记行格式须与 SqlSourceMarker 一致
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

    @Test
    fun `原生包装类模板_RowBounds与ResultHandler显式类型化可编译`() {
        // 20261008 验收（3.25 真机编译）：原生包装类（mybatis-config.xml <plugin> 注册物）
        // 的 beforeQuery 转发里，rowBounds/resultHandler 裸三元 `args[N] : null` 依赖 javac
        // 目标类型推断，JDK8 判 Object 无法转具体形参类型（RowBounds 与 ResultHandler 两处
        // 先后报错）→ 注入用户项目编译失败。显式 instanceof+cast 让类型明确，8/17/21 全可编译。
        // 摘掉任一形态即红。
        val code = SourceInterceptorTemplates.generateNativePluginClass("cn.song.nativedemo")
        assertTrue("应实现 org.apache.ibatis.plugin.Interceptor", code.contains("implements Interceptor"))
        assertTrue("应持有 InnerInterceptor 委托", code.contains("InnerInterceptor"))
        assertTrue(
            "rowBounds 转发应显式类型化（javac8 可编译，防回退裸三元）",
            code.contains("args.length > 2 && args[2] instanceof RowBounds ? (RowBounds) args[2] : null"),
        )
        assertTrue(
            "resultHandler 转发应显式类型化（javac8 可编译，防回退裸三元）",
            code.contains("args.length > 3 && args[3] instanceof ResultHandler ? (ResultHandler) args[3] : null"),
        )
        assertFalse(
            "不得回退到裸三元（javac8 判 Object 无法转具体形参类型）",
            code.contains("args.length > 2 ? args[2] : null") || code.contains("args.length > 3 ? args[3] : null"),
        )
        assertTrue("应导入 RowBounds", code.contains("import org.apache.ibatis.session.RowBounds;"))
        assertTrue("应导入 ResultHandler", code.contains("import org.apache.ibatis.session.ResultHandler;"))
    }

    @Test
    fun `docs手工示例含同款自位跳过`() {
        // 计划 §五-1：docs 示例（手工配置路线）与注入模板同款缺陷——不跳过拦截器自身帧时，
        // 拦截器注册在业务包内位置会打成本文件行。源码扫描断言同 BannedApiSourceScanTest 路线。
        var dir = java.io.File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val candidate = java.io.File(dir, "docs/instrumentation/SqlSourceInterceptor.kt")
            if (candidate.isFile) {
                val code = candidate.readText()
                assertTrue(
                    "docs 示例应含运行时类名自位跳过（拷贝/改包后依旧生效）",
                    code.contains("javaClass.name"),
                )
                return
            }
            dir = dir.parentFile ?: return@repeat
        }
        // CI/沙盒定位不到 docs 时不判失败（守卫性断言，主链路已由模板测试锁定）
    }
}
