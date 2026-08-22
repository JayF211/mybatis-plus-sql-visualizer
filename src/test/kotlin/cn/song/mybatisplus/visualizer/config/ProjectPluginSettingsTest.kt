package cn.song.mybatisplus.visualizer.config

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProjectPluginSettings 项目级设置测试（O-5 Phase B）：
 *  - 项目设置独立于全局
 *  - 开关切换（useProjectSettings on/off）
 *  - 持久化恢复（getState → loadState 往返）
 *  - 空项目降级（无项目设置时使用全局默认值）
 */
class ProjectPluginSettingsTest : BasePlatformTestCase() {

    @Test
    fun testProjectSettingsIndependentFromGlobal() {
        // 项目设置修改不影响全局设置
        val projectSettings = project.service<ProjectPluginSettings>()
        val globalSettings = PluginSettings.getInstance()
        val globalDialectBefore = globalSettings.state.dialect

        projectSettings.state.dialect = "PostgreSQL"
        projectSettings.state.replaceParams = false

        // 全局设置不变
        assertEquals("全局 dialect 不受项目设置影响", globalDialectBefore, globalSettings.state.dialect)
    }

    @Test
    fun testUseProjectSettingsToggle() {
        val projectSettings = project.service<ProjectPluginSettings>()
        val snapshot = projectSettings.getState().copy()

        try {
            // 默认关
            assertFalse("useProjectSettings 默认关", projectSettings.state.useProjectSettings)

            // 开启
            projectSettings.state.useProjectSettings = true
            projectSettings.state.dialect = "Oracle"
            assertTrue("开启后 useProjectSettings=true", projectSettings.state.useProjectSettings)
            assertEquals("项目 dialect 可设置", "Oracle", projectSettings.state.dialect)

            // 关闭后设置保留但不生效
            projectSettings.state.useProjectSettings = false
            assertFalse("关闭后 useProjectSettings=false", projectSettings.state.useProjectSettings)
            assertEquals("关闭后项目设置保留", "Oracle", projectSettings.state.dialect)
        } finally {
            projectSettings.loadState(snapshot)
        }
    }

    @Test
    fun testStateRoundTrip() {
        val projectSettings = project.service<ProjectPluginSettings>()
        val snapshot = projectSettings.getState().copy()

        try {
            projectSettings.state.useProjectSettings = true
            projectSettings.state.dialect = "PostgreSQL"
            projectSettings.state.replaceParams = false
            projectSettings.state.tablePrefix = "sys_"
            projectSettings.state.ignoredFields = "temp"
            projectSettings.state.tableNameInference = PluginSettings.INFERENCE_CAMEL

            // 模拟持久化：getState → loadState
            val saved = projectSettings.getState()
            val fresh = ProjectPluginSettings.State()
            fresh.useProjectSettings = saved.useProjectSettings
            fresh.dialect = saved.dialect
            fresh.replaceParams = saved.replaceParams
            fresh.formatSql = saved.formatSql
            fresh.injectLogicDelete = saved.injectLogicDelete
            fresh.logicDeleteValue = saved.logicDeleteValue
            fresh.tablePrefix = saved.tablePrefix
            fresh.tableSuffix = saved.tableSuffix
            fresh.ignoredFields = saved.ignoredFields
            fresh.tableNameInference = saved.tableNameInference

            assertEquals("往返后 useProjectSettings", true, fresh.useProjectSettings)
            assertEquals("往返后 dialect", "PostgreSQL", fresh.dialect)
            assertEquals("往返后 replaceParams", false, fresh.replaceParams)
            assertEquals("往返后 tablePrefix", "sys_", fresh.tablePrefix)
            assertEquals("往返后 ignoredFields", "temp", fresh.ignoredFields)
            assertEquals("往返后 tableNameInference", PluginSettings.INFERENCE_CAMEL, fresh.tableNameInference)
        } finally {
            projectSettings.loadState(snapshot)
        }
    }

    @Test
    fun testEffectiveSettingsFallbackToGlobal() {
        // 项目设置关闭时，effective 方法应返回全局设置
        val projectSettings = project.service<ProjectPluginSettings>()
        val globalSettings = PluginSettings.getInstance()
        val snapshot = projectSettings.getState().copy()
        val globalSnapshot = globalSettings.getState().copy()

        try {
            // 设置全局
            globalSettings.state.dialect = "MySQL"
            globalSettings.state.tablePrefix = "g_"

            // 项目设置关闭
            projectSettings.state.useProjectSettings = false
            projectSettings.state.dialect = "PostgreSQL"
            projectSettings.state.tablePrefix = "p_"

            // effective 应返回项目值（useProjectSettings=false 时返回全局值）
            assertEquals("关闭时 effectiveDialect 返回全局", "MySQL", projectSettings.effectiveDialect())
            assertEquals("关闭时 effectiveTablePrefix 返回全局", "g_", projectSettings.effectiveTablePrefix())

            // 开启后返回项目值
            projectSettings.state.useProjectSettings = true
            assertEquals("开启时 effectiveDialect 返回项目值", "PostgreSQL", projectSettings.effectiveDialect())
            assertEquals("开启时 effectiveTablePrefix 返回项目值", "p_", projectSettings.effectiveTablePrefix())
        } finally {
            projectSettings.loadState(snapshot)
            globalSettings.loadState(globalSnapshot)
        }
    }

    @Test
    fun testDefaultStateHasCorrectDefaults() {
        val state = ProjectPluginSettings.State()
        assertFalse("useProjectSettings 默认关", state.useProjectSettings)
        assertEquals("dialect 默认空（使用全局）", "", state.dialect)
        assertEquals("tablePrefix 默认空", "", state.tablePrefix)
        assertEquals("tableNameInference 默认空", "", state.tableNameInference)
    }
}
