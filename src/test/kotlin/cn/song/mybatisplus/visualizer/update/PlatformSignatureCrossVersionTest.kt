package cn.song.mybatisplus.visualizer.update

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File


/**
 * 跨版本平台签名回归（T-DynUpdate 262 签名漂移事故护栏）。
 *
 * 背景：262 平台 `unloadPlugin` 首参从 IdeaPluginDescriptorImpl 漂移为子类 PluginMainDescriptor，
 * 反射探测器在 243 单测与 verifyPlugin 均无法复现（反射绕过编译期检查、测试只跑构建平台 243），
 * 只有真实用户升级才暴露。本测试把「签名核验」本身变成用例：对**本机全部已解压的目标平台
 * 发行版**（gradle transforms 里 verifyPlugin 下载的 243/253/262 + 已安装 IDE 兜底），用各发行版
 * 自带 JBR 运行 [PlatformSignatureChecker]，以与生产一致的弹性匹配逻辑解析动态安装五入口。
 * 任何目标版本的签名漂移都会在本地测试失败，不再依赖真实用户踩雷。
 *
 * T-DbSchemaCompletion 追加第 6 项：`DatabaseSchemaResolver` 的反射入口 `ObjectKind.COLUMN`
 * （静态字段读法）——DBN 的 ObjectKind 是手写 type-safe enum、`enumConstants` 恒 null，历史上按
 * 枚举取导致 schema 列遍历从未执行且静默无日志；本项在捆绑 Database Tools 的发行版上验证该入口
 * 可用，未捆绑者打印 SKIP 不计缺失。
 *
 * 发行版缺失时按 Assume 跳过对应家族（CI 机器可能只装部分版本）；一个家族都没有则整类跳过。
 */
class PlatformSignatureCrossVersionTest {

    @Test
    fun dynamicInstallApisResolveOnAllLocalTargetPlatforms() {
        val distros = discoverDistros()
        assumeTrue("本机未发现任何 IDEA 发行版，跨版本签名核验无从执行", distros.isNotEmpty())

        val failures = mutableListOf<String>()
        val covered = mutableListOf<String>()
        for ((family, root) in distros) {
            val result = runChecker(root)
            covered.add(family)
            if (result.exitCode != 0) {
                failures.add("IU-$family (${root.path}):\n${result.output}")
            }
        }
        assumeTrue("未发现可运行的目标平台发行版", covered.isNotEmpty())
        println("跨版本签名核验覆盖平台: ${covered.joinToString("、")}")
        assertEquals(failures.joinToString("\n\n"), 0, failures.size)
    }

    // ------------------------------------------------------------------
    // 发行版发现：gradle caches 下 verifyPlugin 转压的发行目录 + 已安装 IDE 兜底
    // ------------------------------------------------------------------

    private fun discoverDistros(): List<Pair<String, File>> {
        val roots = mutableListOf<File>()
        val caches = File(System.getProperty("user.home"), ".gradle/caches")
        caches.listFiles { f -> f.isDirectory }?.forEach { versionDir ->
            File(versionDir, "transforms").listFiles { f -> f.isDirectory }?.forEach { hashDir ->
                File(hashDir, "transformed").listFiles { f -> f.isDirectory }?.forEach { distro ->
                    if (File(distro, "build.txt").isFile && File(distro, "lib").isDirectory) {
                        roots.add(distro)
                    }
                }
            }
        }
        // 已安装 IDE 兜底（如本机 262 实机）
        System.getenv("LOCALAPPDATA")?.let { laf ->
            val installed = File(laf, "Programs/IntelliJ IDEA")
            if (File(installed, "build.txt").isFile && File(installed, "lib").isDirectory) {
                roots.add(installed)
            }
        }
        return roots.mapNotNull { distro ->
            val build = File(distro, "build.txt").readText().trim()
            Regex("(\\d{3})\\.").find(build)?.groupValues?.get(1)?.let { family -> family to distro }
        }.distinctBy { it.first }.sortedBy { it.first }
    }

    // ------------------------------------------------------------------
    // 检查器编译（一次性）与执行
    // ------------------------------------------------------------------

    private fun runChecker(distro: File): CheckResult {
        val java = sequenceOf(
            File(distro, "jbr/bin/java.exe"),
            File(distro, "jbr/bin/java"),
        ).firstOrNull { it.isFile }?.absolutePath ?: "java"
        // 检查器类就在测试运行时类路径上（src/test/java 正常编译产物）；-cp 只带检查器类目录
        //（完整测试类路径超 Windows 命令行 32K 上限 error=206）。发行版 JBR（21/25）可加载
        // Java 17 字节码，再用 URLClassLoader(parent=null) 加载本发行版平台 jar——字节码版本
        // 天然兼容，不受测试 JVM（17）读不了 262 major-69 类文件的限制
        // codeSource.location 在部分 classloader 下返回 null（262 实机同款现象），用 resource 定位
        val checkerResource = PlatformSignatureChecker::class.java.classLoader
            .getResource("PlatformSignatureChecker.class")!!.toURI()
        val checkerHome = File(checkerResource).parentFile.absolutePath
        val proc = ProcessBuilder(
            listOf(java, "-cp", checkerHome, "PlatformSignatureChecker", File(distro, "lib").absolutePath),
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        return CheckResult(exit, output)
    }

    private data class CheckResult(val exitCode: Int, val output: String)
}
