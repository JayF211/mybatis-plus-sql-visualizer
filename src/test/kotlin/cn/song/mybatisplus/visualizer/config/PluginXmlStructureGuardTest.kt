package cn.song.mybatisplus.visualizer.config

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plugin.xml（含三个 optional 配置文件）结构守卫（T-TestGuard-02）。
 *
 * 收录的每条规则都对应一个「只有真机/Marketplace 才暴露」的历史坑，剥注释后做负向断言，
 * 防止同族问题复发：
 *  - `<listener topic=`：plugin.xml 不支持该注册形态（ProjectManagerListener 等须 messageBus
 *    编程注册——全局平台陷阱清单）；
 *  - `<action` 缺 `class=`：整个插件无法加载（bug-fixes/project-plugin-xml-kotlin-optional.md）；
 *  - description 内嵌外链 `<img`：Marketplace 302 防盗链全部图挂（pitfalls/pitfall-gitee-hotlink-302.md）；
 *  - `projectConfigurable` 与父级同名套娃（bug-fixes/project-settings-no-nested-configurable.md）；
 *    T-SettingsTruth P4 起项目级设置页合法注册（parentId=tools 顶层），守卫收窄为
 *    「不得挂应用级 configurable 之下 + id 不与 applicationConfigurable 重复」；
 *  - `completion.contributor` 缺 `language=`：全语言注册不被调度、静默失效
 *    （bug-fixes/project-native-sql-highlighting.md，M34 补全失效根因之一）；
 *  - `notificationGroup` 带 `displayName=`：NotificationGroupEP 无该属性（243 javap 实证），
 *    分组显示名必须走 bundle + key；
 *  - `<toolWindow` 带 `title=`：不支持该属性（bug-fixes/toolwindow-button-tooltip-mechanism.md）；
 *  - 设置子页面 `applicationConfigurable` 缺 `groupWeight` 或非声明序递减：平台 Weighted.COMPARATOR
 *    按 weight 降序优先、displayName 自然比较兜底，缺 weight 时中文按码点序重排，中英文子页
 *    顺序不一致（2026-09-15 真机发现，243 源码 + 262 字节码双核实）。
 *
 * 纯逻辑测试：从 classpath 读 META-INF 下全部 xml，先剥注释再断言（plugin.xml 注释里有历史
 * 注册段残段，不剥会误报）。
 */
class PluginXmlStructureGuardTest {

    private val xmlNames = listOf(
        "plugin.xml", "kotlin-optional.xml", "database-optional.xml", "diagram-optional.xml",
        "mcp-optional.xml",
    )

    private fun strippedXmls(): List<Pair<String, String>> = xmlNames.map { name ->
        val stream = javaClass.classLoader.getResourceAsStream("META-INF/$name")
            ?: error("资源缺失: META-INF/$name")
        val raw = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        name to raw.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    }

    private fun tagsOf(xml: String, tag: String): List<String> =
        Regex("<$tag\\b[^>]*>").findAll(xml).map { it.value }.toList()

    private fun assertHolds(description: String, condition: Boolean) =
        assertTrue(description, condition)

    // ------------------------------------------------------------------
    // 注册形态守卫
    // ------------------------------------------------------------------

    @Test
    fun `不存在listener_topic注册形态`() {
        for ((name, xml) in strippedXmls()) {
            assertHolds(
                "$name 含 <listener topic=…>（平台不支持，监听须 messageBus 编程注册）",
                !xml.contains("<listener topic="),
            )
        }
    }

    @Test
    fun `action元素必须带class属性`() {
        for ((name, xml) in strippedXmls()) {
            val bad = tagsOf(xml, "action").filter { !it.contains("class=") }
            assertHolds("$name 存在缺 class 属性的 <action>（整插件加载失败）：$bad", bad.isEmpty())
        }
    }

    @Test
    fun `description不得内嵌外链img`() {
        for ((name, xml) in strippedXmls()) {
            val desc = xml.substringAfter("<description", "").substringBefore("</description>")
            assertHolds("$name description 内嵌 <img>（Marketplace 302 防盗链图全挂，用文字+链接）", !desc.contains("<img"))
        }
    }

