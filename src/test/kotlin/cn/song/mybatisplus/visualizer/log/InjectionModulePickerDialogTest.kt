package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import javax.swing.JButton
import javax.swing.JCheckBox

/**
 * T-ModPick：[InjectionModulePickerDialog] 构造与选择态测试（headless，同 ShareDialogTest
 * 惯例走 centerPanelForTest 测试缝，不 show() 不触模态）。
 *
 * 锁定行为：默认全选（用户直接确认=原全量注入语义）、全不选后勾选集为空、
 * 全选/全不选按钮联动、模块行数与候选一致。
 */
class InjectionModulePickerDialogTest : BasePlatformTestCase() {

    private fun allComponents(c: java.awt.Component): List<java.awt.Component> {
        val list = mutableListOf(c)
        if (c is java.awt.Container) c.components.forEach { list.addAll(allComponents(it)) }
        return list
    }

    private fun rows() = listOf(
        InjectionModulePickerDialog.Row("mod-a", "mod-a · cn.song.a", false),
        InjectionModulePickerDialog.Row("mod-b", "mod-b · cn.song.b", true),
        InjectionModulePickerDialog.Row("mod-c", "mod-c · cn.song.c", false),
    )

    fun test构造_三行勾选框默认全选() {
        val dialog = InjectionModulePickerDialog(myFixture.project, rows())
        try {
            val boxes = allComponents(dialog.centerPanelForTest()).filterIsInstance<JCheckBox>()
            assertEquals("应有 3 个模块勾选框", 3, boxes.size)
            assertEquals("默认应全选", 3, boxes.count { it.isSelected })
            assertEquals("默认选择集=全部模块", setOf("mod-a", "mod-b", "mod-c"), dialog.selectedKeys())
        } finally {
            dialog.close(0)
        }
    }

    fun test全不选按钮_清空选择集() {
        val dialog = InjectionModulePickerDialog(myFixture.project, rows())
        try {
            val pane = dialog.centerPanelForTest()
            val none = allComponents(pane).filterIsInstance<JButton>()
                .first { it.text == MpSqlVisualizerBundle.message("log.inject.pick.selectNone") }
            none.doClick()
            assertEquals("全不选后选择集应为空", emptySet<String>(), dialog.selectedKeys())
        } finally {
            dialog.close(0)
        }
    }

    fun test全不选再全选_恢复全模块() {
        val dialog = InjectionModulePickerDialog(myFixture.project, rows())
        try {
            val pane = dialog.centerPanelForTest()
            val buttons = allComponents(pane).filterIsInstance<JButton>()
            buttons.first { it.text == MpSqlVisualizerBundle.message("log.inject.pick.selectNone") }.doClick()
            buttons.first { it.text == MpSqlVisualizerBundle.message("log.inject.pick.selectAll") }.doClick()
            assertEquals("全选应恢复全部模块", setOf("mod-a", "mod-b", "mod-c"), dialog.selectedKeys())
        } finally {
            dialog.close(0)
        }
    }

    fun test勾选框文本含模块名与基础包() {
        val dialog = InjectionModulePickerDialog(myFixture.project, rows())
        try {
            val texts = allComponents(dialog.centerPanelForTest()).filterIsInstance<JCheckBox>().map { it.text }
            assertTrue("行文本应含模块名与基础包", texts.contains("mod-b · cn.song.b"))
        } finally {
            dialog.close(0)
        }
    }
}
