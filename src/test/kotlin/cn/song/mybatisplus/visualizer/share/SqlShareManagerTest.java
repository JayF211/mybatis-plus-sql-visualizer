package cn.song.mybatisplus.visualizer.share;

import org.junit.Assert.assertEquals;
import org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 分享管理器单元测试（F-8）。
 *
 * 说明：剪贴板操作在 headless 环境下可能不可用，本测试聚焦文件输出正确性；
 * 剪贴板复制路径由 copyToClipboard 私有方法覆盖，不直接断言剪贴板内容。
 */
public class SqlShareManagerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void saveHtmlReport_写入文件_UTF8编码() throws Exception {
        SqlShareManager manager = new SqlShareManager(null);
        String html = "<!DOCTYPE html><html><body><pre>SELECT 1</pre></body></html>";
        File file = new File(tempFolder.newFolder(), "report.html");
        manager.saveHtmlReport(html, file.getAbsolutePath());
        assertTrue(file.exists());
        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertEquals(html, content);
    }

    @Test
    public void saveHtmlReport_嵌套目录_自动创建() throws Exception {
        SqlShareManager manager = new SqlShareManager(null);
        String html = "<html><body>test</body></html>";
        File nested = new File(tempFolder.newFolder("a"), "b/c/report.html");
        manager.saveHtmlReport(html, nested.getAbsolutePath());
        assertTrue(nested.exists());
        assertEquals(html, Files.readString(nested.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void saveHtmlReport_覆盖写入() throws Exception {
        SqlShareManager manager = new SqlShareManager(null);
        File file = new File(tempFolder.newFolder(), "r.html");
        manager.saveHtmlReport("<html>v1</html>", file.getAbsolutePath());
        manager.saveHtmlReport("<html>v2</html>", file.getAbsolutePath());
        assertEquals("<html>v2</html>", Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void saveHtmlReport_含中文_正确保留() throws Exception {
        SqlShareManager manager = new SqlShareManager(null);
        String html = "<html><body>来源：UserService.java</body></html>";
        File file = new File(tempFolder.newFolder(), "r.html");
        manager.saveHtmlReport(html, file.getAbsolutePath());
        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertTrue(content.contains("UserService.java"));
        assertEquals(html, content);
    }
}
