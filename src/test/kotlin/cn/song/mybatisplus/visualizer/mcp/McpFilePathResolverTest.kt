package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * T-McpFilePathUnify（O-70）filePath 统一解析单测（纯逻辑层，testFast 桶）。
 *
 * 覆盖三个带 filePath 的工具共用同一解析口径后的形态矩阵：项目相对路径（含反斜杠/前导斜杠/
 * 两侧空白）、项目内绝对路径、项目外绝对路径（含**前缀相似但不同根**的误判防护）、`..` 上跳、
 * 无项目根。安全边界（拒项目外与上跳）是
 * [cn.song.mybatisplus.visualizer.mcp.McpRenderInput.normalizeFilePath] 既有测试的延续。
 */
class McpFilePathResolverTest {

    private val base = "F:/proj/demo"

    @Test
    fun `相对路径_拼项目根并统一正斜杠`() {
        assertEquals("$base/src/a.xml", McpFilePathResolver.resolveInsideProject(base, "src/a.xml"))
        assertEquals("$base/src/a.xml", McpFilePathResolver.resolveInsideProject(base, "src\\a.xml"))
        assertEquals("$base/src/a.xml", McpFilePathResolver.resolveInsideProject(base, "/src/a.xml"))
        assertEquals("$base/src/a.xml", McpFilePathResolver.resolveInsideProject(base, "  src/a.xml  "))
    }

    @Test
    fun `项目内绝对路径_接受`() {
        assertEquals("$base/src/a.xml", McpFilePathResolver.resolveInsideProject(base, "F:/proj/demo/src/a.xml"))
        assertEquals("$base/src/a.xml", McpFilePathResolver.resolveInsideProject(base, "F:\\proj\\demo\\src\\a.xml"))
    }

    @Test
    fun `POSIX项目内绝对路径可用_项目外和相似前缀拒绝`() {
        val posixRoot = "/home/dev/project"
        assertEquals("$posixRoot/src/a.xml", McpFilePathResolver.resolveInsideProject(posixRoot, "/home/dev/project/src/a.xml"))
        assertEquals("$posixRoot/src/a.xml", McpFilePathResolver.resolveInsideProject(posixRoot, "src/a.xml"))
        assertNull(McpFilePathResolver.resolveInsideProject(posixRoot, "/home/dev/project-other/src/a.xml"))
        assertNull(McpFilePathResolver.resolveInsideProject(posixRoot, "/etc/passwd"))
        assertNull(McpFilePathResolver.resolveInsideProject(posixRoot, "/src/a.xml"))
    }

    @Test
    fun `UNC绝对路径不可伪装项目相对路径`() {
        assertNull(McpFilePathResolver.resolveInsideProject(base, "//server/share/a.xml"))
    }

    @Test
    fun `项目外绝对路径_拒绝`() {
        assertNull(McpFilePathResolver.resolveInsideProject(base, "C:/Windows/system32/drivers/etc/hosts"))
        assertNull(McpFilePathResolver.resolveInsideProject(base, "F:/other-project/src/a.xml"))
        // 前缀相似但不同根——startsWith 裸前缀会误判，必须按 `base/` 边界判
        assertNull(McpFilePathResolver.resolveInsideProject(base, "F:/proj/demo-other/src/a.xml"))
    }

    @Test
    fun `上跳_一律拒绝`() {
        assertNull(McpFilePathResolver.resolveInsideProject(base, "../etc/passwd"))
        assertNull(McpFilePathResolver.resolveInsideProject(base, "src/../../x.xml"))
        assertNull(McpFilePathResolver.resolveInsideProject(base, "F:/proj/demo/../secret.xml"))
    }

    @Test
    fun `无项目根或空入参_拒绝`() {
        assertNull(McpFilePathResolver.resolveInsideProject(null, "src/a.xml"))
        assertNull(McpFilePathResolver.resolveInsideProject(null, "F:/proj/demo/src/a.xml"))
        assertNull(McpFilePathResolver.resolveInsideProject(base, ""))
        assertNull(McpFilePathResolver.resolveInsideProject(base, "   "))
    }

    @Test
    fun `真实文件路径只接受项目目录内的常规文件`() {
        val root = Files.createTempDirectory("mcp-project-")
        val source = Files.createDirectory(root.resolve("src"))
        val inside = Files.writeString(source.resolve("a.xml"), "<mapper/>")
        val outside = Files.createTempFile("mcp-outside-", ".xml")
        try {
            assertEquals(inside.toRealPath().toString().replace('\\', '/'),
                McpFilePathResolver.canonicalProjectFile(root.toString(), inside.toString()))
            assertNull(McpFilePathResolver.canonicalProjectFile(root.toString(), outside.toString()))
            assertNull(McpFilePathResolver.canonicalProjectFile(root.toString(), source.toString()))
        } finally {
            Files.deleteIfExists(inside)
            Files.deleteIfExists(source)
            Files.deleteIfExists(root)
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `项目内链接指向项目外时拒绝`() {
        val root = Files.createTempDirectory("mcp-project-")
        val outside = Files.createTempFile("mcp-outside-", ".xml")
        val link = root.resolve("linked.xml")
        try {
            val created = runCatching { Files.createSymbolicLink(link, outside) }
            assumeTrue("当前系统无法创建符号链接：${created.exceptionOrNull()?.javaClass?.simpleName}", created.isSuccess)
            assertNull(McpFilePathResolver.canonicalProjectFile(root.toString(), link.toString()))
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(root)
            Files.deleteIfExists(outside)
        }
    }
}
