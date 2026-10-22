package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-LicenseCompliance（20261021）：分发包许可合规守卫。
 *
 * 钉住三件事：① 根目录 LICENSE 与 THIRD-PARTY-NOTICES.md 在场；
 * ② NOTICES 覆盖随包分发的全部第三方组件（JetBrains 图标 / JSqlParser / ASM）；
 * ③ build.gradle.kts 的 jsqlparser 依赖必须排除 jmh-core（防打包泄漏回潮）。
 *
 * 反向验证：删 NOTICES、改名组件关键词、或摘掉 build.gradle.kts 的 exclude 均必红。
 * 零平台引用，进 testFast 快桶。
 */
class DistributionComplianceGuardTest {

    @Test
    fun test根目录许可文件在场() {
        listOf("LICENSE", "THIRD-PARTY-NOTICES.md").forEach { name ->
            val file = File(name)
            assertTrue("根目录缺少 $name（分发包合规文件），或为空", file.isFile && file.length() > 100)
        }
    }

    @Test
    fun testNOTICES覆盖全部第三方组件() {
        val text = File("THIRD-PARTY-NOTICES.md").readText()
        listOf(
            "JetBrains", "JSqlParser", "ASM", "INRIA",
            "Apache License 2.0", "BSD 3-Clause",
        ).forEach { keyword ->
            assertTrue("THIRD-PARTY-NOTICES.md 缺少第三方组件关键词：$keyword", text.contains(keyword))
        }
    }

    @Test
    fun testJsqlparser依赖排除jmhCore() {
        val build = File("build.gradle.kts").readText()
        assertTrue(
            "build.gradle.kts 的 jsqlparser 依赖必须排除 jmh-core（JSqlParser 传递的基准测试依赖，" +
                "不排除会把 jmh-core/jopt-simple/commons-math3 约 2.8MB 打进插件运行期 lib）",
            build.contains("exclude(group = \"org.openjdk.jmh\", module = \"jmh-core\")"),
        )
    }
}
