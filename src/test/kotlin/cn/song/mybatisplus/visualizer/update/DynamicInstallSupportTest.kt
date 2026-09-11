package cn.song.mybatisplus.visualizer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 动态安装门面单测（T-DynUpdate F1）：zip 前置校验全分支——插件 xml 定位（直属/IPGP lib 布局）、
 * id 匹配、version 提取、坏包拒绝。纯 java.util.zip，不触平台反射与 EDT 编排
 * （install 编排层沿用任务惯例由 runIde 沙盒端到端验证）。
 */
class DynamicInstallSupportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pluginXml = """
        <idea-plugin>
            <id>cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer</id>
            <name>SQL Visualizer</name>
            <version>2026.9.11-beta.1</version>
        </idea-plugin>
    """.trimIndent()

    /** IPGP buildPlugin 布局：zip 顶层目录 + lib/插件 jar（plugin.xml 在 jar 内）。 */
    private fun buildIpgpLayoutZip(target: Path, xml: String = pluginXml) {
        ZipOutputStream(target.toFile().outputStream().buffered()).use { zip ->
            val jarBytes = java.io.ByteArrayOutputStream().also { jarOut ->
                ZipOutputStream(jarOut).use { jar ->
                    jar.putNextEntry(ZipEntry("META-INF/plugin.xml"))
                    jar.write(xml.toByteArray())
                    jar.closeEntry()
                }
            }.toByteArray()
            zip.putNextEntry(ZipEntry("mybatis-plus-sql-visualizer/lib/mybatis-plus-sql-visualizer-2026.9.11-beta.1.jar"))
            zip.write(jarBytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("mybatis-plus-sql-visualizer/lib/jsqlparser-4.9.jar"))
            zip.write(ByteArray(0))
            zip.closeEntry()
        }
    }

    @Test
    fun validateArtifactAcceptsIpgpLayout() {
        val zip = tmp.root.toPath().resolve("ok.zip")
        buildIpgpLayoutZip(zip)
        val result = DynamicInstallSupport.validateArtifact(zip)
        assertTrue("IPGP 布局应校验通过：$result", result is DynamicInstallSupport.ArtifactCheck.Ok)
        assertEquals("2026.9.11-beta.1", (result as DynamicInstallSupport.ArtifactCheck.Ok).version)
    }

    @Test
    fun validateArtifactAcceptsDirectDescriptorLayout() {
        // 直属布局：META-INF/plugin.xml 在 zip 顶层（解包态目录压缩形态）
        val zip = tmp.root.toPath().resolve("direct.zip")
        ZipOutputStream(zip.toFile().outputStream().buffered()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("META-INF/plugin.xml"))
            zipOut.write(pluginXml.toByteArray())
            zipOut.closeEntry()
        }
        val result = DynamicInstallSupport.validateArtifact(zip)
        assertTrue("直属 plugin.xml 应校验通过：$result", result is DynamicInstallSupport.ArtifactCheck.Ok)
    }

    @Test
    fun validateArtifactRejectsWrongPluginId() {
        val zip = tmp.root.toPath().resolve("wrong-id.zip")
        val foreignXml = pluginXml
            .replace("cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer", "com.example.other")
        buildIpgpLayoutZip(zip, foreignXml)
        val result = DynamicInstallSupport.validateArtifact(zip)
        assertTrue("错包应被拒：$result", result is DynamicInstallSupport.ArtifactCheck.Invalid)
        assertTrue((result as DynamicInstallSupport.ArtifactCheck.Invalid).reason.contains("id mismatch"))
    }

    @Test
    fun validateArtifactRejectsMissingVersion() {
        val zip = tmp.root.toPath().resolve("no-version.zip")
        buildIpgpLayoutZip(zip, pluginXml.replace("<version>2026.9.11-beta.1</version>", ""))
        val result = DynamicInstallSupport.validateArtifact(zip)
        assertTrue(result is DynamicInstallSupport.ArtifactCheck.Invalid)
        assertTrue((result as DynamicInstallSupport.ArtifactCheck.Invalid).reason.contains("version"))
    }

    @Test
    fun validateArtifactRejectsMissingDescriptor() {
        val zip = tmp.root.toPath().resolve("no-xml.zip")
        ZipOutputStream(zip.toFile().outputStream().buffered()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("mybatis-plus-sql-visualizer/lib/random.jar"))
            zipOut.write(ByteArray(0))
            zipOut.closeEntry()
        }
        val result = DynamicInstallSupport.validateArtifact(zip)
        assertTrue(result is DynamicInstallSupport.ArtifactCheck.Invalid)
        assertTrue((result as DynamicInstallSupport.ArtifactCheck.Invalid).reason.contains("not found"))
    }

    @Test
    fun validateArtifactRejectsUnreadableFile() {
        val zip = tmp.root.toPath().resolve("corrupt.zip")
        java.nio.file.Files.write(zip, "not a zip at all".toByteArray())
        val result = DynamicInstallSupport.validateArtifact(zip)
        assertTrue("坏包应拒绝且不抛出：$result", result is DynamicInstallSupport.ArtifactCheck.Invalid)
        assertTrue((result as DynamicInstallSupport.ArtifactCheck.Invalid).reason.contains("not readable"))
    }

    @Test
    fun validateArtifactReadsVersionAcrossLines() {
        // DOTALL 提取：version 标签跨行形态
        val zip = tmp.root.toPath().resolve("multiline.zip")
        val multilineXml = pluginXml.replace(
            "<version>2026.9.11-beta.1</version>",
            "<version>\n        2026.9.11-beta.2\n    </version>",
        )
        buildIpgpLayoutZip(zip, multilineXml)
        val result = DynamicInstallSupport.validateArtifact(zip)
        assertEquals("2026.9.11-beta.2", (result as DynamicInstallSupport.ArtifactCheck.Ok).version)
    }
}
