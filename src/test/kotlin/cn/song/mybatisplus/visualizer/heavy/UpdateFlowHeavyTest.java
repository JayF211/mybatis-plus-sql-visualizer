package cn.song.mybatisplus.visualizer.heavy;

import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner;
import cn.song.mybatisplus.visualizer.update.PluginVersion;
import cn.song.mybatisplus.visualizer.update.UpdateApi;
import cn.song.mybatisplus.visualizer.update.UpdateSource;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 更新链路端到端测试（T-M28-06，Heavy）：本地 HttpServer 托管清单与插件包，
 * **真实网络栈**（HttpRequests → HTTP）走 check → 下载 → zip 校验 → 安装入口全链路。
 *
 * 安装终点断言：测试环境插件从 build/classes 加载（descriptorPath 不在插件目录内），
 * [PluginUpdateRunner] 的目录守卫应拒绝调度（防开发态误删构建产物）——
 * 即证明「下载成功 + zip 校验通过 + 安装入口触达」；真实安装调度（卸载/解压启动脚本）
 * 由 runIde 沙盒人工验证（T-M23.10 机制已实证）。
 */
public class UpdateFlowHeavyTest extends HeavyPlatformTestCase {

    private HttpServer server;
    private final AtomicInteger manifestHits = new AtomicInteger(0);
    private final AtomicInteger zipHits = new AtomicInteger(0);

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @Override
    protected void tearDown() throws Exception {
        server.stop(0);
        System.clearProperty(UpdateSource.MANIFEST_URL_OVERRIDE);
        super.tearDown();
    }

    private void serveManifest(String version, String downloadUrl) {
        server.createContext("/update.json", exchange -> {
            manifestHits.incrementAndGet();
            String body = """
                    {"version":"%s","downloadUrl":"%s","changeNotes":"e2e notes","publishDate":"2026-08-15"}
                    """.stripTrailing().formatted(version, downloadUrl);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, (long) body.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body.getBytes());
            }
        });
    }

    private void serveZip(boolean valid) {
        server.createContext("/plugin.zip", exchange -> {
            zipHits.incrementAndGet();
            byte[] bytes;
            if (valid) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (ZipOutputStream zip = new ZipOutputStream(bos)) {
                    zip.putNextEntry(new ZipEntry("lib/plugin.jar"));
                    zip.write(new byte[]{1, 2, 3});
                    zip.closeEntry();
                }
                bytes = bos.toByteArray();
            } else {
                bytes = "<html>error page</html>".getBytes();
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    public void testCheckDownloadInstallFullChainOverHttp() throws Exception {
        serveManifest("9999.1.1", base() + "/plugin.zip");
        serveZip(true);
        System.setProperty(UpdateSource.MANIFEST_URL_OVERRIDE, base() + "/update.json");

        // ① 真实 HTTP 检查：发现新版本
        PluginUpdateRunner.CheckResult result = PluginUpdateRunner.check(PluginVersion.current());
        assertTrue("应发现新版本，实际: " + result, result instanceof PluginUpdateRunner.CheckResult.Available);
        PluginUpdateRunner.CheckResult.Available avail = (PluginUpdateRunner.CheckResult.Available) result;
        assertEquals("9999.1.1", avail.manifest().version());

        // ② 真实 HTTP 下载 + zip 校验 + 安装入口触达（守卫对测试环境 descriptorPath=null
        //    已放行——T-M28.4 实证 2026.2 已安装插件 descriptorPath 为 null；install seam
        //    注入替身，避免测试沙盒真实调度卸载/解压）
        boolean[] installCalled = {false};
        PluginUpdateRunner.InstallResult install = PluginUpdateRunner.downloadAndInstall(
                avail.manifest(), null,
                (url, target) -> {
                    try {
                        UpdateApi.INSTANCE.download(url, target, null);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                (Path path) -> {
                    installCalled[0] = true;
                    return PluginUpdateRunner.InstallResult.InstalledRestartRequired.INSTANCE;
                });
        assertTrue("下载与校验通过后应触达安装入口（守卫不再误拒 null descriptorPath）", installCalled[0]);
        assertEquals(PluginUpdateRunner.InstallResult.InstalledRestartRequired.INSTANCE, install);

        assertEquals("清单应被拉取 1 次", 1, manifestHits.get());
        assertEquals("插件包应被下载 1 次", 1, zipHits.get());
    }

    @Test
    public void testInvalidZipDownloadIsRejectedOverHttp() throws Exception {
        serveManifest("9999.1.1", base() + "/plugin.zip");
        serveZip(false);
        System.setProperty(UpdateSource.MANIFEST_URL_OVERRIDE, base() + "/update.json");

        PluginUpdateRunner.CheckResult result = PluginUpdateRunner.check(PluginVersion.current());
        assertTrue(result instanceof PluginUpdateRunner.CheckResult.Available);
        PluginUpdateRunner.CheckResult.Available avail = (PluginUpdateRunner.CheckResult.Available) result;
        PluginUpdateRunner.InstallResult install = PluginUpdateRunner.downloadAndInstall(avail.manifest(), null);
        assertTrue("坏 zip 应被校验拒绝", install instanceof PluginUpdateRunner.InstallResult.Failed);
        PluginUpdateRunner.InstallResult.Failed failed = (PluginUpdateRunner.InstallResult.Failed) install;
        assertTrue(failed.message().contains("valid plugin archive"));
        // 校验失败后不得触达安装入口（zipHits=1 表示下载发生、安装未执行）
        assertEquals(1, zipHits.get());
    }
}
