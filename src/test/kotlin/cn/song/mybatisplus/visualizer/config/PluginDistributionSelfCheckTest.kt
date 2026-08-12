package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件分发自检（M14-01）：从测试 classpath 校验打包关键内容，防打包遗漏回归。
 *
 * 覆盖：
 *  - plugin.xml 注册了设置页 configurable 与正确插件名；
 *  - 插件图标位于主 JAR 的 META-INF（官方规范约定路径）；
 *  - bundle 默认中文（settings 未注册时兜底简体中文，M14）。
 */
class PluginDistributionSelfCheckTest {

    private fun resourceText(path: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: error("资源缺失: $path")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun `pluginXml_注册设置页configurable`() {
        val xml = resourceText("META-INF/plugin.xml")
        assertTrue("应注册 PluginSettingsConfigurable", xml.contains("PluginSettingsConfigurable"))
        assertTrue("configurable 应挂在 tools 分组", xml.contains("parentId=\"tools\""))
        assertTrue("设置页 id 应存在", xml.contains("cn.song.mybatisplus.visualizer.settings"))
    }

    @Test
    fun `pluginXml_插件名统一为全名`() {
        val xml = resourceText("META-INF/plugin.xml")
        assertTrue("插件名应为 MyBatis-Plus SQL Visualizer", xml.contains("<name>MyBatis-Plus SQL Visualizer</name>"))
        assertTrue("不应残留旧名 MP SQL Visualizer", !xml.contains("MP SQL Visualizer"))
    }

    @Test
    fun `插件图标_位于主JAR的META-INF约定路径`() {
        // 官方规范：pluginIcon.svg 须在主 JAR 的 META-INF（无 <icon> 声明）
        assertNotNull("META-INF/pluginIcon.svg 应存在", javaClass.classLoader.getResource("META-INF/pluginIcon.svg"))
        assertNotNull("META-INF/pluginIcon_dark.svg 应存在", javaClass.classLoader.getResource("META-INF/pluginIcon_dark.svg"))
        val xml = resourceText("META-INF/plugin.xml")
        // 去掉注释后不应存在 <icon> 元素（约定路径方式，注释中的文字不算）
        val noComments = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue("不应声明 <icon> 自定义路径（约定路径方式）", !Regex("<icon[ >]").containsMatchIn(noComments))
    }

    @Test
    fun `bundle_默认中文_环境未注册时兜底简体中文`() {
        // 测试环境无 PluginSettings service → runCatching 兜底中文（用户要求默认中文）
        assertEquals("简体中文", MpSqlVisualizerBundle.message("settings.language.zh"))
        assertEquals("界面语言", MpSqlVisualizerBundle.message("settings.language"))
        assertEquals("修改快捷键…", MpSqlVisualizerBundle.message("settings.shortcut.modify"))
    }

    @Test
    fun `bundle_参数化文案_MessageFormat占位符`() {
        assertEquals("快捷键：Ctrl+Alt+S", MpSqlVisualizerBundle.message("settings.shortcut.current", "Ctrl+Alt+S"))
        assertEquals("动态条件: 条件文本", MpSqlVisualizerBundle.message("panel.condition.flag", "条件文本"))
    }
}
