package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.acp.SignatureDistroDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

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
        val distros = SignatureDistroDiscovery.ideaDistros
        assumeTrue("本机未发现任何 IDEA 发行版，跨版本签名核验无从执行", distros.isNotEmpty())

        // T-TestPerf6：逐发行版核验由串行改并行——每个发行版一个独立 JBR 外部 JVM（互不共享
        // 状态），并行只省墙钟不改判红语义；invokeAll 保提交序（family 升序），covered/failures
        // 的聚合口径与输出顺序与原串行版逐字符一致。池大小 3：外部 JVM 与 4 个测试片并存的
        // 资源上限，收益与假失败风险由定向读数验证（T-TestPerf6 计划 V4）。
        val pool = Executors.newFixedThreadPool(minOf(3, distros.size))
        val results = try {
            pool.invokeAll(distros.map { (family, root) ->
                Callable { (family to root) to runChecker(root) }
            }).map { future ->
                // 执行环境故障（ProcessBuilder IOException 等）保持原串行版的测试 error 形态
                try { future.get() } catch (e: ExecutionException) { throw e.cause ?: e }
            }
        } finally {
            pool.shutdownNow()
        }

        val failures = mutableListOf<String>()
        val covered = mutableListOf<String>()
        for ((target, result) in results) {
            val (family, root) = target
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
    // 发行版发现（T-TestPerf6 起收敛到 testutil/SignatureDistroDiscovery 单例，口径原样：
    // transforms 判 build.txt+lib + LOCALAPPDATA 装机兜底，family 去重升序，一次遍历三类共享）；
    // 本类只保留执行（与 PlatformSignatureCrossVersionTest 同模式；DatabaseTools 全系捆绑，
    // 发行版根目录直接含 plugins/DatabaseTools）。
    // ------------------------------------------------------------------

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
