package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * 预览编辑器空态引导测试（T-UxOnboard T-UX-02）：placeholder 文案接线 i18n 键——
 * 与 LogToSqlPanelUiTest 的 T-UN-14 断言形态一致（键存在 + 面板取非空文案即证接线）。
 */
class SqlPreviewPanelEmptyHintTest : BasePlatformTestCase() {

    fun `test空态提示接线文案键_双语键存在且非空`() {
        val panel = SqlPreviewPanel(project)
        try {
            val hint = panel.previewEmptyHintForTest()
            assertEquals(
                "预览空态提示应接线文案键",
                MpSqlVisualizerBundle.message("panel.preview.emptyHint"),
                hint,
            )
            assertTrue("空态提示文案不应为空", hint.isNotBlank())
            // 键在英文 bundle 同步存在（message() 缺键兜底返回键名，非空但形态可辨）
            assertTrue(
                "缺键时 message() 兜底返回键名——文案不应为键名",
                hint != "panel.preview.emptyHint",
            )
        } finally {
            panel.clear()
        }
    }
}
