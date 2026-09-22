package cn.song.mybatisplus.visualizer.acp

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * T-MlLlmSignatureGuard ml-llm（AI Assistant）ACP internal 类型跨版本签名护栏（O-41，P2）。
 *
 * 背景：ACP 绕行（[AcpIdeaMcpTransportProviderImpl] 让 claude-acp 走 SSE）依赖 ml-llm 的三个
 * `@ApiStatus.Internal` 类型（provider 接口 / 传输枚举 / agent id），而 **verifier 对 optional 依赖
 * 不做类型解析**（三个目标平台逐目标记 `(failed) com.intellij.ml.llm (optional): Unavailable`），
 * 上游漂移不会让任何门禁转红——本测试把签名核验本身变成用例。
 *
 * **覆盖点**（四项断言，见 [MlLlmAcpSignatureChecker]）：
 *  1. `AcpAgentId` 为接口且含抽象 `getRawId()` / `getFullId()`（`()Ljava/lang/String;`）；
 *  2. `AcpIdeaMcpTransport` 为 `Enum` 且枚举常量**恰** `[STDIO, SSE]`（常量增减即绕行前提变化）；
 *  3. `AcpIdeaMcpTransportProvider` 为接口、唯一抽象方法描述符为
 *     `(L<AcpAgentId 包>/AcpAgentId;)Lcom/intellij/ml/llm/agents/acp/client/AcpIdeaMcpTransport;`、
 *     类上带 `@ApiStatus.Internal`（**读 `RuntimeInvisibleAnnotations` 属性位**，不是反射）；
 *  4. `AcpAgentId.REGISTRY_PREFIX` 常量仍为 `"acp.registry."`（对上
 *     `AcpIdeaMcpTransportProviderImpl.CLAUDE_ACP_ID` 的拼接依据）。
 *
 * **版本面 = 发现式，不是列举 243/253/262**：243/253 平台**没有** AI Assistant
 * （[AcpTransportIsolationTest] 已断言 ml-llm 在 243 沙盒缺席），故口径写「本机全部存在的 ml-llm 制品」：
 * `~/.gradle/caches/*​/transforms/*​/transformed/com.intellij.ml.llm-*/ml-llm`（本机 262.9437.276）+
 * `%APPDATA%/JetBrains/IntelliJIdea*/plugins/ml-llm`（本机装机 262.10968.97）。无制品 `assumeTrue` 跳过
 * （243/253 沙盒与 CI 上不误报失败）。
 *
 * **按包路径分组（本护栏的核心口径）**：两份制品的 `AcpAgentId` **不在同一个包**——装机在
 * `com.intellij.ml.llm.agents.acp.api`（在 ml-llm jar 内），transforms 的 262.9437.276 在
 * `com.intellij.platform.acp`（**平台**类型，只存在于配对发行版 `plugins/platform-acp-plugin` 的
 * `intellij.platform.acp.jar`，ml-llm 目录内零命中，故按 companion 根补挂并打印来源 jar）。期望包名
 * 由 [expectedPackages] 登记表给出：provider 描述符声明的包与登记值不符即红——上游改包必须人工复核后
 * 改登记表，不得静默通过；这条同时是「上游修好后绕行可移除」的自动信号。
 *
 * **与既有守卫的分工**（T-MLS-04）：[AcpTransportScopeSourceScanTest] 管**作用域**——ml-llm 类型引用
 * 只许出现在 `visualizer/acp/` 包、META-INF 只许 `acp-transport-optional.xml` 引用、桩声明只许在
 * `src/mcpStub`（回答「引用落在哪」）；本护栏管**签名**——那些被引用到的**外部制品**类型长什么样
 * （回答「引用到的东西还在不在、还是不是那个形状」）。两者互补：作用域守卫全绿也拦不住上游改签名。
 *
 * **不用桩类代替真制品**：桩刻意与真类偏离（不 `sealed`）且由本项目提供，断言桩永远绿——那是假绿的
 * 经典形态，故断言目标一律是外部制品目录。
 *
 * **已登记但不归本护栏判定的漂移**（勿当成漏报）：装机制品 262.10968.97 的 EP 接口形参包是
 * `com.intellij.ml.llm.agents.acp.api`，而 [AcpIdeaMcpTransportProviderImpl] 实现的是
 * `com.intellij.platform.acp` 包——本护栏按「制品→期望包名」登记表如实分组（两处各自成立即绿），
 * **不**对装机制品断言「包名 == 我方 impl 的包」，故该漂移不会让本护栏转红。其运行时后果
 * （EP 类链接是否真的失配）只能真机走查，未验点登记在计划 §五-5/§八。
 */
