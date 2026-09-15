package cn.song.mybatisplus.visualizer.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import javax.swing.ListModel

/**
 * 设置页主页面（左侧树导航父节点）测试（F-44 续 · 补用例大波次）：此前 0 测试。
 * 覆盖：createComponent 列出全部 7 个子页面（搜索空）；搜索无匹配项时列表清空（rebuildList 过滤）。
 * 通过遍历取 JBList 与 SearchTextField，无需新增生产缝。
 */
class PluginSettingsFolderConfigurableTest : BasePlatformTestCase() {

    private fun all(c: java.awt.Component): List<java.awt.Component> {
        val list = mutableListOf(c)
        if (c is java.awt.Container) c.components.forEach { list.addAll(all(it)) }
        return list
    }

    fun testCreateComponent_空搜索列出全部7个子页面() {
        val cfg = PluginSettingsFolderConfigurable()
        val comp = cfg.createComponent()
        val list = all(comp).filterIsInstance<JBList<*>>().first()
        assertEquals("默认应列出 7 个子页面（主五页+shortcuts/mcp，T-ShortcutSettings）", 7, (list.model as ListModel<*>).size)
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
}
