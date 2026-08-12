package cn.song.mybatisplus.visualizer.config

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

/**
 * 插件设置页（T-M5-02，F-16）。
 *
 * 接入 IDE Settings → Tools → MP SQL Visualizer（application-level，无项目依赖）。
 * 表单读写委托 [PluginSettingsPanel]，保存即生效（PersistentStateComponent 自动持久化）。
 */
class PluginSettingsConfigurable : Configurable {

    private lateinit var panel: PluginSettingsPanel

    override fun getDisplayName(): String = "MP SQL Visualizer"

    override fun createComponent(): JComponent {
        panel = PluginSettingsPanel(PluginSettings.getInstance())
        return panel.getRoot()
    }

    override fun isModified(): Boolean = panel.isModified()

    override fun apply() = panel.apply()

    override fun reset() = panel.reset()
}
