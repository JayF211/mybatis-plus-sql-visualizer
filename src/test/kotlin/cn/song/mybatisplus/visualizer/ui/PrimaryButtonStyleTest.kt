package cn.song.mybatisplus.visualizer.ui

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 主行动按钮样式的**公共入口**回归（UI-043 补强，复盘 P0-#5）。
 *
 * 「复制 SQL 并关闭」走 [SqlPreviewDialog.createJButtonForAction]，已在 `SqlPreviewDialogTest` 覆盖；
 * 但 [primaryButton] 是另一条独立入口——执行视图的「返回预览」按钮（`SqlPreviewPanelExecution.kt`
 * 里 `primaryButton(...)`）走它，此前零样式断言（`ConsoleHostTest` 只断言按钮存在与移除）。
 *
 * 两条入口共用 [applyPrimaryButtonStyle]，但入口本身可能被单独改坏（例如有人给 `primaryButton`
 * 加一段 `background = ...`），所以入口层也要钉一遍。
 */
class PrimaryButtonStyleTest : BasePlatformTestCase() {

    fun testPrimaryButtonUsesPlatformStyle() {
        val button = primaryButton("返回预览") {}
        assertTrue(
            "primaryButton 应打平台 styleDefault 标记（否则 LAF 不会画原生蓝色圆角）",
            button.getClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY) == java.lang.Boolean.TRUE,
        )
        assertFalse(
            "primaryButton 不得靠手工铺底色（会在圆角外溢出成方角蓝边）",
            button.isOpaque,
        )
    }

    fun testPrimaryButtonKeepsClickListener() {
        // 样式改造不得影响动作接线（`addActionListener` 在 apply 之后）
        var clicked = 0
        val button = primaryButton("x") { clicked++ }
        button.doClick()
        assertEquals("主按钮应保留点击回调", 1, clicked)
    }

    fun testApplyPrimaryButtonStyleIsIdempotent() {
        // 重复施加不应把 isOpaque 翻回 true（旧实现是 isOpaque = true，重复调用幂等但语义相反；
        // 新实现须保证连续调用后仍是「交给 LAF」）
        val button = javax.swing.JButton("y")
        applyPrimaryButtonStyle(button)
        applyPrimaryButtonStyle(button)
        assertTrue(
            "重复施加后仍应保持 styleDefault",
            button.getClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY) == java.lang.Boolean.TRUE,
        )
        assertFalse("重复施加后不得变回不透明", button.isOpaque)
    }
}
