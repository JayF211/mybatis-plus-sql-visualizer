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
 *     `AcpIdeaMcpTransportProviderImpl.CLAUDE_ACP_ID` 的拼接依据）；
 *  5. 制品树内某份 `plugin.xml` 仍声明该 EP（类字节不变而 EP 声明被删时护栏须红）；
 *  6. **我方 impl 声明了该制品 provider 抽象方法的同描述符 getTransport**（见下）。
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
 * **断言 ⑥（复核 major⑯，2026-09-22 补）：我方 impl 是否真的实现了该制品的接口**。此前本护栏只核
 * 「制品自己的 provider 长什么样」：装机制品 262.10968.97 的 provider 形参包是
 * `com.intellij.ml.llm.agents.acp.api`，而 [AcpIdeaMcpTransportProviderImpl] 当时只声明了
 * `com.intellij.platform.acp` 形态——按「制品→期望包名」分组**两处各自成立即绿**，于是「我方类在该
 * 制品上根本没有实现该接口（EP 调用即 AbstractMethodError、SSE 绕行失效）」这个失配**看不见**。
 * 现由 `--impl=<impl class 文件>` 把 impl 交进检查器，逐制品核「impl 是否声明了与 provider 抽象方法
 * **逐字节相同**描述符的 getTransport」；两形态由 impl 的双重载覆盖（上游把 AcpAgentId 的包在两条发行线
 * 之间搬过：262.9437.276 = 平台包，262.10968.97 = api 包，JVM 按擦除后描述符匹配 override）。
 * **仍属未验点**：真机上两线各自「EP 真被注册 / 不抛 AbstractMethodError / SSE 通道可达」只能真机走查
 * （登记在计划 §五/§八）。
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

    /**
     * **运行时**核验（复核 major⑯ 的运行时半边）：在受控 classpath 下加载**真制品**的接口与枚举，
     * 再加载我方实现类，用真制品自己的 `AcpAgentId.Companion.parse(…)` 造实例并**真调一次**接口方法。
     *
     * 为什么静态断言（⑥）之外还要这一条：静态只证明「描述符对得上」，证明不了
     * 「在该制品的类型面下能加载/实例化/通过接口判定」与「调用不抛 `AbstractMethodError`」——
     * 后者正是复核⑯ 指认的失效形态。反向验证读数：摘掉 api 形态重载 → 装机线探针报
     * `java.lang.AbstractMethodError: null`（`RESULT MISSING 2`），即本用例对该缺陷有判别力。
     *
     * 无制品时与签名用例同款 `assumeTrue` 跳过；制品在场而运行时依赖（平台 lib / Kotlin 运行时）装配不齐
     * 则**判红**（fail-closed：不得静默当成「已核」）。
     */
    @Test
    fun mlLlmAcpTransportRuntimeHoldsOnAllLocalArtifacts() {
        val artifacts = discoverArtifacts()
        assumeTrue(
            "本机未发现任何 ml-llm（AI Assistant）制品——运行时探针无从执行",
            artifacts.isNotEmpty(),
        )

        val failures = mutableListOf<String>()
        for (artifact in artifacts) {
            val result = runRuntimeProbe(artifact)
            println("[ACP 运行时探针] ${artifact.label} exit=${result.exitCode}")
            println(result.output.trimEnd())
            if (result.exitCode != 0) {
                failures += "${artifact.label} (${artifact.root.path}):\n${result.output}"
            }
        }
        assertEquals(failures.joinToString("\n\n"), 0, failures.size)
    }

    private fun runRuntimeProbe(artifact: Artifact): CheckerResult {
        val probeResource = AcpTransportRuntimeProbe::class.java.classLoader
            .getResource("AcpTransportRuntimeProbe.class")!!.toURI()
        val probeHome = File(probeResource).parentFile.absolutePath

        val implDir = implClassesDir()
            ?: return CheckerResult(1, "MISS 找不到我方 impl 的产物目录（无法核运行时半边）")
        val acpJar = File(artifact.root, "lib/modules/intellij.ml.llm.agents.acp.jar")
        if (!acpJar.isFile) {
            return CheckerResult(1, "MISS 制品内未找到 $acpJar（运行时探针需要真接口/真枚举）")
        }
        val platformAcpJar = artifact.companions
            .map { File(it, "intellij.platform.acp.jar") }
            .firstOrNull { it.isFile }
            ?: return CheckerResult(1, "MISS 未找到 platform-acp 制品（platform.acp.AcpAgentId 的来源）")
        val platformLib = platformLibDir(artifact)
            ?: return CheckerResult(1, "MISS 未找到该发行线的平台 lib 目录（真制品初始化需要平台类）")
        // 真制品是 class file 69（Java 25），测试 JVM（21）加载不了 —— 用**该发行线自带的 JBR**跑探针：
        // 既满足版本要求，又与真机 IDE 的运行时一致（找不到 JBR 才回落测试 JVM，届时会明确报版本错）
        val java = distroJava(platformLib)
            ?: sequenceOf(
                File(System.getProperty("java.home"), "bin/java.exe"),
                File(System.getProperty("java.home"), "bin/java"),
            ).firstOrNull { it.isFile }?.absolutePath ?: "java"
        val kotlinJars = kotlinRuntimeJars()
        if (kotlinJars.isEmpty()) {
            return CheckerResult(
                1,
                "MISS 找不到 Kotlin 运行时 jar（真制品是 Kotlin 2.x）——诊断：user.home=" +
                    System.getProperty("user.home") + "；codeSource 命中=" + codeSourceJarCount() +
                    "；cache 命中=" + cachedJarDiagnostics(),
            )
        }

        val command = mutableListOf(
            java, "-cp", probeHome, "AcpTransportRuntimeProbe",
            implDir.absolutePath, artifact.expectedPackage, acpJar.absolutePath, platformAcpJar.absolutePath,
            platformLib.absolutePath,
        )
        kotlinJars.forEach { command += it.absolutePath }
        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        return CheckerResult(exit, output)
    }

    /** 我方 impl 的产物根目录（k2j 适配：生产树是 src/main/java → `build/classes/java/main`；kotlin 目录留作回落）。 */
    private fun implClassesDir(): File? {
        val relative = "cn/song/mybatisplus/visualizer/acp/AcpIdeaMcpTransportProviderImpl.class"
        return listOf(
            File("build/classes/java/main"),
            File("build/classes/kotlin/main"),
            File("build/classes/kotlin/jvm/main"),
        ).firstOrNull { File(it, relative).isFile }
    }

    /**
     * 该制品所属发行线的平台 `lib` 目录（与制品同 build 段优先）。真制品的 `AcpRegistryAgentId`
     * 静态初始化要 `com.intellij.openapi.extensions.ExtensionPointName` 等平台类，故须把发行版 lib 全量挂上
     * ——逐个挑 jar 会陷入「缺一个类补一个 jar」的循环。
     */
    private fun platformLibDir(artifact: Artifact): File? {
        val segment = segmentOf(artifact.root.parentFile?.name.orEmpty())
        val candidates = mutableListOf<File>()
        for (transforms in gradleTransforms()) {
            transforms.listFiles { f -> f.isDirectory }?.forEach { hashDir ->
                File(hashDir, "transformed").listFiles { f -> f.isDirectory }?.forEach { distro ->
                    val lib = File(distro, "lib")
                    if (distro.name.startsWith("idea") && File(lib, "util.jar").isFile) {
                        candidates += lib
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null
        return candidates.sortedWith(
            compareBy({ if (segment != null && segmentOf(it.parentFile.name) == segment) 0 else 1 }, { it.path }),
        ).first()
    }

    /** 该发行线自带的 JBR（`<distro>/jbr/bin/java`）——真机 IDE 用的就是它。 */
    private fun distroJava(platformLib: File): String? {
        val bin = File(platformLib.parentFile, "jbr/bin")
        return sequenceOf(File(bin, "java.exe"), File(bin, "java"))
            .firstOrNull { it.isFile }?.absolutePath
    }

    private val kotlinProbeClasses = listOf(
        "kotlin.Unit",
        "kotlinx.serialization.KSerializer",
        "kotlinx.serialization.json.Json",
        "kotlinx.coroutines.CoroutineScope",
    )

    /** 诊断：codeSource 路径命中的 jar 数（探针失败时写进报文，避免「找不到」这种无信息量的失败）。 */
    private fun codeSourceJarCount(): Int = kotlinProbeClasses.count { name ->
        runCatching {
            val c = Class.forName(name, false, AcpSignatureCrossVersionTest::class.java.classLoader)
            File(c.protectionDomain.codeSource.location.toURI()).isFile
        }.getOrDefault(false)
    }

    /** 诊断：cache 路径逐项命中情况。 */
    private fun cachedJarDiagnostics(): String = listOf(
        "org.jetbrains.kotlin/kotlin-stdlib" to newestCachedJar("org.jetbrains.kotlin", "kotlin-stdlib"),
        "kotlinx/kotlinx-serialization-core-jvm" to
            newestCachedJar("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm"),
        "kotlinx/kotlinx-serialization-json-jvm" to
            newestCachedJar("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm"),
        "kotlinx/kotlinx-coroutines-core-jvm" to
            newestCachedJar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
    ).joinToString("; ") { (k, v) -> "$k=" + (v?.name ?: "none") }

    /**
     * Kotlin 运行时 jar（stdlib + serialization + coroutines）。
     *
     * 真制品是 Kotlin 2.x，缺 stdlib/serialization 会在其静态初始化期抛 `NoClassDefFoundError`。
     * 先按**类自身的 codeSource** 取（最准），取不齐则回落 **gradle 模块缓存**（本机实测有效；
     * 两条路都不依赖 `java.class.path`——Gradle 测试 JVM 的该属性在部分布局下并不逐条列出依赖）。
     */
    private fun kotlinRuntimeJars(): List<File> {
        val fromCodeSource = kotlinProbeClasses.mapNotNull { name ->
            runCatching {
                val c = Class.forName(name, false, AcpSignatureCrossVersionTest::class.java.classLoader)
                File(c.protectionDomain.codeSource.location.toURI())
            }.getOrNull()
        }.filter { it.isFile }
        if (fromCodeSource.isNotEmpty()) {
            return fromCodeSource.distinctBy { it.name.substringBeforeLast('-') }
        }
        return listOf(
            newestCachedJar("org.jetbrains.kotlin", "kotlin-stdlib"),
            newestCachedJar("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm"),
            newestCachedJar("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm"),
            newestCachedJar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
        ).filterNotNull()
    }

    /**
     * gradle 模块缓存里 artifact 名**恰为** prefix 的最高版本 jar。
     *
     * 目录形如 `<group>/<artifact>/<version>/<sha1>/<artifact>-<version>.jar`（jar 在哈希子目录里，
     * 故必须递归找；只扫一层会一个都找不到）。artifact 名要求**精确相等**，否则
     * `kotlin-stdlib-jdk8` 这类派生 artifact 会盖过 `kotlin-stdlib`（按名降序时 `j` > `2`）。
     */
    private fun newestCachedJar(group: String, prefix: String): File? {
        val base = File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1/$group")
        val artifactDir = File(base, prefix)
        if (!artifactDir.isDirectory) return null
        return artifactDir.walkTopDown()
            .maxDepth(3)
            .filter { it.isFile && it.name.endsWith(".jar") && !it.name.contains("sources") }
            .filter { it.name.startsWith("$prefix-") }
            .sortedByDescending { it.name }
            .firstOrNull()
    }

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
        // 断言 ⑥（复核 major⑯）：把**我方 impl 的 class 文件**交给检查器，核「impl 是否真的声明了该制品
        // provider 的抽象方法」。不给就是「只核制品、不核我方」——那正是本轮被点名的假绿，故必须给。
        implClassFile()?.let { command += "--impl=" + it.absolutePath }
        artifact.companions.forEach { command += it.absolutePath }
        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        return CheckerResult(exit, output)
    }

    /**
     * 我方 provider 实现的 class 文件（断言 ⑥ 的被核对象）。按「主源集产物目录」定位；找不到返回 null，
     * 由调用方在报告里显式标注（不得静默当成「已核」）。
     */
    private fun implClassFile(): File? {
        val relative = "cn/song/mybatisplus/visualizer/acp/AcpIdeaMcpTransportProviderImpl.class"
        // k2j 适配：生产树是 src/main/java → 产物在 build/classes/java/main，kotlin 目录留作回落
        val candidates = listOf(
            File("build/classes/java/main", relative),
            File("build/classes/kotlin/main", relative),
            File("build/classes/kotlin/jvm/main", relative),
        )
        candidates.firstOrNull { it.isFile }?.let { return it }
        // 兜底：从已加载的类取 codeSource（测试运行时主源集产物在类路径上）
        return runCatching {
            val clazz = Class.forName("cn.song.mybatisplus.visualizer.acp.AcpIdeaMcpTransportProviderImpl")
            val location = File(clazz.protectionDomain.codeSource.location.toURI())
            val f = if (location.isDirectory) File(location, relative) else location
            f.takeIf { it.isFile }
        }.getOrNull()
    }
}