class AcpSignatureCrossVersionTest {

    @Test
    fun mlLlmAcpInternalSignaturesHoldOnAllLocalArtifacts() {
        val artifacts = discoverArtifacts()
        assumeTrue(
            "本机未发现任何 ml-llm（AI Assistant）制品——243/253 平台本就没有 AI Assistant，" +
                "跨版本签名核验无从执行",
            artifacts.isNotEmpty(),
        )

        val failures = mutableListOf<String>()
        val covered = mutableListOf<String>()
        for (artifact in artifacts) {
            val result = runChecker(artifact)
            covered += artifact.label
            println("[ml-llm 签名护栏] ${artifact.label} (${artifact.root.path}) exit=${result.exitCode}")
            println(result.output.trimEnd())
            if (result.exitCode != 0) {
                failures += "${artifact.label} (${artifact.root.path}):\n${result.output}"
            }
        }
        println("ml-llm 制品签名核验覆盖: ${covered.joinToString("、")}")
        assertEquals(failures.joinToString("\n\n"), 0, failures.size)
    }

    // ------------------------------------------------------------------
    // 制品发现（照 McpDbSignatureCrossVersionTest 的发现+执行模式；发现面换成 ml-llm 的两处落点）
    // ------------------------------------------------------------------

    private enum class ArtifactKind { TRANSFORMS, INSTALLED }

    /**
     * 制品→AcpAgentId 期望包名登记表。上游改包即红，须人工复核后改这里；未知形态一律判红
     * （fail-closed，防「怎么变都绿」）。
     */
    private val expectedPackages = mapOf(
        ArtifactKind.TRANSFORMS to "com.intellij.platform.acp",
        ArtifactKind.INSTALLED to "com.intellij.ml.llm.agents.acp.api",
    )

    private data class Artifact(
        val label: String,
        val root: File,
        val expectedPackage: String,
        val companions: List<File>,
    )

    /** companion 根：配对发行版的 `platform-acp-plugin/lib/modules`（transforms 制品的 AcpAgentId 只在这里）。 */
    private data class Companion(val root: File, val segment: String?)

    private data class CheckerResult(val exitCode: Int, val output: String)

    private fun discoverArtifacts(): List<Artifact> {
        val companions = discoverCompanions()
        val out = mutableListOf<Artifact>()
        for (transforms in gradleTransforms()) {
            transforms.listFiles { f -> f.isDirectory }?.forEach { hashDir ->
                File(hashDir, "transformed").listFiles { f -> f.isDirectory }?.forEach { distro ->
                    if (distro.name.startsWith("com.intellij.ml.llm-") && File(distro, "ml-llm/lib/modules").isDirectory) {
                        out += Artifact(
                            label = "transforms ${distro.name}",
                            root = File(distro, "ml-llm"),
                            expectedPackage = expectedPackages.getValue(ArtifactKind.TRANSFORMS),
                            companions = orderCompanions(companions, segmentOf(distro.name)),
                        )
                    }
                }
            }
        }
        System.getenv("APPDATA")?.let { appData ->
            File(appData, "JetBrains").listFiles { f -> f.isDirectory && f.name.startsWith("IntelliJIdea") }
                ?.forEach { ide ->
                    val mlLlm = File(ide, "plugins/ml-llm")
                    if (File(mlLlm, "lib/modules").isDirectory) {
                        out += Artifact(
                            label = "installed ${ide.name}",
                            root = mlLlm,
                            expectedPackage = expectedPackages.getValue(ArtifactKind.INSTALLED),
                            companions = orderCompanions(companions, null),
                        )
                    }
                }
        }
        return out.distinctBy { it.root.canonicalPath }
    }

