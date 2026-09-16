package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-IntrUpg-02：[SqlSourceInterceptorAutoInjector.needsInterceptorUpgrade] 表驱动单测。
 *
 * 升级判定三条件（模板生成标记 + 旧 isSelf 常量拼接形态 + 非运行时比对）的边界：
 * 旧模板产物必升级、新模板不重复写、用户自写文件永不被覆盖。
 */
class InjectionUpgradeDecisionTest {

    /** 旧模板形态：isSelf 用生成期包名常量（真机污染件同款）。 */
    private fun legacyTemplate(basePackage: String) = """
        package $basePackage.config;
        /**
         * 自动生成于 SQL Visualizer 插件，可安全删除。
         */
        public class SqlSourceInterceptor implements InnerInterceptor {
            private boolean isSelf(String cn) {
                return cn.equals("$basePackage.config.SqlSourceInterceptor");
            }
        }
    """.trimIndent()

    /** 新模板形态（现行 T-JumpPath-01）：isSelf 运行时类名比对。 */
    private fun runtimeTemplate(basePackage: String) = """
        package $basePackage.config;
        /**
         * 自动生成于 SQL Visualizer 插件，可安全删除。
         */
        public class SqlSourceInterceptor implements InnerInterceptor {
            private boolean isSelf(String cn) {
                return cn.equals(getClass().getName());
            }
        }
    """.trimIndent()

    @Test
    fun `旧形态模板_命中升级`() {
        assertTrue(SqlSourceInterceptorAutoInjector.needsInterceptorUpgrade(legacyTemplate("cn.song.mpjdemo")))
    }

    @Test
    fun `新形态模板_不升级`() {
        assertFalse(SqlSourceInterceptorAutoInjector.needsInterceptorUpgrade(runtimeTemplate("cn.song.mpjdemo")))
    }

    @Test
    fun `用户自写文件_即便含常量isSelf_永不覆盖`() {
        // 无模板生成标记：仅命中后两条件不构成升级（覆盖用户文件红线）
        val userWritten = """
            package com.acme.config;
            public class SqlSourceInterceptor implements InnerInterceptor {
                private boolean isSelf(String cn) {
                    return cn.equals("com.acme.config.SqlSourceInterceptor");
                }
            }
        """.trimIndent()
        assertFalse(SqlSourceInterceptorAutoInjector.needsInterceptorUpgrade(userWritten))
    }

    @Test
    fun `模板标记但无isSelf常量形态_不升级`() {
        // 模板标记 + 运行时比对之外的其它新演进形态（无 `cn.equals("` 常量特征）不误判
        val evolved = """
            /** 自动生成于 SQL Visualizer 插件 */
            public class SqlSourceInterceptor { }
        """.trimIndent()
        assertFalse(SqlSourceInterceptorAutoInjector.needsInterceptorUpgrade(evolved))
    }

    @Test
    fun `空文本与无关文本_不升级`() {
        assertFalse(SqlSourceInterceptorAutoInjector.needsInterceptorUpgrade(""))
        assertFalse(SqlSourceInterceptorAutoInjector.needsInterceptorUpgrade("package x; class Y {}"))
    }
}
