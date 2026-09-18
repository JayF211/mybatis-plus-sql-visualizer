package cn.song.mybatisplus.visualizer.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JTextField

/**
 * 项目级设置页测试（F-44 续 + T-SettingsTruth P4 面板扩容）：
 *  - createComponent 构建「使用项目设置」开关 + 十项可覆盖字段（State 十字段一一对应）
 *  - 构造后 isModified=false；各控件改动后 isModified 逐项比对为真（T-RA-04 起无标志位）
 *  - apply 写回 ProjectPluginSettings 且 isModified 复位；reset 回灌 state 值
 *  - 空值语义：state null/空字段时控件灌全局值；字符串字段 apply 时 trim
 * 通过组件遍历取控件，无需新增生产缝。tearDown 还原项目/全局设置状态。
 */
class ProjectPluginSettingsConfigurableTest : BasePlatformTestCase() {

    private var savedProjectState: ProjectPluginSettings.State? = null
    private var savedGlobalState: PluginSettings.State? = null

    override fun setUp() {
        super.setUp()
        savedProjectState = ProjectPluginSettings.getInstance(myFixture.project).state.copy()
        savedGlobalState = PluginSettings.getInstance().state.copy()
    }

    override fun tearDown() {
        ProjectPluginSettings.getInstance(myFixture.project).loadState(savedProjectState!!)
        PluginSettings.getInstance().loadState(savedGlobalState!!)
        super.tearDown()
    }

    private fun all(c: java.awt.Component): List<java.awt.Component> {
        val list = mutableListOf(c)
        if (c is java.awt.Container) c.components.forEach { list.addAll(all(it)) }
        return list
    }

    private fun newConfig(): ProjectPluginSettingsConfigurable =
        ProjectPluginSettingsConfigurable(myFixture.project)

    private fun clicks(check: JCheckBox, selected: Boolean) {
        if (check.isSelected != selected) check.doClick()
    }

    fun testCreateComponent_含使用项目设置开关与十项可覆盖字段() {
        val cfg = newConfig()
        val comp = cfg.createComponent()
        assertNotNull(comp)
        val checks = all(comp).filterIsInstance<JCheckBox>()
        // 开关 + 参数替换 + 格式化 + 逻辑删除注入 = 4 个 checkbox
        assertEquals("应有 4 个 checkbox（开关 + 3 个覆盖项）", 4, checks.size)
        val combos = all(comp).filterIsInstance<JComboBox<*>>()
        // 方言下拉 + 推断策略下拉
        assertEquals("应有 2 个下拉（方言 + 推断策略）", 2, combos.size)
        assertTrue("方言下拉应有可选项", combos[0].itemCount > 0)
        assertEquals("推断策略下拉应有 SNAKE/CAMEL 两项", 2, combos[1].itemCount)
        // 20260918 用户真机反馈回归守卫：下拉项须为**本地化文案**，不得把枚举原值当显示项
        // （原实现 comboBox(listOf(INFERENCE_SNAKE, INFERENCE_CAMEL)) 致中文界面显示裸值
        //  `SNAKE`/`CAMEL`，与全局设置页同项文案不一致）。断言显示文本 == 全局面板同一组 bundle 键。
        val inferenceLabels = (0 until combos[1].itemCount).map { combos[1].getItemAt(it).toString() }
        assertEquals(
            "推断策略下拉项文案须复用全局面板 bundle 键",
            listOf(
                MpSqlVisualizerBundle.message("settings.tableNameInference.snake"),
                MpSqlVisualizerBundle.message("settings.tableNameInference.camel"),
            ),
            inferenceLabels,
        )
        assertFalse(
            "下拉不得显示枚举原值（应显示本地化文案）：$inferenceLabels",
            inferenceLabels.any { it == PluginSettings.INFERENCE_SNAKE || it == PluginSettings.INFERENCE_CAMEL },
        )
        // 行标签亦须复用全局键（原项目级重复键措辞漂移：表名推断/添加表名前缀 等已收编）
        // 注：DSL v2 row(label) 生成的是普通 JLabel（非 JBLabel），故按 JLabel 收集
        val labels = all(comp).filterIsInstance<javax.swing.JLabel>().map { it.text }
        assertTrue(
            "行标签应复用全局键文案「${MpSqlVisualizerBundle.message("settings.tableNameInference")}」：$labels",
            labels.contains(MpSqlVisualizerBundle.message("settings.tableNameInference")),
        )
        val texts = all(comp).filterIsInstance<JTextField>().filter { it !is com.intellij.ui.components.JBCheckBox }
        // 逻辑删除值 + 表前缀 + 表后缀 + 忽略字段
        assertEquals("应有 4 个文本框", 4, texts.size)
        cfg.disposeUIResources()
    }

