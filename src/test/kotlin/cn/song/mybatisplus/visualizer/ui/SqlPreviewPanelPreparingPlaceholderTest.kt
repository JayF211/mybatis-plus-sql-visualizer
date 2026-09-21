// T-SyncK2JMaster115 自 master src/test/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelPreparingPlaceholderTest.kt 移植
package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.awt.Component
import java.awt.Container

/**
 * T-PrewarmBudget：预热等待期的编辑区占位（2026-09-20 据实测新增）。
 *
 * 背景：装配延后到预热回调时，编辑区原本是**纯空面板**——真机连拍实测亮像素计数 0，冷启动空窗
 * 584~926ms 肉眼可见（见 `docs/验收文档/20260920-预热耗时采样与预算建议.md`）。抬高
 * `PREWARM_WAIT_MS` 会拉长这段等待，故一并补占位。本用例锁两条：占位能显示、装配后被替换掉。
 *
 * 覆盖边界：占位分支（`resolvePreviewEditorSource` 返回 false）在单元测试模式下不可达
 * （测试走同步有界等待），故这里直调显示方法 + 走一次真实装配收口，验证「显示」与「被替换」两段。
 */
class SqlPreviewPanelPreparingPlaceholderTest : BasePlatformTestCase() {

    private fun waitForEdt() = UIUtil.dispatchAllInvocationEvents()

    // K2J 互操作：master 侧 PreviewResult 的具名实参 + 缺省值 → k2j Java 7 参便捷构造器位置实参。
    private fun result() = PreviewResult(
        "SELECT id\nFROM user\nWHERE username = ?",
        "SELECT id FROM user WHERE username = ?",
        listOf("username"),
        null,
        WrapperType.LAMBDA_QUERY,
        "user",
        listOf("id", "username"),
    )

    /** 递归收集容器树里所有 JLabel 的文本（占位标签在 GridBagLayout 包装盒内，非直接子件）。 */
    private fun collectLabelTexts(c: Component): List<String> {
        val out = mutableListOf<String>()
        if (c is javax.swing.JLabel) c.text?.let { out += it }
        if (c is Container) c.components.forEach { out += collectLabelTexts(it) }
        return out
    }

    private fun preparingText(): String = MpSqlVisualizerBundle.message("panel.preview.preparing")

    fun test等待期占位_显示后编辑区含提示文案() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showPreparingPlaceholderForTest()
            waitForEdt()
            val texts = collectLabelTexts(panel.editorHolderForTest())
            assertTrue("编辑区应出现「准备中」占位，实得: $texts", texts.contains(preparingText()))
        } finally {
            panel.clear()
        }
    }

    fun test等待期占位_装配后被替换掉() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showPreparingPlaceholderForTest()
            waitForEdt()
            assertTrue(
                "前提：占位已显示",
                collectLabelTexts(panel.editorHolderForTest()).contains(preparingText()),
            )

            // 走真实装配收口（assemblePreviewEditor 的 editorHolder.removeAll() 即替换点）
            panel.showResult(result())
            waitForEdt()

            val texts = collectLabelTexts(panel.editorHolderForTest())
            assertFalse("装配后占位必须已被替换掉，实得: $texts", texts.contains(preparingText()))
            assertTrue("装配后编辑区应已有编辑器组件", panel.editorHolderForTest().componentCount > 0)
        } finally {
            panel.clear()
        }
    }
}
