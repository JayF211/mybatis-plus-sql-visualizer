package cn.song.mybatisplus.visualizer.acp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ml-llm（AI Assistant）引用作用域守卫（T-AcpMcpTransport，与 McpScopeSourceScanTest 同款护栏）。
 *
 * 背景：`com.intellij.ml.llm.*` 来自可选插件 AI Assistant（acp-transport-optional.xml 仅在
 * com.intellij.ml.llm 存在时加载；该插件非发行版捆绑，243/253/262 三个 CI 目标平台**都没有**）。
 * 与 mcpserver 一样，缺口豁免的诚实性取决于「引用严格限定在适配包内」——若未来任何非 acp 代码
 * （面板/服务层/设置页）直接引用 ml-llm 类型，未装环境就会真正走到缺类路径。本守卫把作用域钉死：
 *  1. src/main/kotlin 中出现 `com.intellij.ml.llm` 的文件必须位于 `visualizer/acp/` 包；
 *  2. META-INF 下引用 ml-llm **类包**（`com.intellij.ml.llm.agents.acp`，比插件 id 更深一层）或
 *     实现类名的描述文件只允许 acp-transport-optional.xml——主 plugin.xml 里合法且必需的只有
 *     `<depends optional="true">com.intellij.ml.llm</depends>`（插件 id 声明，不含 `.agents.acp`，
 *     故不命中本规则；与 mcpserver 那套「大小写区分插件 id 与包名」的判法不同——ml-llm 的插件 id
 *     与类包前缀逐字相同，只能靠层级区分）；
 *  3. 桩类型（`package com.intellij.ml.llm.*` / `com.intellij.mcpserver.*`）的**声明**只允许出现在
 *     src/mcpStub 桩源集内——R5 的真正泄漏向量是「桩文件被误放进 src/main」，那样桩类会随
 *     main 输出进插件 jar，运行时与平台/AI Assistant 真类冲突。
 * 扫描前剥离注释（BannedApiSourceScanTest 同款）。
 */
class AcpTransportScopeSourceScanTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    /** 剥离 block 注释与行注释（与 BannedApiSourceScanTest 同款）。 */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    /** 剥离字符串字面量（字符串引用不是编译期类型引用，与 McpScopeSourceScanTest 同理）。 */
    private fun stripStrings(source: String): String =
        source.replace(Regex("\"[^\"]*\""), "")

    private fun mainSources(): List<Pair<String, String>> =
        repoRoot().resolve("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') to it.readText() }
            .toList()

    @Test
    fun `ml-llm 类型引用限定在 acp 适配包内`() {
        val offenders = mainSources()
            .filter { (_, text) -> stripStrings(stripComments(text)).contains("com.intellij.ml.llm") }
            .map { (path, _) -> path }
            .filter { !it.contains("/visualizer/acp/") }
        assertTrue(
            "src/main/kotlin 出现 acp 包之外的 com.intellij.ml.llm 引用：$offenders。" +
                "ml-llm 类型仅允许在 visualizer/acp/ 适配包内出现（AI Assistant 缺席时" +
                "acp-transport-optional.xml 整文件不加载，引用出包即破坏「永不可达」前提）",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `META-INF 下 ml-llm 类引用只在 acp-transport-optional_xml`() {
        val offenders = repoRoot().resolve("src/main/resources/META-INF").walkTopDown()
            .filter { it.isFile && it.extension == "xml" && it.name != "acp-transport-optional.xml" }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') to stripXmlComments(it.readText()) }
            .filter { (_, text) ->
                text.contains("com.intellij.ml.llm.agents.acp") ||
                    text.contains("AcpIdeaMcpTransportProviderImpl")
            }
            .map { (path, _) -> path }
            .toList()
        assertTrue(
            "META-INF 描述文件在 acp-transport-optional.xml 之外出现 ml-llm 类引用：$offenders。" +
                "主 plugin.xml 引用 ml-llm 类型会让未装 AI Assistant 的环境在解析阶段尝试加载缺类" +
                "（<depends> 插件 id 声明 com.intellij.ml.llm 为合法机制，不在本规则内）",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `桩类型声明只允许在 mcpStub 桩源集内`() {
        val stubPackages = listOf("package com.intellij.ml.llm", "package com.intellij.mcpserver")
        val offenders = mainSources()
            .filter { (_, text) -> stubPackages.any { text.contains(it) } }
            .map { (path, _) -> path }
        assertTrue(
            "src/main/kotlin 出现平台可选插件桩类型的声明：$offenders。" +
                "桩源码必须留在 src/mcpStub（桩产物只挂编译/测试 classpath、不进插件 jar）——" +
                "桩类随 main 输出入 jar 会与平台真类冲突（R5）",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `acp 桩源集三个类型齐备且位于 mcpStub`() {
        // 接线守卫：provider 实现引用的三个 ml-llm 类型必须在桩源集内可解析（少一个即 main 编译失败，
        // 但失败信息指向实现类而非缺桩，此断言把定位成本压到零）
        val expected = listOf(
            "src/mcpStub/kotlin/com/intellij/platform/acp/AcpAgentId.kt",
            "src/mcpStub/kotlin/com/intellij/ml/llm/agents/acp/client/AcpIdeaMcpTransport.kt",
            "src/mcpStub/kotlin/com/intellij/ml/llm/agents/acp/client/AcpIdeaMcpTransportProvider.kt",
        )
        val missing = expected.filterNot { repoRoot().resolve(it).isFile }
        assertTrue("acp 桩文件缺失：$missing（main 的 provider 实现将编译不过）", missing.isEmpty())
    }

    /** XML 注释剥离（`<!-- -->`）+ 行注释兜底——plugin.xml 的 depends 注释里提到实现类名属文档性
     *  记载，不构成解析期类引用（只可能漏报不误报）。 */
    private fun stripXmlComments(source: String): String =
        source
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }
}
