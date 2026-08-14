package cn.song.mybatisplus.visualizer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * PluginBuildInfo 纯 JUnit 测试（T-M23.14）：
 *  - readMpvBuild：临时 jar 构造 → 属性读取 / 缺失属性 / 非法输入兜底
 *  - formatBuildTime：时间戳 → 本地可读时间 / 不可解析兜底
 *
 * 不依赖平台 fixture（无 PSI/索引），与 NamingStrategyTest 同风格。
 */
class PluginBuildInfoTest {

    /** 构造含指定 manifest 属性的临时 jar，返回 jar 文件（测试结束删除）。 */
    private fun tempJarWithManifest(attributes: Map<String, String>): File {
        val file = Files.createTempFile("mpv-build-test", ".jar").toFile()
        file.deleteOnExit()
        val manifest = Manifest().apply {
            // 必须显式设置 Manifest-Version：JDK 9+ JarOutputStream 在缺失版本号时
            // 静默丢弃全部属性（JDK 8 自动补 1.0，行为差异）；真实构建产物必有此属性
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            attributes.forEach { (k, v) -> mainAttributes[Attributes.Name(k)] = v }
        }
        JarOutputStream(file.outputStream(), manifest).use { /* 空 jar 亦可读 manifest */ }
        return file
    }

    @Test
    fun testReadMpvBuildFromJarManifest() {
        // 模拟 buildPlugin 产物：manifest 含 MPV-Build 毫秒时间戳
        val jar = tempJarWithManifest(mapOf("MPV-Build" to "1786700024611"))
        assertEquals("1786700024611", PluginBuildInfo.readMpvBuild(jar))
    }

    @Test
    fun testReadMpvBuildMissingAttribute() {
        // manifest 无 MPV-Build（如外部普通 jar）→ null
        val jar = tempJarWithManifest(mapOf("Manifest-Version" to "1.0"))
        assertNull(PluginBuildInfo.readMpvBuild(jar))
    }

    @Test
    fun testReadMpvBuildInvalidInput() {
        // 不存在文件 / 目录形态（runIde 开发 classpath 同构）→ null
        assertNull(PluginBuildInfo.readMpvBuild(File("no-such-file.jar")))
        val dir = Files.createTempDirectory("mpv-build-dir").toFile()
        dir.deleteOnExit()
        assertNull(PluginBuildInfo.readMpvBuild(dir))
    }

    @Test
    fun testFormatBuildTime() {
        // 1786700024611 ms = 2026-08-14 17:33:44（本地时区）；只校验可解析为规范格式
        val formatted = PluginBuildInfo.formatBuildTime("1786700024611")
        assertNotNull("时间戳应可格式化，实际: $formatted", formatted)
        assertTrue(
            "应为 yyyy-MM-dd HH:mm:ss，实际: $formatted",
            Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$").matches(formatted!!),
        )
        // 不可解析 → null（null 输入 / 非数字 / 溢出）
        assertNull(PluginBuildInfo.formatBuildTime(null))
        assertNull(PluginBuildInfo.formatBuildTime("not-a-timestamp"))
        assertNull(PluginBuildInfo.formatBuildTime("99999999999999999999"))
    }

    @Test
    fun testMpvBuildNullInTestEnvironment() {
        // 测试 classpath 为 classes 目录（非 jar）→ 当前环境构建号必然为 null（开发形态语义）
        assertNull(PluginBuildInfo.mpvBuild())
    }
}