    fun testIsModified_构造后未改动为false() {
        val cfg = newConfig()
        cfg.createComponent()
        assertFalse("构造后未改动 isModified 应为 false", cfg.isModified())
        cfg.disposeUIResources()
    }

    fun test勾选使用项目设置_isModified翻转且apply写回() {
        val cfg = newConfig()
        val comp = cfg.createComponent()
        val check = all(comp).filterIsInstance<JCheckBox>().first()
        assertFalse(cfg.isModified())
        check.doClick() // 触发 actionPerformed；isModified 由逐项比对得出（无标志位）
        assertTrue("勾选后应标记为已修改", cfg.isModified())
        cfg.apply()
        assertFalse("apply 后 isModified 应复位", cfg.isModified())
        assertTrue(
            "apply 应将使用项目设置写回",
            ProjectPluginSettings.getInstance(myFixture.project).state.useProjectSettings,
        )
        cfg.disposeUIResources()
    }

    fun testApply_十项字段全部写回() {
        val cfg = newConfig()
        val comp = cfg.createComponent()
        val checks = all(comp).filterIsInstance<JCheckBox>()
        val useProject = checks[0]
        val replaceParams = checks[1]
        val formatSql = checks[2]
        val injectLogicDelete = checks[3]
        val combos = all(comp).filterIsInstance<JComboBox<*>>()
        val texts = all(comp).filterIsInstance<JTextField>().filter { it !is com.intellij.ui.components.JBCheckBox }

        clicks(useProject, true)
        clicks(replaceParams, false)
        clicks(formatSql, false)
        clicks(injectLogicDelete, false)
        combos[0].selectedItem = "PostgreSQL"
        texts[0].text = "1" // 逻辑删除值
        texts[1].text = "t_" // 表前缀
        texts[2].text = "_log" // 表后缀
        // 推断策略下拉持有 data class 选项（值+本地化文案），按 getItemAt 选第二项（CAMEL）
        // ——直接赋裸值 "CAMEL" 会因 equals 不匹配静默 return（JDK17 setSelectedItem 语义）
        combos[1].selectedItem = combos[1].getItemAt(1)
        texts[3].text = " temp ; created " // 忽略字段（apply 应 trim）

        assertTrue(cfg.isModified())
        cfg.apply()
        assertFalse(cfg.isModified())

        val st = ProjectPluginSettings.getInstance(myFixture.project).state
        assertTrue(st.useProjectSettings)
        assertEquals(false, st.replaceParams)
        assertEquals(false, st.formatSql)
        assertEquals(false, st.injectLogicDelete)
        assertEquals("PostgreSQL", st.dialect)
        assertEquals("1", st.logicDeleteValue)
        assertEquals("t_", st.tablePrefix)
        assertEquals("_log", st.tableSuffix)
        assertEquals(PluginSettings.INFERENCE_CAMEL, st.tableNameInference)
        assertEquals("temp ; created", st.ignoredFields)
        cfg.disposeUIResources()
    }

    fun testReset_空值控件回灌全局值且回写不改state() {
        val global = PluginSettings.getInstance().state
        global.tablePrefix = "g_"
        global.replaceParams = false
        val st = ProjectPluginSettings.getInstance(myFixture.project).state
        st.useProjectSettings = true
        st.replaceParams = null
        st.tablePrefix = ""

        val cfg = newConfig()
        val comp = cfg.createComponent()
        val checks = all(comp).filterIsInstance<JCheckBox>()
        val texts = all(comp).filterIsInstance<JTextField>().filter { it !is com.intellij.ui.components.JBCheckBox }
        // null → 全局值；空字符串 → 全局值
        assertEquals("replaceParams null 应灌全局值", false, checks[1].isSelected)
        assertEquals("tablePrefix 空应灌全局值", "g_", texts[1].text)
        assertFalse("reset 前未改动不应 modified", cfg.isModified())

        // 控件改回全局一致值后 reset 应还原（不改 state）
        texts[1].text = "x_"
        assertTrue(cfg.isModified())
        cfg.reset()
        assertEquals("reset 后 tablePrefix 控件回灌 state+全局兜底", "g_", texts[1].text)
        assertFalse(cfg.isModified())
        assertEquals("reset 不应写 state", "", st.tablePrefix)
        cfg.disposeUIResources()
    }
}
