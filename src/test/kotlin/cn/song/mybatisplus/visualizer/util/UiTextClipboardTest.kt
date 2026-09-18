package cn.song.mybatisplus.visualizer.util

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.datatransfer.DataFlavor

/**
 * UiTextClipboard 文本复制收口测试（T-TestGuard2 G2-6，此前 0 测试）。
 *
 * T-UiNative P0 定案：剪贴板写入统一走本收口（进平台复制栈，Ctrl+Shift+V 可回贴）；
 * KDoc 明确不做 isUnitTestMode 短路——单元测试沙盒内 CopyPasteManager 可用且落剪贴板
 * 可读回（SqlClickPreview 等既有调用已验证），本测试把该前提本身锁成断言。
 *
 * 本类源码含 CopyPasteManager 字样，分片亲和规则自动固定第 1 片（Windows 剪贴板全机
 * 互斥，见 mpv-testing 机器级资源亲和），双开分片场景零维护。
 */
class UiTextClipboardTest : BasePlatformTestCase() {

    private fun clipboardText(): String? =
        CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)

    fun testCopy_写入后可读回() {
        UiTextClipboard.copy("SELECT 1 FROM dual")
        assertEquals("copy 后剪贴板应可读回原文", "SELECT 1 FROM dual", clipboardText())
    }

    fun testCopy_连续写入_后写覆盖前写() {
        UiTextClipboard.copy("first")
        UiTextClipboard.copy("second")
        assertEquals("连续复制应以后写为准", "second", clipboardText())
    }

    fun testCopy_空串与多行文本() {
        UiTextClipboard.copy("")
        assertEquals("空串也是合法写入（清空语义）", "", clipboardText())
        val multi = "UPDATE t SET a = 1\nWHERE id = 2"
        UiTextClipboard.copy(multi)
        assertEquals("多行 SQL 原样落剪贴板", multi, clipboardText())
    }
}
