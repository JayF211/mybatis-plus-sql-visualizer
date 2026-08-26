package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.options.Configurable
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 设置页文件夹节点（左侧树导航的父节点）。
 *
 * 仅作为分组文件夹，不显示页面内容；子节点由 plugin.xml 中
 * parentId="cn.song.mybatisplus.visualizer.settings" 的 applicationConfigurable 构成。
 */
class PluginSettingsFolderConfigurable : Configurable {

    override fun getDisplayName(): String = MpSqlVisualizerBundle.message("plugin.name")

    override fun createComponent(): JComponent = JPanel()

    override fun isModified(): Boolean = false

    override fun apply() {}

    override fun reset() {}
}
