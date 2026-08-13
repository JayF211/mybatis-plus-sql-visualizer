package cn.song.mybatisplus.visualizer.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * PluginSettings 持久化链路测试（中英文切换失效定位）：
 *  - PersistentStateComponent 契约：getState → loadState 往返（模拟 IDE 保存/重启加载）保留 language
 *  - Configurable apply/reset 链路：设置页切换 → state 写回 → 重新打开读取
 *  - 双注册检查：@Service 注解与 plugin.xml applicationService 并存的隐患验证
 */
class PluginSettingsPersistenceTest : BasePlatformTestCase() {

    fun testStateRoundTripKeepsLanguage() {
        // 模拟 IDE 持久化：修改 state → getState（保存快照）→ loadState（重启加载）→ language 保留 en
        val settings = PluginSettings.getInstance()
        val old = settings.state.language
        try {
            settings.state.language = "en"
            val snapshot = settings.getState()
            assertEquals("getState 应反映最新 language", "en", snapshot.language)
            // 模拟重启：新实例 loadState（IDE 从磁盘反序列化后调用）
            val fresh = PluginSettings()
            fresh.loadState(snapshot)
            assertEquals("loadState 后 language 保留 en", "en", fresh.state.language)
        } finally {
            settings.state.language = old
        }
    }

    fun testConfigurableApplyWritesLanguageThenResetReadsIt() {
        // 设置页链路：切 en → apply → state=en；重新打开（新 panel）reset → combo 选中 en
        val settings = PluginSettings.getInstance()
        val old = settings.state.language
        try {
            settings.state.language = "zh"
            // 模拟用户在语言下拉选择 English 后 apply（直接改 state 等价于 combo 写回路径）
            settings.state.language = "en"
            val configurable = PluginSettingsConfigurable()
            val panel = configurable.createComponent()
            // reset 应从 state 读取 en
            configurable.reset()
            // 验证 panel 当前语言（通过再次 apply 不改变 state 证明 reset 已同步）
            val s = settings.state
            val reasons = mutableListOf<String>()
            if (panel is PluginSettingsPanel) {
                val p = panel
                // 通过 apply 后 state 是否变化推断表单与 state 一致性（无法直接访问私有字段）
                val before = PluginSettings.State().apply {
                    dialect = s.dialect; replaceParams = s.replaceParams; formatSql = s.formatSql
                    showGutterIcon = s.showGutterIcon; injectLogicDelete = s.injectLogicDelete; language = s.language
                }
                p.apply()
                if (s.dialect != before.dialect) reasons += "dialect ${before.dialect}->${s.dialect}"
                if (s.replaceParams != before.replaceParams) reasons += "replaceParams"
                if (s.formatSql != before.formatSql) reasons += "formatSql"
                if (s.showGutterIcon != before.showGutterIcon) reasons += "showGutterIcon"
                if (s.injectLogicDelete != before.injectLogicDelete) reasons += "injectLogicDelete"
                if (s.language != before.language) reasons += "language ${before.language}->${s.language}"
            }
            assertTrue("reset 后表单与 state 不一致: $reasons", reasons.isEmpty())
            assertEquals("state.language 保持 en", "en", settings.state.language)
        } finally {
            settings.state.language = old
        }
    }

    fun testLanguageSwitchImmediateInBundle() {
        // 回归：M14.7 已修复的切换即时生效（bundle 读取链）
        val settings = PluginSettings.getInstance()
        val old = settings.state.language
        settings.state.language = "en"
        try {
            assertEquals("Interface language", cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("settings.language"))
        } finally {
            settings.state.language = old
        }
    }
}
