package cn.song.mybatisplus.visualizer.heavy

import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner
import cn.song.mybatisplus.visualizer.update.PluginVersion
import cn.song.mybatisplus.visualizer.update.UpdateSource
import com.intellij.testFramework.HeavyPlatformTestCase
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 更新链路端到端测试（T-M28-06，Heavy）：本地 HttpServer 托管清单与插件包，
 * **真实网络栈**（HttpRequests → HTTP）走 check → 下载 → zip 校验 → 安装入口全链路。
 *
 * 安装终点断言：测试环境插件从 build/classes 加载（descriptorPath 不在插件目录内），
 * [PlatformPluginInstaller] 的目录守卫应拒绝调度（防开发态误删构建产物）——
 * 即证明「下载成功 + zip 校验通过 + 安装入口触达」；真实安装调度（卸载/解压启动脚本）
 * 由 runIde 沙盒人工验证（T-M23.10 机制已实证）。
 */
class UpdateFlowHeavyTest : HeavyPlatformTestCase() {

    private lateinit var server: HttpServer
    private val manifestHits = AtomicInteger(0)
    private val zipHits = AtomicInteger(0)

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    override fun tearDown() {
        server.stop(0)
        System.clearProperty(UpdateSource.MANIFEST_URL_OVERRIDE)
        super.tearDown()
    }

    private fun serveManifest(version: String, downloadUrl: String) {
        server.createContext("/update.json") { exchange ->
            manifestHits.incrementAndGet()
            val body = """{"version":"$version","downloadUrl":"$downloadUrl","changeNotes":"e2e notes","publishDate":"2026-08-15"}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
    }

    private fun serveZip(valid: Boolean) {
        server.createContext("/plugin.zip") { exchange ->
            zipHits.incrementAndGet()
            val bytes = if (valid) {
                val bos = java.io.ByteArrayOutputStream()
                ZipOutputStream(bos).use { zip ->
                    zip.putNextEntry(ZipEntry("lib/plugin.jar"))
                    zip.write(byteArrayOf(1, 2, 3))
                    zip.closeEntry()
                }
                bos.toByteArray()
            } else {
                "<html>error page</html>".toByteArray()
            }
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun base(): String = "http://127.0.0.1:${server.address.port}"

    fun testCheckDownloadInstallFullChainOverHttp() {
        serveManifest("9999.1.1", "${base()}/plugin.zip")
        serveZip(valid = true)
        System.setProperty(UpdateSource.MANIFEST_URL_OVERRIDE, "${base()}/update.json")

        // ① 真实 HTTP 检查：发现新版本
        val result = PluginUpdateRunner.check(PluginVersion.current())
        assertTrue("应发现新版本，实际: $result", result is PluginUpdateRunner.CheckResult.Available)
        assertEquals("9999.1.1", (result as PluginUpdateRunner.CheckResult.Available).manifest.version)

        // ② 真实 HTTP 下载 + zip 校验 + 安装入口触达（守卫对测试环境 descriptorPath=null
        //    已放行——T-M28.4 实证 2026.2 已安装插件 descriptorPath 为 null；install seam
        //    注入替身，避免测试沙盒真实调度卸载/解压）
        var installCalled = false
        val install = PluginUpdateRunner.downloadAndInstall(
            result.manifest, null,
            install = {
                installCalled = true
                PluginUpdateRunner.InstallResult.InstalledRestartRequired
            },
        )
        assertTrue("下载与校验通过后应触达安装入口（守卫不再误拒 null descriptorPath）", installCalled)
        assertEquals(PluginUpdateRunner.InstallResult.InstalledRestartRequired, install)

        assertEquals("清单应被拉取 1 次", 1, manifestHits.get())
        assertEquals("插件包应被下载 1 次", 1, zipHits.get())
    }

    fun testInvalidZipDownloadIsRejectedOverHttp() {
        serveManifest("9999.1.1", "${base()}/plugin.zip")
        serveZip(valid = false)
        System.setProperty(UpdateSource.MANIFEST_URL_OVERRIDE, "${base()}/update.json")

        val result = PluginUpdateRunner.check(PluginVersion.current())
        assertTrue(result is PluginUpdateRunner.CheckResult.Available)
        val install = PluginUpdateRunner.downloadAndInstall(
            (result as PluginUpdateRunner.CheckResult.Available).manifest, null,
        )
        assertTrue("坏 zip 应被校验拒绝", install is PluginUpdateRunner.InstallResult.Failed)
        assertTrue(
            ((install as PluginUpdateRunner.InstallResult.Failed).message.contains("valid plugin archive")),
        )
        // 校验失败后不得触达安装入口（zipHits=1 表示下载发生、安装未执行）
        assertEquals(1, zipHits.get())
    }
}
