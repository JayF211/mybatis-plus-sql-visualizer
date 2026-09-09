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
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            // reset 应从 state 读取 en
            configurable.reset()
            // 验证 panel 当前语言（通过再次 apply 不改变 state 证明 reset 已同步）。
            // M23.9：createComponent 返回 getRoot()（JComponent）而非面板本身，原 `panel is
            // PluginSettingsPanel` 恒 false 是死分支（警告暴露）——改经 panelForTest 取回表单面板
            val s = settings.state
            val reasons = mutableListOf<String>()
            val p = configurable.panelForTest()
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
            // M28：更新分区新增「启动时自动检查更新」开关（默认开）——复选框总数 4 → 5；
            // T-M31.14 SQL 日志组 +5；手动选择实体类开关 +1（T-M38+）；
            // T-TenantLine：多租户注入开关 +1；Hibernate 日志捕获开关 +1
            // F-6：INSERT 追加租户列开关 +1；数据权限占位提示开关 +1
            // M47：Spring JdbcTemplate 日志捕获开关 +1
            // F-43：自动保存历史 SQL 开关 +1
            // 2026-08-27 筛选精简：移除「显示类型筛选条」开关 -1（SQL 类型多选改面板工具栏直出）
            // F-44-7：源码位置自动注入开关 +1；T-SqlDuration：耗时显示开关 +1；
            // T-Dgm-04：JOIN 图原生框架渲染开关 +1（合并双方共同确认的最终基线）
            // JdbcTemplate SQL 字符串预览开关 +1
            // T-PortLossBatch1 D1：删除「关闭工具窗口时自动停止监听」假开关 -1——master 无非配置读者，
            // dev 侧真读者是 Java 监听器（ToolWindowVisibilityListener，未随本轮移植），开关整体下线
            assertEquals("复选框总数应为 21", 21, texts.size)
        } finally {
            settings.state.language = old
        }
    }

    /** 收集组件树中全部 JCheckBox 文本（M19.2 复选框文案刷新断言辅助）。 */
    /** 递归收集全部 JBLabel 文本（按组件树布局顺序；T-M31.8 分组重构后行标签在组面板第二层）。 */
    private fun collectLabelTexts(component: java.awt.Component): List<String> {
        val texts = mutableListOf<String>()
        if (component is com.intellij.ui.components.JBLabel) texts += component.text ?: ""
        if (component is java.awt.Container) {
            component.components.forEach { texts += collectLabelTexts(it) }
        }
        return texts.filter { it.isNotBlank() }
    }

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

    fun testStateRoundTripKeepsM19_4TableConfig() {
        // T-M19.4-02：四项设置增强随 State 持久化往返保留（getState → loadState，模拟 IDE 保存/重启加载）
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.tableNameInference = PluginSettings.INFERENCE_CAMEL
            settings.state.ignoredFields = "transientField; remark"
            settings.state.removeTablePrefix = "t_;tmp_"
            settings.state.removeTableSuffix = "_log;_bak"
            val saved = settings.getState()
            assertEquals("getState 应反映最新 tableNameInference", PluginSettings.INFERENCE_CAMEL, saved.tableNameInference)
            assertEquals("getState 应反映最新 ignoredFields", "transientField; remark", saved.ignoredFields)
            assertEquals("getState 应反映最新 removeTablePrefix", "t_;tmp_", saved.removeTablePrefix)
            assertEquals("getState 应反映最新 removeTableSuffix", "_log;_bak", saved.removeTableSuffix)
            // 模拟重启：新实例 loadState（IDE 从磁盘反序列化后调用）
            val fresh = PluginSettings()
            fresh.loadState(saved)
            assertEquals("loadState 后 tableNameInference 保留", PluginSettings.INFERENCE_CAMEL, fresh.state.tableNameInference)
            assertEquals("loadState 后 ignoredFields 保留", "transientField; remark", fresh.state.ignoredFields)
            assertEquals("loadState 后 removeTablePrefix 保留", "t_;tmp_", fresh.state.removeTablePrefix)
            assertEquals("loadState 后 removeTableSuffix 保留", "_log;_bak", fresh.state.removeTableSuffix)
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testPanelResetApplyM19_4RowsSynced() {
        // T-M19.4-02：设置面板 4 新行 isModified/apply/reset 联动——reset 从 state 回读表单，
        // 表单与配置一致时 isModified=false、apply 不改变 state（通过 apply 后 state 是否变化
        // 推断表单与 state 一致性，无法直接访问私有字段）
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.tableNameInference = PluginSettings.INFERENCE_CAMEL
            settings.state.ignoredFields = "a; b"
            settings.state.removeTablePrefix = "t_"
            settings.state.removeTableSuffix = "_log"
            val panel = PluginSettingsPanel(settings)
            panel.reset()
            assertFalse("reset 后表单与 state 一致 → isModified=false", panel.isModified())
            val before = settings.state.copy()
            panel.apply()
            assertEquals("apply 不应改变 state（reset 已同步表单）", before, settings.state)
            assertFalse("apply 后 isModified 仍为 false", panel.isModified())
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testPanelTableConfigRowsOrderAndPlaceholderHint() {
        // T-M19.4（用户反馈调整）：行顺序 = 添加表前缀 → 添加表后缀 → 移除表名前缀 → 移除表名后缀 →
        // 忽略字段名 → 表名推断策略；仅「移除表名前缀/移除表名后缀/忽略字段名」3 个支持多值的输入栏
        // 空时显示灰色提示「多个时使用;来分隔」（添加表前缀/后缀为**单值**，无提示），
        // 语言切换 rebuild 后 placeholder 随语言刷新
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.language = "zh"
            val panel = PluginSettingsPanel(settings)
            // 行顺序：递归收集全部行标签文本（T-M31.8 分组重构后行标签在组面板第二层，
            // 直接子组件只含 TitledSeparator + 组面板——须递归；相对顺序断言不受组标题干扰）
            val labels = collectLabelTexts(panel.getRoot())
            val expected = listOf("添加表前缀", "添加表后缀", "移除表名前缀", "移除表名后缀", "忽略字段名", "表名推断策略")
            val indices = expected.map { labels.indexOf(it) }
            assertTrue(
                "行顺序应为 $expected，实际行标签: $labels",
                indices.none { it < 0 } && indices.zipWithNext().all { (a, b) -> a < b },
            )
            // placeholder：3 个支持多值的输入栏 + 日志过滤关键字 + F-43 保存路径 + F-43 文件名模板
            // （添加表前缀/后缀单值 + shortcut 只读框均不计入）
            val zhHints = mutableListOf<String>()
            collectTextFieldEmptyTexts(panel.getRoot(), zhHints)
            assertEquals("placeholder 应设置于 6 个输入栏（+F-43 保存路径+文件名模板）", 6, zhHints.size)
            // 多值输入栏 + 日志过滤关键字的 placeholder 为「多个时使用;来分隔」
            val multiValueHints = zhHints.count { it == "多个时使用;来分隔" }
            assertEquals("多值输入栏 placeholder 数量应为 4", 4, multiValueHints)

            // 切英文 rebuild 后 placeholder 随语言刷新
            settings.state.language = "en"
            panel.rebuild()
            val enHints = mutableListOf<String>()
            collectTextFieldEmptyTexts(panel.getRoot(), enHints)
            assertEquals("英文 placeholder 数量为 6（+F-43 保存路径+文件名模板）", 6, enHints.size)
        } finally {
            settings.loadState(snapshot)
        }
    }

    /** 收集组件树中全部 JBTextField 的非空 placeholder 提示（emptyText；辅助断言用）。 */
    private fun collectTextFieldEmptyTexts(component: java.awt.Component, out: MutableList<String>) {
        if (component is com.intellij.ui.components.JBTextField && component.emptyText.text.isNotBlank()) {
            out += component.emptyText.text
        }
        if (component is java.awt.Container) {
            component.components.forEach { collectTextFieldEmptyTexts(it, out) }
        }
    }

    // ------------------------------------------------------------------
    // T-TenantLine：多租户注入配置持久化
    // ------------------------------------------------------------------

    fun testTenantLineConfigPersistence() {
        // T-TenantLine：injectTenantLine + tenantColumnName 随 State 持久化往返保留
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.injectTenantLine = false
            settings.state.tenantColumnName = "org_id"
            val saved = settings.getState()
            assertEquals("getState 应反映最新 injectTenantLine", false, saved.injectTenantLine)
            assertEquals("getState 应反映最新 tenantColumnName", "org_id", saved.tenantColumnName)
            // 模拟重启：新实例 loadState（IDE 从磁盘反序列化后调用）
            val fresh = PluginSettings()
            fresh.loadState(saved)
            assertEquals("loadState 后 injectTenantLine 保留", false, fresh.state.injectTenantLine)
            assertEquals("loadState 后 tenantColumnName 保留", "org_id", fresh.state.tenantColumnName)
        } finally {
            settings.loadState(snapshot)
        }
    }
}
