package cn.song.mybatisplus.visualizer.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 分享管理器单元测试（F-8）。
 *
 * 说明：剪贴板操作在 headless 环境下可能不可用，本测试聚焦文件输出正确性；
 * 剪贴板复制路径由 [copyToClipboard] 私有方法覆盖，不直接断言剪贴板内容。
 */
class SqlShareManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `saveHtmlReport_写入文件_UTF8编码`() {
        val manager = SqlShareManager(project = null)
        val html = "<!DOCTYPE html><html><body><pre>SELECT 1</pre></body></html>"
        val file = File(tempFolder.newFolder(), "report.html")
        manager.saveHtmlReport(html, file.absolutePath)
        assertTrue(file.exists())
        val content = file.readText(Charsets.UTF_8)
        assertEquals(html, content)
    }

    @Test
    fun `saveHtmlReport_嵌套目录_自动创建`() {
        val manager = SqlShareManager(project = null)
        val html = "<html><body>test</body></html>"
        val nested = File(tempFolder.newFolder("a"), "b/c/report.html")
        manager.saveHtmlReport(html, nested.absolutePath)
        assertTrue(nested.exists())
        assertEquals(html, nested.readText(Charsets.UTF_8))
    }

    @Test
    fun `saveHtmlReport_覆盖写入`() {
        val manager = SqlShareManager(project = null)
        val file = File(tempFolder.newFolder(), "r.html")
        manager.saveHtmlReport("<html>v1</html>", file.absolutePath)
        manager.saveHtmlReport("<html>v2</html>", file.absolutePath)
        assertEquals("<html>v2</html>", file.readText(Charsets.UTF_8))
    }

    @Test
    fun `saveHtmlReport_含中文_正确保留`() {
        val manager = SqlShareManager(project = null)
        val html = "<html><body>来源：UserService.java</body></html>"
        val file = File(tempFolder.newFolder(), "r.html")
        manager.saveHtmlReport(html, file.absolutePath)
        val content = file.readText(Charsets.UTF_8)
        assertTrue(content.contains("UserService.java"))
        assertEquals(html, content)
    }
}
