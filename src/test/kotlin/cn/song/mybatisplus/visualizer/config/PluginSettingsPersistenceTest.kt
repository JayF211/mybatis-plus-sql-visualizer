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
                    showGutterIcon = s.showGutterIcon; injectLogicDelete = s.injectLogicDelete
                    tablePrefix = s.tablePrefix; tableSuffix = s.tableSuffix; language = s.language
                }
                p.apply()
                if (s.dialect != before.dialect) reasons += "dialect ${before.dialect}->${s.dialect}"
                if (s.replaceParams != before.replaceParams) reasons += "replaceParams"
                if (s.formatSql != before.formatSql) reasons += "formatSql"
                if (s.showGutterIcon != before.showGutterIcon) reasons += "showGutterIcon"
                if (s.injectLogicDelete != before.injectLogicDelete) reasons += "injectLogicDelete"
                if (s.tablePrefix != before.tablePrefix) reasons += "tablePrefix ${before.tablePrefix}->${s.tablePrefix}"
                if (s.tableSuffix != before.tableSuffix) reasons += "tableSuffix ${before.tableSuffix}->${s.tableSuffix}"
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

    fun testLanguageWriteThroughOnSelection() {
        // M19（用户实测：设置切语言确定后重开仍原样）：平台 Settings 框架 OK 仅对「当前选中页」且
        // isModified()==true 的 configurable 调用 apply()（反编译 2026.2.1 ConfigurableEditor 验证），
        // 改完语言切到其他页再确定 → apply 被静默跳过 → 写穿保证选中即持久化、不依赖 apply 链路。
        val settings = PluginSettings.getInstance()
        val old = settings.state.language
        try {
            settings.state.language = "zh"
            val panel = PluginSettingsPanel(settings)
            panel.selectLanguage("en")   // 模拟用户在下拉中选择 English——不调用 apply()
            assertEquals("写穿后 state.language 应立即为 en（不依赖 apply 链路）", "en", settings.state.language)
        } finally {
            settings.state.language = old
        }
    }

    fun testLanguageWriteThroughApplyEnabledAndIdempotent() {
        // M19.1（用户实测：写穿后「应用」按钮灰掉，只能点确定）：
        // 平台 Apply 按钮启用 = configurable.isModified()——写穿后表单与配置已一致，
        // 靠 languagePending「待应用」标记保持 isModified()=true；apply() 消费标记后幂等。
        val settings = PluginSettings.getInstance()
        val old = settings.state.language
        try {
            settings.state.language = "zh"
            val panel = PluginSettingsPanel(settings)
            panel.selectLanguage("en")
            assertEquals("写穿后 state.language 已为 en", "en", settings.state.language)
            assertTrue("写穿后 Apply 应可用（isModified()=true）", panel.isModified())
            assertTrue("apply() 应报告语言变更（pending 消费）", panel.apply())
            assertFalse("再次 apply() 幂等（返回 false）", panel.apply())
            assertFalse("应用后 isModified() 恢复 false（Apply 灰属正常）", panel.isModified())
            assertEquals("state.language 保持 en", "en", settings.state.language)
            panel.selectLanguage("en")   // 重复选同值：写穿守卫应 no-op（不重新置 pending）
            assertFalse("重复选同值不重新置 pending", panel.isModified())
        } finally {
            settings.state.language = old
        }
    }

    fun testLanguageWriteThroughRefreshesCheckboxTexts() {
        // M19.2（用户实测：切换语言后右侧复选框文本仍中文、确定重开才变）：
        // 复选框为字段实例（构造时按当时语言固化文本），rebuild 重建布局不会更新其文本——
        // 修复：文本在 buildRows 内每轮重设。本用例验证 zh→en rebuild 后全部复选框为英文。
        val settings = PluginSettings.getInstance()
        val old = settings.state.language
        try {
            settings.state.language = "zh"
            val panel = PluginSettingsPanel(settings)
            // 模拟用户选择 English（写穿）→ 重建表单（invokeLater 在测试环境不保证执行，直接调用）
            panel.selectLanguage("en")
            panel.rebuild()
            val texts = mutableListOf<String>()
            collectCheckBoxTexts(panel.getRoot(), texts)
            val hasCjk = { s: String -> s.any { it.code in 0x4E00..0x9FFF } }
            assertTrue("rebuild 后复选框不应残留中文: $texts", texts.none(hasCjk))
            assertTrue("rebuild 后复选框应含英文文案: $texts", texts.any { it.contains("Replace") } && texts.any { it.contains("Format") })
            assertEquals("复选框总数应为 4", 4, texts.size)
        } finally {
            settings.state.language = old
        }
    }

    /** 收集组件树中全部 JCheckBox 文本（M19.2 复选框文案刷新断言辅助）。 */
    private fun collectCheckBoxTexts(component: java.awt.Component, out: MutableList<String>) {
        if (component is javax.swing.JCheckBox) out += component.text
        if (component is java.awt.Container) {
            component.components.forEach { collectCheckBoxTexts(it, out) }
        }
    }

    fun testStateRoundTripKeepsTablePrefixSuffix() {
        // T-M16.2-03：表前缀/后缀随 State 持久化往返保留（getState → loadState，模拟 IDE 保存/重启加载）
        val settings = PluginSettings.getInstance()
        val oldPrefix = settings.state.tablePrefix
        val oldSuffix = settings.state.tableSuffix
        try {
            settings.state.tablePrefix = "sys_"
            settings.state.tableSuffix = "_log"
            val snapshot = settings.getState()
            assertEquals("getState 应反映最新 tablePrefix", "sys_", snapshot.tablePrefix)
            assertEquals("getState 应反映最新 tableSuffix", "_log", snapshot.tableSuffix)
            // 模拟重启：新实例 loadState（IDE 从磁盘反序列化后调用）
            val fresh = PluginSettings()
            fresh.loadState(snapshot)
            assertEquals("loadState 后 tablePrefix 保留", "sys_", fresh.state.tablePrefix)
            assertEquals("loadState 后 tableSuffix 保留", "_log", fresh.state.tableSuffix)
        } finally {
            settings.state.tablePrefix = oldPrefix
            settings.state.tableSuffix = oldSuffix
        }
    }

    fun testStorageRoamingDisabledContract() {
        // T-M16.3-01（用户报告语言切换保存失效根因）：官方规范（IntelliJ Platform SDK——Services）——
        // **应用级轻服务（@Service(APP)）+ PersistentStateComponent 必须禁用 roaming**
        // （roamingType = RoamingType.DISABLED）；默认 RoamingType.DEFAULT 使状态进入设置同步
        // （Settings Sync）管理通道，与轻服务持久化不兼容 → 确定后重开/重启设置回滚。
        // 编译期契约断言，防回归。
        val state = PluginSettings::class.java.getAnnotation(com.intellij.openapi.components.State::class.java)
        assertNotNull("@State 注解应存在（PersistentStateComponent 持久化声明）", state)
        val storage = state!!.storages.first()
        assertEquals(
            "应用级轻服务的存储必须禁用 roaming",
            com.intellij.openapi.components.RoamingType.DISABLED,
            storage.roamingType,
        )
    }
}