    @Test
    fun `不存在projectConfigurable注册`() {
        // T-SettingsTruth P4：项目级设置页已合法注册（parentId=tools 顶层，与全局页平级）。
        // 保留守卫但收窄为「防套娃」：projectConfigurable 不得挂在应用级 configurable
        // （id=cn.song.mybatisplus.visualizer.settings）之下——平台限制，项目级节点挂应用级
        // configurable 之下不渲染（bug-fixes/project-settings-no-nested-configurable.md）。
        for ((name, xml) in strippedXmls()) {
            val bad = tagsOf(xml, "projectConfigurable").filter {
                it.contains("""parentId="cn.song.mybatisplus.visualizer.settings"""")
            }
            assertHolds(
                "$name 存在挂在应用级 configurable 之下的 projectConfigurable（防套娃；" +
                    "项目级须挂 tools 顶层与全局页平级）：$bad",
                bad.isEmpty(),
            )
            // 项目级注册与全局页 id 不得重复
            val projIds = tagsOf(xml, "projectConfigurable")
                .mapNotNull { Regex("""id="([^"]+)"""").find(it)?.groupValues?.get(1) }
            val appIds = tagsOf(xml, "applicationConfigurable")
                .mapNotNull { Regex("""id="([^"]+)"""").find(it)?.groupValues?.get(1) }
            val dup = projIds.intersect(appIds.toSet())
            assertHolds("$name projectConfigurable 与 applicationConfigurable id 重复：$dup", dup.isEmpty())
        }
    }

    @Test
    fun `项目级设置页已注册且挂tools顶层`() {
        // T-SettingsTruth P4 接线守卫：ProjectPluginSettingsConfigurable 必须注册为
        // projectConfigurable 且 parentId=tools（防回归为死类）
        val xml = strippedXmls().first { it.first == "plugin.xml" }.second
        val proj = tagsOf(xml, "projectConfigurable").filter {
            it.contains("""instance="cn.song.mybatisplus.visualizer.config.ProjectPluginSettingsConfigurable"""")
        }
        assertHolds("项目级设置页 projectConfigurable 注册数应为 1，实际 ${proj.size}", proj.size == 1)
        assertHolds(
            "项目级设置页必须挂 tools 顶层（不得挂应用级 configurable 之下）",
            proj[0].contains("""parentId="tools""""),
        )
        assertHolds("项目级设置页须声明 bundle+key 本地化", proj[0].contains("bundle=") && proj[0].contains("key="))
    }

    @Test
    fun `completion_contributor必须声明language属性`() {
        for ((name, xml) in strippedXmls()) {
            val bad = tagsOf(xml, "completion.contributor").filter { !it.contains("language=") }
            assertHolds(
                "$name 存在缺 language 属性的 completion.contributor（全语言注册不被调度、静默失效）：$bad",
                bad.isEmpty(),
            )
        }
    }

    @Test
    fun `notificationGroup不得带displayName属性`() {
        for ((name, xml) in strippedXmls()) {
            val bad = tagsOf(xml, "notificationGroup").filter { it.contains("displayName=") }
            assertHolds("$name notificationGroup 带 displayName（该属性不存在，显示名走 bundle+key）：$bad", bad.isEmpty())
        }
    }

    @Test
    fun `toolWindow不得带title属性`() {
        for ((name, xml) in strippedXmls()) {
            val bad = tagsOf(xml, "toolWindow").filter { it.contains("title=") }
            assertHolds("$name toolWindow 带 title 属性（平台不支持）：$bad", bad.isEmpty())
        }
    }

    @Test
    fun `设置子页面groupWeight按声明序严格递减`() {
        val xml = strippedXmls().first { it.first == "plugin.xml" }.second
        val children = tagsOf(xml, "applicationConfigurable")
            .filter { it.contains("""parentId="cn.song.mybatisplus.visualizer.settings"""") }
        assertHolds(
            "SQL Visualizer 子设置页注册数应为 7（主五页 + shortcuts/mcp，T-ShortcutSettings），实际 ${children.size}",
            children.size == 7,
        )
        val weights = children.map { tag ->
            val id = Regex("""id="([^"]+)"""").find(tag)!!.groupValues[1]
            val w = Regex("""groupWeight="(\d+)"""").find(tag)?.groupValues?.get(1)?.toIntOrNull()
            assertHolds(
                "子设置页 $id 缺 groupWeight（平台按 displayName 自然比较兜底排序，中文名码点序" +
                    "与英文不一致导致中英文子页顺序错乱）",
                w != null,
            )
            id to w!!
        }
        for (i in 1 until weights.size) {
            val (prevId, prevW) = weights[i - 1]
            val (id, w) = weights[i]
            assertHolds(
                "子设置页 groupWeight 须按声明序严格递减（weight 降序=树中显示顺序）：$prevId=$prevW 应大于 $id=$w",
                prevW > w,
            )
        }
    }

    // ------------------------------------------------------------------
    // 存在性守卫（防整体误删）
    // ------------------------------------------------------------------

    @Test
    fun `主描述文件保留核心注册锚点`() {
        val xml = strippedXmls().first { it.first == "plugin.xml" }.second
        assertTrue("applicationConfigurable 注册不应丢失", xml.contains("applicationConfigurable"))
        assertTrue("可选 Kotlin 依赖声明不应丢失", xml.contains("supportsKotlinPluginMode"))
        assertTrue("errorHandler EP 不应丢失（Internal Errors 上报按钮）", xml.contains("<errorHandler"))
    }

    // ------------------------------------------------------------------
    // T-McpToolset：MCP 可选依赖结构守卫
    // ------------------------------------------------------------------

    @Test
    fun `mcp可选依赖声明与工具集注册成对存在`() {
        val pluginXml = strippedXmls().first { it.first == "plugin.xml" }.second
        val mcpXml = strippedXmls().first { it.first == "mcp-optional.xml" }.second
        assertHolds(
            "plugin.xml 缺 mcpServer 可选依赖声明（MCP 工具注册失效）",
            pluginXml.contains("""<depends optional="true" config-file="mcp-optional.xml">com.intellij.mcpServer</depends>"""),
        )
        assertHolds(
            "mcp-optional.xml 缺 mcpServer.mcpToolset 注册（工具不上线）",
            mcpXml.contains("mcpServer.mcpToolset"),
        )
        // T-McpToolset2 / T-MTS2-00：一工具一 toolset 类（平台 per-tool 门控只有 toolset 粒度
        // isEnabled）——十工具逐类断言，新工具漏注册即红
        val expectedToolsets = listOf(
            "RenderSqlTool", "SchemaContextTool", "ExplainTool",
            "RenderLogsTool", "CheckSqlTool", "RollbackPlanTool", "PerfAdviceTool",
            "CrudSqlTool", "ReverseSqlTool", "MapperCheckTool",
        )
        val missing = expectedToolsets.filter { !mcpXml.contains("cn.song.mybatisplus.visualizer.mcp.$it") }
        assertHolds(
            "mcp-optional.xml 缺 toolset 注册：$missing（清单与 McpToolGate.TOOL_NAMES 对齐）",
            missing.isEmpty(),
        )
    }

    @Test
    fun `主描述文件不得直接引用mcpserver类型`() {
        // 可选依赖铁律（M17 database-optional 同款）：主描述文件不得直接引用可选插件类型/注册——
        // mcpServer 缺席（243/未装）时整份主文件仍须可加载，mcp 引用只允许出现在 mcp-optional.xml
        val pluginXml = strippedXmls().first { it.first == "plugin.xml" }.second
        assertHolds(
            "plugin.xml 主文件出现 mcpToolset/mcpServer EP 注册（须收敛进 mcp-optional.xml）",
            !pluginXml.contains("mcpToolset"),
        )
    }
}
