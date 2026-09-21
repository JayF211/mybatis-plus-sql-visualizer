// T-SyncK2JMaster115 自 master src/test/kotlin/cn/song/mybatisplus/visualizer/config/EditorShowWhitespacesSettingBindingTest.kt 移植
package cn.song.mybatisplus.visualizer.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 空白符可视化开关接线测试（T-PreviewWhitespaceToggle O-44a）：设置页「界面与操作」节复选框的
 * isModified/apply/reset 归属正确性——防「勾选后重开未勾选」（读写判定必须随 UI 分区）与
 * 「漏挂 isModified 改动被静默丢弃」两类历史事故回归（同 OnboardingAnchorTipSettingBindingTest 形态）。
 *
 * 出厂默认值（关）与「关闭态不设置编辑器属性」的语义在 EditorWhitespaceSettingsTest 覆盖。
 */
class EditorShowWhitespacesSettingBindingTest : BasePlatformTestCase() {

    fun `test开关勾选_检测到变化且apply写回`() {
        val settings = PluginSettings.getInstance()
        val oldEnabled = settings.state.editorShowWhitespaces
        try {
            settings.state.editorShowWhitespaces = false
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()

            configurable.reset()
            assertFalse("reset 后表单与配置一致（无变化）", configurable.isModified())

            panel.setEditorShowWhitespacesCheck(true)
            assertTrue("仅勾选空白符开关也应检测到变化（漏挂 isModified → Apply 不启用）", configurable.isModified())
            configurable.apply()
            assertTrue("apply 后 state.editorShowWhitespaces 应写回 true", settings.state.editorShowWhitespaces)

            panel.setEditorShowWhitespacesCheck(false)
            assertTrue("取消勾选同样应检测到变化", configurable.isModified())
            configurable.apply()
            assertFalse("apply 后 state 应回 false", settings.state.editorShowWhitespaces)

            configurable.reset()
            assertFalse("reset 后应回到与配置一致（无变化）", configurable.isModified())
        } finally {
            settings.state.editorShowWhitespaces = oldEnabled
        }
    }

    fun `test界面节勾选后重开_设置保持勾选_不回滚`() {
        // 用户踩坑回归形态：勾选 → apply → 重新打开设置页（reset 读 state）→ 仍勾选
        val settings = PluginSettings.getInstance()
        val oldEnabled = settings.state.editorShowWhitespaces
        try {
            settings.state.editorShowWhitespaces = true
            val first = AllSectionsConfigurable()
            first.createComponent()
            first.reset()
            first.panelForTest().setEditorShowWhitespacesCheck(false)
            first.apply()

            val reopened = AllSectionsConfigurable()
            reopened.createComponent()
            reopened.reset()
            assertFalse("重开设置页后开关应保持关闭（勾选不回滚）", reopened.isModified())
            reopened.panelForTest().apply()
            assertFalse("reset 后直接 apply 不应改变 state（表单已同步）", settings.state.editorShowWhitespaces)
        } finally {
            settings.state.editorShowWhitespaces = oldEnabled
        }
    }
}
