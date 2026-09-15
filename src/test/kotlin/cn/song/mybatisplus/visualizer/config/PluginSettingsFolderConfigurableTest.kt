package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import javax.swing.ListCellRenderer
import javax.swing.ListModel

/**
 * 设置页主页面（左侧树导航父节点）测试（F-44 续 · 补用例大波次）：此前 0 测试。
 * 覆盖：createComponent 列出全部 5 个子页面（搜索空）；搜索无匹配项时列表清空（rebuildList 过滤）。
 * 通过遍历取 JBList 与 SearchTextField，无需新增生产缝。
 * T-UN-20（T-UiNative）增补：行渲染器归一 SimpleColoredComponent 片段通道——组件类型断言
 * （JLabel-HTML/GridBag 通道不允许回潮）+ 双行语义断言（页名/描述行文本仍取 bundle 既有键）。
 */
class PluginSettingsFolderConfigurableTest : BasePlatformTestCase() {

    private fun all(c: java.awt.Component): List<java.awt.Component> {
        val list = mutableListOf(c)
        if (c is java.awt.Container) c.components.forEach { list.addAll(all(it)) }
        return list
    }

    /** 片段拼接文本（与 HistoryCellRendererTest 同款）。 */
    private fun joinedText(component: SimpleColoredComponent): String {
        val sb = StringBuilder()
        val it = component.iterator()
        while (it.hasNext()) sb.append(it.next())
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun rendererOf(list: JBList<*>): ListCellRenderer<Any?> =
        list.cellRenderer as ListCellRenderer<Any?>

    /** 渲染 value 并按 DFS 序取所有 SimpleColoredComponent 行（两行结构：[名称行, 描述行]）。 */
    private fun renderedLines(list: JBList<*>, value: Any?, selected: Boolean = false): List<SimpleColoredComponent> =
        all(rendererOf(list).getListCellRendererComponent(list, value, 0, selected, false))
            .filterIsInstance<SimpleColoredComponent>()

    private fun createList(): Pair<JBList<*>, SearchTextField> {
        val cfg = PluginSettingsFolderConfigurable()
        val comp = cfg.createComponent()
        val list = all(comp).filterIsInstance<JBList<*>>().first()
        val search = all(comp).filterIsInstance<SearchTextField>().first()
        return list to search
    }

    fun testCreateComponent_空搜索列出全部5个子页面() {
        val cfg = PluginSettingsFolderConfigurable()
        val comp = cfg.createComponent()
        val list = all(comp).filterIsInstance<JBList<*>>().first()
        assertEquals("默认应列出 5 个子页面", 5, (list.model as ListModel<*>).size)
    }

    fun test搜索过滤_无匹配项时列表清空() {
        val cfg = PluginSettingsFolderConfigurable()
        val comp = cfg.createComponent()
        val list = all(comp).filterIsInstance<JBList<*>>().first()
        val search = all(comp).filterIsInstance<SearchTextField>().first()
        // 设置搜索文本触发 DocumentListener → rebuildList；EDT 上同步派发
        search.text = "zzzzz"
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("无匹配项时列表应为空", 0, (list.model as ListModel<*>).size)
    }

    // ---------- T-UN-20：落地页行渲染器归一 SimpleColoredComponent 片段通道 ----------

    fun test渲染器_子页面行_SimpleColoredComponent两行_页名加粗描述灰() {
        val (list, _) = createList()
        assertEquals("固定 56px 行高语义保持", 56, list.fixedCellHeight)
        val lines = renderedLines(list, list.model.getElementAt(0))
        assertEquals("双行结构（页名 + 描述）", 2, lines.size)
        // 语义断言：行文本仍取 bundle 既有键
        assertEquals("首行页名取 bundle 既有键", MpSqlVisualizerBundle.message("settings.section.ui"), joinedText(lines[0]))
        assertEquals("次行描述取 bundle 既有键", MpSqlVisualizerBundle.message("settings.section.ui.desc"), joinedText(lines[1]))
        // 着色语义：页名加粗、描述元信息灰
        val nameIter = lines[0].iterator(); nameIter.next()
        assertTrue("页名片段应加粗（原 Font.BOLD 语义）", nameIter.textAttributes.style and SimpleTextAttributes.STYLE_BOLD != 0)
        val descIter = lines[1].iterator(); descIter.next()
        assertTrue("描述片段不应加粗", descIter.textAttributes.style and SimpleTextAttributes.STYLE_BOLD == 0)
        assertEquals("描述片段用元信息灰（与日志/历史页同源）", MpvsUiColors.META_GRAY, descIter.textAttributes.fgColor)
        // 图标语义保持：行首图标非空
        val icon = all(rendererOf(list).getListCellRendererComponent(list, list.model.getElementAt(0), 0, false, false))
            .filterIsInstance<javax.swing.JLabel>().firstOrNull()?.icon
        assertTrue("子页面行应带页图标", icon != null)
    }

    fun test渲染器_选中态走平台选中填充_不自绘() {
        val (list, _) = createList()
        val selected = rendererOf(list).getListCellRendererComponent(list, list.model.getElementAt(0), 0, true, false)
        assertEquals("选中背景取列表选中色（平台选中填充）", list.selectionBackground, selected.background)
        val lines = renderedLines(list, list.model.getElementAt(0), selected = true)
        assertEquals("选中态仍为双行", 2, lines.size)
        val descIter = lines[1].iterator(); descIter.next()
        assertEquals("选中态描述随 selectionForeground（不再固定灰）", null, descIter.textAttributes.fgColor)
    }

    fun test渲染器_设置项行_字段名加粗与所属页面() {
        val (list, search) = createList()
        // 搜索命中设置项字段（FieldItem）：字段名在上、所属页面在下
        val fieldLabel = MpSqlVisualizerBundle.message("settings.dialect")
        search.text = fieldLabel
        UIUtil.dispatchAllInvocationEvents()
        assertTrue("搜索应命中设置项", list.model.size > 0)
        val hits = (0 until list.model.size)
            .map { renderedLines(list, list.model.getElementAt(it)) }
            .filter { it.size == 2 && joinedText(it[0]) == fieldLabel }
        assertTrue("应渲染出设置项行", hits.isNotEmpty())
        assertEquals("设置项次行应为所属页面名", MpSqlVisualizerBundle.message("settings.section.preview"), joinedText(hits[0][1]))
        val fieldIter = hits[0][0].iterator(); fieldIter.next()
        assertTrue("字段名片段应加粗", fieldIter.textAttributes.style and SimpleTextAttributes.STYLE_BOLD != 0)
    }
}
