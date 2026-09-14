package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-SelToggle：SQL 日志页类型过滤弹窗「全选/全不选」双按钮合一后的切换语义测试。
 *
 * 弹窗经 [SqlLogPanel.buildTypeFilterContentForTest] 直接构建（不走 `JPopupMenu.show`——
 * headless 环境 lightweight popup 不可靠），断言只触达切换按钮文案与 `activeFilter`
 * 的两处投影（弹窗复选框勾选态、外层 SQL类型按钮文案）。
 */
class SqlLogPanelTypePopupToggleTest : BasePlatformTestCase() {

    private lateinit var manager: SqlLogManager

    override fun setUp() {
        super.setUp()
        manager = SqlLogManager(project)
        manager.entries.setDedupeEnabled(false)
    }

    override fun tearDown() {
        manager.dispose()
        super.tearDown()
    }

    private fun waitForEdt() = com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

    /** 递归收集弹窗内勾选框——T-CrudColor 图例把每个复选框包进行面板（色块 + 复选框），顶层直查取不到。 */
    private fun collectCheckBoxes(container: java.awt.Container): List<javax.swing.JCheckBox> =
        container.components.flatMap { comp ->
            when (comp) {
                is javax.swing.JCheckBox -> listOf(comp)
                is java.awt.Container -> collectCheckBoxes(comp)
                else -> emptyList()
            }
        }

    private fun label(key: String) = MpSqlVisualizerBundle.message(key)

    private fun allTypes() = SqlLogPanelKt.FILTERABLE_TYPES.toSet()

    fun test初始全勾_切换按钮应为全不选文案() {
        val panel = SqlLogPanel(project, manager)
        try {
            panel.buildTypeFilterContentForTest()
            val toggle = panel.typePopupToggleButtonForTest
            assertNotNull("构建弹窗内容后应有切换按钮", toggle)
            // activeFilter 默认 = 全部可筛选类型
            assertEquals(label("log.filter.selectNone"), toggle!!.text)
        } finally {
            panel.dispose()
        }
    }

    fun test切换按钮一次点击清空_再点击填满() {
        val panel = SqlLogPanel(project, manager)
        try {
            panel.buildTypeFilterContentForTest()
            val toggle = panel.typePopupToggleButtonForTest!!

            toggle.doClick() // 全勾 → 点击 = 全不选
            waitForEdt()
            assertEquals("清空后外层按钮应显示「无」", label("log.filter.type.none"), panel.typeFilterButtonForTest.text)
            assertEquals(label("log.filter.selectAll"), toggle.text)

            toggle.doClick() // 空集 → 点击 = 全选
            waitForEdt()
            assertEquals("填满后外层按钮应显示「全部」", label("log.filter.all"), panel.typeFilterButtonForTest.text)
            assertEquals(label("log.filter.selectNone"), toggle.text)
        } finally {
            panel.dispose()
        }
    }

    fun test单类型勾选即时同步切换按钮文案() {
        val panel = SqlLogPanel(project, manager)
        try {
            val content = panel.buildTypeFilterContentForTest()
            val toggle = panel.typePopupToggleButtonForTest!!
            val checkBoxes = collectCheckBoxes(content)
            assertEquals("弹窗内应构建全部可筛选类型勾选框", SqlLogPanelKt.FILTERABLE_TYPES.size, checkBoxes.size)

            // 取消一个类型 → 非全勾 → 文案翻「全选」
            checkBoxes.first().doClick()
            waitForEdt()
            assertEquals(label("log.filter.selectAll"), toggle.text)

            // 勾回 → 重新全勾 → 文案翻「全不选」
            checkBoxes.first().doClick()
            waitForEdt()
            assertEquals(label("log.filter.selectNone"), toggle.text)
        } finally {
            panel.dispose()
        }
    }

    fun test部分勾选态点击切换按钮填满并翻文案() {
        val panel = SqlLogPanel(project, manager)
        try {
            panel.applyTypeFilterForTest(setOf(StatementType.SELECT))
            panel.buildTypeFilterContentForTest()
            val toggle = panel.typePopupToggleButtonForTest!!
            assertEquals("部分勾选应为全选文案", label("log.filter.selectAll"), toggle.text)

            toggle.doClick()
            waitForEdt()
            assertEquals(label("log.filter.all"), panel.typeFilterButtonForTest.text)
            assertEquals(label("log.filter.selectNone"), toggle.text)
            // 弹窗内勾选框同步填满（activeFilter 回写的镜像）
            val checkBoxes = collectCheckBoxes(panel.buildTypeFilterContentForTest())
            assertTrue("填满后弹窗勾选框应全部选中", checkBoxes.all { it.isSelected })
        } finally {
            panel.dispose()
        }
    }

    fun test未构建弹窗时无切换按钮() {
        val panel = SqlLogPanel(project, manager)
        try {
            assertNull("未打开弹窗时不应有切换按钮引用", panel.typePopupToggleButtonForTest)
        } finally {
            panel.dispose()
        }
    }

    fun test弹窗重建按当下筛选态初始化文案() {
        val panel = SqlLogPanel(project, manager)
        try {
            panel.applyTypeFilterForTest(emptySet())
            panel.buildTypeFilterContentForTest()
            assertEquals("空集态重建弹窗应显示全选文案", label("log.filter.selectAll"), panel.typePopupToggleButtonForTest!!.text)

            panel.applyTypeFilterForTest(allTypes())
            panel.buildTypeFilterContentForTest()
            assertEquals("全勾态重建弹窗应显示全不选文案", label("log.filter.selectNone"), panel.typePopupToggleButtonForTest!!.text)
        } finally {
            panel.dispose()
        }
    }
}
