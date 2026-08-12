package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiDocumentManager
import javax.swing.JComponent

/**
 * 插件设置页（T-M5-02，F-16）。
 *
 * 接入 IDE Settings → Tools → MyBatis-Plus SQL Visualizer（application-level，无项目依赖）。
 * 表单读写委托 [PluginSettingsPanel]，保存即生效（PersistentStateComponent 自动持久化）。
 *
 * M14.7：保存后按变更类型联动——
 *  - 界面语言变化 → 重建表单（设置页自身文案即时切换）；
 *  - gutter 图标开关变化 → 遍历所有打开项目的编辑器触发重分析（line marker 立即刷新，无需重开文件）。
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
     * 保存配置；按变更类型联动（语言 → 重建表单；gutter 开关 → 全局刷新行标记）。
     */
    override fun apply() {
        val settings = PluginSettings.getInstance()
        val gutterIconBefore = settings.state.showGutterIcon
        if (panel.apply()) panel.rebuild()
        // M14.7：gutter 图标开关变化 → 触发已打开编辑器的重分析（line marker 立即生效，无需重开文件）
        if (settings.state.showGutterIcon != gutterIconBefore) refreshGutterIcons()
    }

    /** 遍历所有打开项目中的编辑器文件，触发 Daemon 重分析（重新计算 line markers）。 */
    private fun refreshGutterIcons() {
        ProjectManager.getInstance().openProjects.forEach { project ->
            EditorFactory.getInstance().getAllEditors()
                .filter { it.project === project }
                .mapNotNull { PsiDocumentManager.getInstance(project).getPsiFile(it.document) }
                .distinct()
                .forEach { DaemonCodeAnalyzer.getInstance(project).restart(it) }
        }
    }

    override fun reset() = panel.reset()
}
