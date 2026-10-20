package cn.song.mybatisplus.visualizer.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element

class ExplainPlanRetentionSettingsTest : BasePlatformTestCase() {
    fun test旧渲染配置缺保留字段默认关闭且显式值序列化往返() {
        val old = Element("State").addContent(Element("option").setAttribute("name", "explainStructuredRender").setAttribute("value", "true"))
        val restored = XmlSerializer.deserialize(old, PluginSettings.State::class.java)
        assertTrue(restored.explainStructuredRender)
        assertFalse(restored.explainPlanRetentionEnabled)
        assertFalse(PluginSettings.State().explainPlanRetentionEnabled)
        restored.explainPlanRetentionEnabled = true
        assertTrue(XmlSerializer.deserialize(XmlSerializer.serialize(restored), PluginSettings.State::class.java).explainPlanRetentionEnabled)
    }

    fun test真实设置控件默认未勾选_事前披露_apply_reset() {
        val settings = PluginSettings.getInstance()
        val before = settings.state.copy()
        try {
            settings.loadState(PluginSettings.State(explainStructuredRender = true))
            val panel = PluginSettingsPanel(settings, setOf(PluginSettingsPanel.SECTION_PREVIEW))
            assertFalse(panel.explainPlanRetentionCheck.isSelected)
            assertTrue(panel.explainPlanRetentionCheck.isVisible)
            assertNotNull(panel.explainPlanRetentionCheck.parent)
            val disclosure = panel.explainPlanRetentionDisclosure
            assertTrue(disclosure.isVisible)
            assertNotNull(disclosure.parent)
            assertTrue(disclosure.preferredSize.width > 0)
            assertTrue(disclosure.text.contains("mp-sql-visualizer.xml"))
            assertTrue(disclosure.text.contains("APP") || disclosure.text.contains("application-level"))
            assertTrue(disclosure.text.contains("未经脱敏") || disclosure.text.contains("unmasked"))
            assertTrue(disclosure.text.contains("清除预览历史") || disclosure.text.contains("Clear preview history"))
            assertTrue(disclosure.text.contains("凭据") || disclosure.text.contains("credentials"))
            panel.explainPlanRetentionCheck.doClick()
            assertFalse("勾选未 Apply 不能授权", settings.state.explainPlanRetentionEnabled)
            assertTrue(panel.isModified())
            panel.reset()
            assertFalse(panel.explainPlanRetentionCheck.isSelected)
            panel.explainPlanRetentionCheck.doClick()
            panel.apply()
            assertTrue(settings.state.explainPlanRetentionEnabled)
            panel.explainPlanRetentionCheck.doClick()
            assertTrue("关闭也需遵守 Apply", settings.state.explainPlanRetentionEnabled)
            panel.reset()
            assertTrue(panel.explainPlanRetentionCheck.isSelected)
            panel.explainPlanRetentionCheck.doClick()
            panel.apply()
            assertFalse(settings.state.explainPlanRetentionEnabled)
            assertTrue("渲染开关独立保留", settings.state.explainStructuredRender)
        } finally { settings.loadState(before) }
    }
}
