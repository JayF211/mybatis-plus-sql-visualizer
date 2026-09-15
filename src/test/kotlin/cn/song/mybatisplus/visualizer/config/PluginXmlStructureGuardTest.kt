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
 *    当前架构为 applicationConfigurable 文件夹 + 子页，不注册 projectConfigurable；
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
        for ((name, xml) in strippedXmls()) {
            assertHolds(
                "$name 出现 projectConfigurable（防与 applicationConfigurable 同 id 同 key 套娃；" +
                    "如需项目级分区页，本守卫连同套娃断言一起改造）",
                !xml.contains("<projectConfigurable"),
            )
        }
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
        assertHolds("SQL Visualizer 子设置页注册数应为 5，实际 ${children.size}", children.size == 5)
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
}
