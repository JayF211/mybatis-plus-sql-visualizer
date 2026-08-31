package cn.song.mybatisplus.visualizer.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import javax.swing.JCheckBox
import javax.swing.JComboBox

/**
 * 项目级设置页测试（F-44 续 · 补用例大波次）：此前 0 测试。
 * 覆盖 Configurable 契约与交互：createComponent 构建「使用项目设置」开关 + 方言下拉；
 * 构造后 isModified=false；勾选开关触发 modified 翻转；apply 写回 ProjectPluginSettings 且 isModified 复位。
 * 通过组件遍历取开关/下拉，无需新增生产缝。tearDown 还原项目设置状态。
 */
class ProjectPluginSettingsConfigurableTest : BasePlatformTestCase() {

    private var savedUseProject: Boolean? = null

    override fun setUp() {
        super.setUp()
        savedUseProject = ProjectPluginSettings.getInstance(myFixture.project).state.useProjectSettings
    }

    override fun tearDown() {
        ProjectPluginSettings.getInstance(myFixture.project).state.useProjectSettings = savedUseProject ?: false
        super.tearDown()
    }

    private fun all(c: java.awt.Component): List<java.awt.Component> {
        val list = mutableListOf(c)
        if (c is java.awt.Container) c.components.forEach { list.addAll(all(it)) }
        return list
    }

    fun testCreateComponent_含使用项目设置开关与方言下拉() {
        val cfg = ProjectPluginSettingsConfigurable(myFixture.project)
        val comp = cfg.createComponent()
        assertNotNull(comp)
        val checks = all(comp).filterIsInstance<JCheckBox>()
        assertEquals("应有一个使用项目设置开关", 1, checks.size)
        val combos = all(comp).filterIsInstance<JComboBox<*>>()
        assertTrue("应有方言下拉", combos.isNotEmpty())
        assertTrue("方言下拉应有可选项", (combos.first() as JComboBox<*>).itemCount > 0)
        cfg.disposeUIResources()
    }

    fun testIsModified_构造后未改动为false() {
        val cfg = ProjectPluginSettingsConfigurable(myFixture.project)
        cfg.createComponent()
        assertFalse("构造后未改动 isModified 应为 false", cfg.isModified())
        cfg.disposeUIResources()
    }

    fun test勾选使用项目设置_isModified翻转且apply写回() {
        val cfg = ProjectPluginSettingsConfigurable(myFixture.project)
        val comp = cfg.createComponent()
        val check = all(comp).filterIsInstance<JCheckBox>().first()
        assertFalse(cfg.isModified())
        check.doClick() // 触发 actionPerformed → modified=true
        assertTrue("勾选后应标记为已修改", cfg.isModified())
        cfg.apply()
        assertFalse("apply 后 isModified 应复位", cfg.isModified())
        assertTrue(
            "apply 应将使用项目设置写回",
            ProjectPluginSettings.getInstance(myFixture.project).state.useProjectSettings,
        )
        cfg.disposeUIResources()
    }
}