    private fun gradleTransforms(): List<File> {
        val caches = File(System.getProperty("user.home"), ".gradle/caches")
        return caches.listFiles { f -> f.isDirectory }
            ?.map { File(it, "transforms") }
            ?.filter { it.isDirectory }
            ?: emptyList()
    }

    private fun discoverCompanions(): List<Companion> {
        val out = mutableListOf<Companion>()
        for (transforms in gradleTransforms()) {
            transforms.listFiles { f -> f.isDirectory }?.forEach { hashDir ->
                File(hashDir, "transformed").listFiles { f -> f.isDirectory }?.forEach { distro ->
                    val modules = File(distro, "plugins/platform-acp-plugin/lib/modules")
                    if (modules.isDirectory) {
                        out += Companion(modules, segmentOf(File(distro, "build.txt").takeIf { it.isFile }?.readText().orEmpty()))
                    }
                }
            }
        }
        System.getenv("LOCALAPPDATA")?.let { laf ->
            val install = File(laf, "Programs/IntelliJ IDEA")
            val modules = File(install, "plugins/platform-acp-plugin/lib/modules")
            if (modules.isDirectory) {
                out += Companion(modules, segmentOf(File(install, "build.txt").takeIf { it.isFile }?.readText().orEmpty()))
            }
        }
        return out.distinctBy { it.root.canonicalPath }
    }

    /** 同一 build 段（如 ml-llm `262.9437.276` 与发行版 `IU-262.9437.185` 的 `9437`）的 companion 排前。 */
    private fun orderCompanions(companions: List<Companion>, segment: String?): List<File> =
        companions
            .sortedWith(compareBy({ if (segment != null && it.segment == segment) 0 else 1 }, { it.root.path }))
            .map { it.root }

    /** 取版本号中段（`262.9437.276` / `IU-262.9437.185` → `9437`；段长可变，243 系为 5 位）。 */
    private fun segmentOf(text: String): String? =
        Regex("\\.(\\d+)\\.").find(text)?.groupValues?.get(1)

    private fun runChecker(artifact: Artifact): CheckerResult {
        // 检查器只读 class 文件字节（零平台依赖、零 classpath 装配），故用测试 JVM 自带的 java 即可——
        // 不必像 McpDbSignatureCrossVersionTest 那样借发行版 JBR（ml-llm 制品目录里没有 jbr）
        val javaHome = File(System.getProperty("java.home"))
        val java = sequenceOf(File(javaHome, "bin/java.exe"), File(javaHome, "bin/java"))
            .firstOrNull { it.isFile }?.absolutePath ?: "java"
        // 检查器类在测试运行时类路径上（src/test/java 编译产物）；-cp 只带检查器类目录
        //（完整测试类路径超 Windows 命令行 32K 上限 error=206，McpDbSignatureChecker 同款规避）
        val checkerResource = MlLlmAcpSignatureChecker::class.java.classLoader
            .getResource("MlLlmAcpSignatureChecker.class")!!.toURI()
        val checkerHome = File(checkerResource).parentFile.absolutePath
        val command = mutableListOf(
            java, "-cp", checkerHome, "MlLlmAcpSignatureChecker",
            artifact.root.absolutePath, artifact.expectedPackage,
        )
        artifact.companions.forEach { command += it.absolutePath }
        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        return CheckerResult(exit, output)
    }
}
