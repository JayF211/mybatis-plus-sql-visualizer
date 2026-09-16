package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

/**
 * T-ExecCopyFix：复制错误按钮点击反馈测试（Bug 1 回归）。
 *
 * 用户实测：点击「复制错误信息」无任何反馈（复制已成功，但按钮静默）。
 * 修复后对齐 SqlClickPreview.flashCopied 对号语义：复制成功 → 按钮文本/图标短暂
 * 变为「已复制」+ 对号，一次性 Timer 到期恢复原文案。
 *
 * 覆盖：
 *  - SqlResultPanel：showError → 点击复制按钮 → 剪贴板持有完整错误文本、按钮文本变 panel.copied；
 *  - Timer 到期恢复原文案（不残留「已复制」——按钮复用，残留会误导下次复制）；
 *  - SqlPreviewPanel：copyError() 无警示返回 false（无警示时点击不应闪对号——
 *    按钮本就不可见，守卫防止未来可见性逻辑变化时空闪）。
 *
 * 剪贴板为系统级共享资源，setUp 快照 tearDown 恢复（SqlPreviewPanelTest 同款）。
 */
class CopyFeedbackUiTest : BasePlatformTestCase() {

    private var clipboardSnapshot: Transferable? = null

    override fun setUp() {
        super.setUp()
        clipboardSnapshot = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
    }

    override fun tearDown() {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(clipboardSnapshot, null) }
        super.tearDown()
    }

    /**
     * 读剪贴板文本（T-UiNative P0）：复制收口走平台 CopyPasteManager，headless 测试环境下
     * 其内容仅存平台句柄、不回写系统剪贴板（旧行为是直写 Toolkit 同步可见）——
     * 断言改读 [CopyPasteManager.getContents]，与生产「非 headless 时写穿系统剪贴板」一致。
     */
    private fun clipboardText(): String? = runCatching {
        CopyPasteManager.getInstance()
            .getContents<String>(DataFlavor.stringFlavor)
    }.getOrNull()

    private fun waitForEdt() = UIUtil.dispatchAllInvocationEvents()

    private fun copyButton(panel: SqlResultPanel): javax.swing.JButton =
        collectComponents(panel).filterIsInstance<javax.swing.JButton>()
            .first { it.text == MpSqlVisualizerBundle.message("panel.copyError") }

    private fun collectComponents(c: java.awt.Component): List<java.awt.Component> {
        val list = mutableListOf<java.awt.Component>(c)
        if (c is java.awt.Container) for (child in c.components) list += collectComponents(child)
        return list
    }

    /**
     * 点击复制按钮 → 剪贴板收到完整错误文本 + 按钮即时反馈「已复制」。
     * Timer 未到期前手动恢复：Swing Timer 走 EDT 队列，测试里不等 1500ms——
     * 反馈态断言后直接校验恢复语义（第二用例）。
     */
    fun testCopyButtonClickPutsErrorInClipboardAndFlashesCopied() {
        val panel = SqlResultPanel()
        try {
            val raw = "Connection refused: boom <&>"
            panel.showError(raw)
            waitForEdt()

            val button = copyButton(panel)
            val textBefore = button.text
            button.doClick()
            waitForEdt()

            assertEquals("复制应把完整错误文本放入剪贴板", raw, clipboardText())
            assertEquals(
                "按钮文本应即时切换为 panel.copied（已复制）",
                MpSqlVisualizerBundle.message("panel.copied"),
                button.text,
            )
            assertTrue("按钮 tooltip 应同步反馈", button.toolTipText == MpSqlVisualizerBundle.message("panel.copied"))
            // 恢复后的目标态：原文案（Timer 到期后回到这里，第二用例验证）
            assertEquals("反馈前的按钮文案应被记录用于恢复", MpSqlVisualizerBundle.message("panel.copyError"), textBefore)
        } finally {
            panel.clear()
        }
    }

    /**
     * 一次性 Timer 到期后按钮恢复原文案——不残留「已复制」。
     * 不真等 1500ms：把等待收敛为时间窗内轮询 EDT 事件（Timer 的-fire 也在 EDT 队列里，
     * dispatchAllInvocationEvents 每轮把到期的 Timer 事件执行掉）。
     */
    fun testFlashRestoresOriginalTextAfterTimer() {
        val panel = SqlResultPanel()
        try {
            panel.showError("timeout after 30s")
            waitForEdt()

            val button = copyButton(panel)
            val original = button.text
            button.doClick()
            waitForEdt()
            assertEquals(MpSqlVisualizerBundle.message("panel.copied"), button.text)

            val deadline = System.currentTimeMillis() + 3000
            while (button.text == MpSqlVisualizerBundle.message("panel.copied") && System.currentTimeMillis() < deadline) {
                waitForEdt()
                Thread.sleep(50)
            }
            assertEquals(
                "Timer 到期后按钮应恢复原文案，不残留「已复制」",
                original,
                button.text,
            )
        } finally {
            panel.clear()
        }
    }

    /**
     * SqlPreviewPanel.copyError()：无警示时返回 false（不闪对号、不动剪贴板），
     * 有警示时复制并返回 true。反馈接线在按钮回调里判断返回值，false 不触发 flash。
     * 剪贴板先植入哨兵文本，「不动」断言 = 哨兵原样保留（不受系统剪贴板既有内容影响）。
     */
    fun testPreviewPanelCopyErrorReturnsFalseWithoutWarning() {
        val sentinel = "__copyfix_sentinel__"
        // T-UiNative P0：哨兵植入与断言同走平台 CopyPasteManager（headless 下内容存平台句柄）
        CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(sentinel))
        val panel = SqlPreviewPanel(project)
        panel.clear()
        assertTrue("无警示时 copyError 应返回 false（不触发反馈）", !panel.copyError())
        assertEquals("无警示时不应动剪贴板（哨兵原样保留）", sentinel, clipboardText())
        panel.dispose()
    }

    /**
     * 有警示时 copyError 复制完整警示文本并返回 true——按钮回调据此闪对号。
     */
    fun testPreviewPanelCopyErrorCopiesWarningAndReturnsTrue() {
        val panel = SqlPreviewPanel(project)
        // 移植注：k2j 侧 PreviewResult 为 Java 构造器（禁命名实参）——20 参位置实参，其余取默认空值
        val result = cn.song.mybatisplus.visualizer.model.PreviewResult(
            "SELECT 1",
            "SELECT 1",
            emptyList(),
            null,
            null,
            "tbl",
            "UPDATE without WHERE",
            emptyList(),
            mapOf(),
            null,
            null,
            emptyList(),
            emptyList(),
            null,
            null,
            mapOf(),
            null,
            null,
            null,
            null,
        )
        panel.showResult(result)
        waitForEdt()
        try {
            assertTrue("有警示时 copyError 应返回 true", panel.copyError())
            assertEquals("应复制完整警示文本", "UPDATE without WHERE", clipboardText())
            assertNotNull("警示态复制按钮应可见", panel.errorCopyButtonForTest()?.isVisible)
        } finally {
            panel.clear()
            panel.dispose()
        }
    }
}
