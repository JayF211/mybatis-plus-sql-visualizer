package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.sun.net.httpserver.HttpServer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JLabel
import org.junit.Assert.assertThrows

class CommunityDialogTest : BasePlatformTestCase() {

    fun test两张卡同时展示且顺序与官网一致() {
        val dialog = CommunityDialog(project) { error("测试不得连接官网") }
        try {
            assertEquals(2, dialog.cardsForTest.size)
            val cards = dialog.cardsForTest
            assertTrue(components(cards[0]).filterIsInstance<JLabel>().any {
                it.text == MpSqlVisualizerBundle.message("log.community.author")
            })
            assertTrue(components(cards[1]).filterIsInstance<JLabel>().any {
                it.text == MpSqlVisualizerBundle.message("log.community.group")
            })
            assertTrue(components(cards[0]).filterIsInstance<JLabel>().any {
                it.text?.contains(MpSqlVisualizerBundle.message("log.community.author.desc")) == true
            })
            assertTrue(components(cards[1]).filterIsInstance<JLabel>().any {
                it.text?.contains(MpSqlVisualizerBundle.message("log.community.group.desc")) == true
            })
            assertEquals(180, dialog.qrLabelsForTest[0].preferredSize.width)
            assertEquals(MpSqlVisualizerBundle.message("log.community.openSite"), dialog.openSiteButtonForTest.text)
        } finally {
            dialog.disposeIfNeeded()
        }
    }

    fun test一张加载失败不影响另一张二维码() {
        val finished = CountDownLatch(2)
        val image = BufferedImage(220, 220, BufferedImage.TYPE_INT_RGB)
        val dialog = CommunityDialog(project) { url ->
            try {
                if (url == CommunityDialog.GROUP_QR_URL) throw IOException("offline")
                image
            } finally {
                finished.countDown()
            }
        }
        try {
            dialog.startLoading()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((dialog.qrLabelsForTest[0].icon == null || dialog.qrLabelsForTest[1].text != MpSqlVisualizerBundle.message("log.community.loadFailed")) && System.nanoTime() < deadline) {
                UIUtil.dispatchAllInvocationEvents()
            }
            assertNotNull(dialog.qrLabelsForTest[0].icon)
            assertNull(dialog.qrLabelsForTest[1].icon)
            assertEquals(MpSqlVisualizerBundle.message("log.community.loadFailed"), dialog.qrLabelsForTest[1].text)
        } finally {
            dialog.disposeIfNeeded()
        }
    }

    fun test关闭后晚到的加载结果不得更新组件() {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val dialog = CommunityDialog(project) {
            started.countDown()
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw IOException("timeout")
                BufferedImage(220, 220, BufferedImage.TYPE_INT_RGB)
            } finally {
                finished.countDown()
            }
        }
        try {
            dialog.startLoading()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            dialog.disposeIfNeeded()
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            dialog.applyImageResult(dialog.qrLabelsForTest[0], Result.success(BufferedImage(220, 220, BufferedImage.TYPE_INT_RGB)))
            UIUtil.dispatchAllInvocationEvents()
            assertTrue(dialog.qrLabelsForTest.all { it.icon == null })
        } finally {
            release.countDown()
            dialog.disposeIfNeeded()
        }
    }

    fun test二维码下载字节与解码尺寸有上限() = verifyQrBounds()

    private fun verifyQrBounds() {
        assertThrows(IOException::class.java) {
            CommunityQrImages.readBounded(ByteArrayInputStream(ByteArray(256 * 1024 + 1)))
        }
        assertThrows(IOException::class.java) { CommunityQrImages.decode("not a png".toByteArray()) }
        val huge = ByteArrayOutputStream().also { out ->
            ImageIO.write(BufferedImage(1025, 1, BufferedImage.TYPE_INT_RGB), "png", out)
        }.toByteArray()
        assertThrows(IOException::class.java) { CommunityQrImages.decode(huge) }
        val valid = ByteArrayOutputStream().also { out ->
            ImageIO.write(BufferedImage(220, 220, BufferedImage.TYPE_INT_RGB), "png", out)
        }.toByteArray()
        val decoded = CommunityQrImages.decode(valid)
        assertEquals(220, decoded.width)
        assertEquals(180, CommunityQrImages.scaled(decoded).width)
    }

    fun test仅在用户请求时通过HTTP读取二维码并拒绝错误响应() = verifyHttpResponses()

    private fun verifyHttpResponses() {
        val valid = ByteArrayOutputStream().also { out ->
            ImageIO.write(BufferedImage(220, 220, BufferedImage.TYPE_INT_RGB), "png", out)
        }.toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok") { exchange ->
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, valid.size.toLong())
            exchange.responseBody.use { it.write(valid) }
        }
        server.createContext("/error") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.createContext("/oversized") { exchange ->
            val body = ByteArray(256 * 1024 + 1)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals(220, CommunityQrImages.fetch("$base/ok").width)
            assertThrows(IOException::class.java) { CommunityQrImages.fetch("$base/error") }
            assertThrows(IOException::class.java) { CommunityQrImages.fetch("$base/oversized") }
        } finally {
            server.stop(0)
        }
    }

    private fun components(component: Component): List<Component> = listOf(component) +
        (component as? Container)?.components.orEmpty().flatMap(::components)
}
