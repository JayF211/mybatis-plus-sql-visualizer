package cn.song.mybatisplus.visualizer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自身版本读取单测（T-M28-01）：plugin.xml 文本解析（纯函数）+ classloader 读取
 * （测试 classpath 含构建产物 resources 的 META-INF/plugin.xml，IPGP 已注入版本）。
 */
class PluginVersionTest {

    @Test
    fun parseVersionExtractsChildElement() {
        val xml = """
            <idea-plugin>
                <id>cn.song.test</id>
                <version>2026.8.14</version>
                <vendor>t</vendor>
            </idea-plugin>
        """.trimIndent()
        assertEquals("2026.8.14", PluginVersion.parseVersion(xml))
    }

    @Test
    fun parseVersionIgnoresRootAttribute() {
        // `<idea-plugin version="2">` 是属性不是版本元素，不得误匹配
        val xml = """<idea-plugin version="2"><version>2026.8.15</version></idea-plugin>"""
        assertEquals("2026.8.15", PluginVersion.parseVersion(xml))
    }

    @Test
    fun parseVersionMissingOrBlank() {
        assertNull(PluginVersion.parseVersion("<idea-plugin></idea-plugin>"))
        assertNull(PluginVersion.parseVersion("""<idea-plugin><version>  </version></idea-plugin>"""))
        assertNull(PluginVersion.parseVersion(""))
    }

    @Test
    fun currentReadsProcessedPluginXml() {
        val version = PluginVersion.current()
        // 测试 classpath 含 src/main/resources 处理后的 META-INF/plugin.xml（patchPluginXml 注入版本）
        assertTrue("expect recognizable version, got: $version", VersionComparator.isRecognizable(version))
        // 三段日期基号 + 可选内测后缀（Gitee 抢先体验通道 -beta.N，见版本号规范）
        assertTrue(version!!.matches(Regex("""\d{4}\.\d{1,2}\.\d{1,2}(-beta\.\d+)?""")))
    }
}
