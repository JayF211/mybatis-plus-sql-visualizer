package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

/**
 * 插件设置页（T-M5-02，F-16）。
 *
 * 接入 IDE Settings → Tools → MyBatis-Plus SQL Visualizer（application-level，无项目依赖）。
 * 表单读写委托 [PluginSettingsPanel]，保存即生效（PersistentStateComponent 自动持久化）。
 */
class PluginSettingsConfigurable : Configurable {

    private lateinit var panel: PluginSettingsPanel

    override fun getDisplayName(): String = MpSqlVisualizerBundle.message("plugin.name")

    override fun createComponent(): JComponent {
        panel = PluginSettingsPanel(PluginSettings.getInstance())
        return panel.getRoot()
    }

    override fun isModified(): Boolean = panel.isModified()

    /**
     * 保存配置；界面语言变化时重建表单（设置页自身文案即时切换，M14）。
     */
    override fun apply() {
        if (panel.apply()) panel.rebuild()
    }

    override fun reset() = panel.reset()
}
