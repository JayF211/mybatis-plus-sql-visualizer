package cn.song.mybatisplus.visualizer.ui.search

import com.intellij.find.SearchReplaceComponent
import com.intellij.find.editorHeaderActions.ToggleMatchCase
import com.intellij.find.editorHeaderActions.ToggleRegex
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel

/**
 * T-SearchToggleNativeLook 开关钮挂位机制断言（20261010 两轮真机返工的回归探针）。
 *
 * 根因（243 SearchReplaceComponent 源码钉死）：只有 `addExtraSearchActions` 组里的
 * `Embeddable` 动作会在构造期被抽给 SearchTextArea.setExtraActions 渲染进框内；
 * addPrimarySearchActions 进右侧工具栏（showOnlySearchPanel 形态下该面板没挂进布局），
 * addSearchFieldActions 只绑快捷键不渲染。
 *
 * 判别性：生产代码换回任何一个错位 API，[myEmbeddedSearchActions] 即为空 → 用例红。
 * 基类用平台测试件：构造链经 ActionManager（DefaultActionGroup.addAll），纯 headless
 * TestCase 无 Application 必炸（20261010 对照实验实证，旧挂位代码同样炸）。
 */
class NativeSearchTextFieldStructureTest : BasePlatformTestCase() {

    fun test嵌入列表含大小写与正则开关() {
        val field = NativeSearchTextField(placeholderText = null, showRegex = true)

        val embedded = embeddedActions(field)
        val types = embedded.map { it.javaClass.simpleName }
        assertTrue("嵌入列表应含 ToggleMatchCase（实际: $types）", embedded.any { it is ToggleMatchCase })
        assertTrue("嵌入列表应含 ToggleRegex（实际: $types）", embedded.any { it is ToggleRegex })
    }

    fun test日志页形态_含大小写与正则开关() {
        // 组件级 showRegex=false 仍不挂 Regex（组件语义保持）；
        // SqlLogPanel 生产接线（showRegex=true + regexToggleForTest 缝）在 SqlLogPanelFilterTest 断言。
        val field = NativeSearchTextField(placeholderText = null, showRegex = false)

        val embedded = embeddedActions(field)
        val types = embedded.map { it.javaClass.simpleName }
        assertTrue("日志页嵌入列表应含 ToggleMatchCase（实际: $types）", embedded.any { it is ToggleMatchCase })
        assertFalse("组件级 showRegex=false 不内嵌 ToggleRegex（实际: $types）", embedded.any { it is ToggleRegex })
    }

    fun test命中数读数_行内右侧标签_空文本隐藏() {
        val field = NativeSearchTextField(placeholderText = null, showRegex = true)
        val label = statusLabelOf(field)

        assertFalse("初始应隐藏", label.isVisible)

        field.setStatusText("5 条")
        assertEquals("读数应写入行内标签", "5 条", label.text)
        assertTrue("有读数时应可见", label.isVisible)

        field.setStatusText("")
        assertEquals("清空后文本应空", "", label.text)
        assertFalse("空读数应隐藏", label.isVisible)
    }

    /** 反射读 SearchReplaceComponent 构造期抽出的框内嵌入动作列表（挂位是否正确的唯一权威）。 */
    private fun embeddedActions(field: NativeSearchTextField): List<Any> {
        val f = SearchReplaceComponent::class.java.getDeclaredField("myEmbeddedSearchActions")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return f.get(field.component) as List<Any>
    }

    /** 从 row 组件树取读数标签（贴近用户视角：标签必须真实挂在 row 布局里）。 */
    private fun statusLabelOf(field: NativeSearchTextField): JBLabel {
        val labels = mutableListOf<JBLabel>()
        collectLabels(field.row, labels)
        assertEquals("row 应恰好挂一个读数标签（实际 ${labels.size} 个）", 1, labels.size)
        return labels[0]
    }

    private fun collectLabels(root: java.awt.Container, out: MutableList<JBLabel>) {
        for (child in root.components) {
            if (child is JBLabel) out.add(child)
            if (child is java.awt.Container) collectLabels(child, out)
        }
    }
}
