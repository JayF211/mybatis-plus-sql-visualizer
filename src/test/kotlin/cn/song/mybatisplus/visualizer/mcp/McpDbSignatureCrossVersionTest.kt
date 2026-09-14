package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * T-McpToolset MCP headless 执行链跨版本签名回归（铁律：新增反射入口必须登记跨版本签名护栏）。
 *
 * [McpDbExecutor] 以反射访问 DatabaseTools 的同步建连链（DatabaseConnectionManager.build →
 * createBlocking → getRemoteConnection → RemoteConnection/Statement/ResultSet）。该链 243/262
 * javap 同形实证，但反射绕过编译期检查、测试只跑构建平台 243——平台版本间漂移只有真实用户
 * 升级才暴露（T-DynUpdate 262 事故同款风险）。本测试把签名核验本身变成用例：对本机全部已
 * 解压的目标平台发行版（gradle transforms 里 verifyPlugin 下载的 243/253/262 + 已安装 IDE 兜底）
 * 用各发行版自带 JBR 运行 [McpDbSignatureChecker]，任一入口漂移即失败。
 *
 * 发行版缺失按 Assume 跳过对应家族；发行版无 DatabaseTools 插件时该家族按缺失计——
 * 不对：DatabaseTools 是 IU 全系捆绑，缺失说明发行版不完整，同样判失败（checker exit 3 亦算 fail）。
 */
class McpDbSignatureCrossVersionTest {

    @Test
    fun mcpDbExecutorApisResolveOnAllLocalTargetPlatforms() {
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
        println("MCP DB 签名核验覆盖平台: ${covered.joinToString("、")}")
        assertEquals(failures.joinToString("\n\n"), 0, failures.size)
    }

    // ------------------------------------------------------------------
    // 发行版发现与执行（与 PlatformSignatureCrossVersionTest 同模式；DatabaseTools 全系捆绑，
    // 发行版根目录直接含 plugins/DatabaseTools）
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

    private fun runChecker(distro: File): McpDbCheckResult {
        val java = sequenceOf(
            File(distro, "jbr/bin/java.exe"),
            File(distro, "jbr/bin/java"),
        ).firstOrNull { it.isFile }?.absolutePath ?: "java"
        // 检查器类在测试运行时类路径上（src/test/java 编译产物）；-cp 只带检查器类目录
        //（完整测试类路径超 Windows 命令行 32K 上限 error=206，PlatformSignatureChecker 同款规避）
        val checkerResource = McpDbSignatureChecker::class.java.classLoader
            .getResource("McpDbSignatureChecker.class")!!.toURI()
        val checkerHome = File(checkerResource).parentFile.absolutePath
        val proc = ProcessBuilder(
            listOf(java, "--add-modules=java.se", "-cp", checkerHome, "McpDbSignatureChecker", distro.absolutePath),
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        return McpDbCheckResult(exit, output)
    }

    private data class McpDbCheckResult(val exitCode: Int, val output: String)
}
