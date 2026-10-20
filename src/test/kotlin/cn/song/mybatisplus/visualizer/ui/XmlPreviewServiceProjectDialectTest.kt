package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class XmlPreviewServiceProjectDialectTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
    }

    fun testXmlPreviewRetainsProjectDialectWithoutDynamicModel() {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.state.copy()
        try {
            settings.state.dialectAuto = false
            settings.state.dialect = "Oracle"
            val result = XmlPreviewService.preview(
                "<mapper namespace=\"demo.Mapper\"><select id=\"all\">SELECT * FROM t_biz</select></mapper>",
                "all", project,
            )
            assertNull(result.previewModel)
            assertEquals("SELECT * FROM t_biz", result.sql.trim())
            assertEquals("Oracle", result.effectiveDialect?.name)
        } finally {
            settings.loadState(snapshot)
        }
    }
}
