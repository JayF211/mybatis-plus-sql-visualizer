package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.diagnostic.Logger
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
 *
 * 启动自动监听（logAutoCaptureOnStart）仅由 [cn.song.mybatisplus.visualizer.log.LogStartupCapture]
 * （postStartupActivity）在项目打开时触发——设置页勾选后不立即监听，项目关闭后自动停止（Disposable）。
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
     * 注意：自动监听（logAutoCaptureOnStart）不在 apply 时立即启动——
     * 由 LogStartupCapture（postStartupActivity）在项目打开时进入 armed 态，
     * onConsoleAppeared 在项目启动运行后自动开始捕捉。
     */
    override fun apply() {
        val settings = PluginSettings.getInstance()
        val gutterIconBefore = settings.state.showGutterIcon
        if (panel.apply()) panel.rebuild()
        LOG.info("PluginSettingsConfigurable.apply: logAutoCaptureOnStart=${settings.state.logAutoCaptureOnStart}")
        // M14.7：gutter 图标开关变化 → 触发已打开编辑器的重分析（line marker 立即生效，无需重开文件）
        if (settings.state.showGutterIcon != gutterIconBefore) refreshGutterIcons()
    }

    /** 遍历所有打开项目中的编辑器文件，触发 Daemon 重分析（重新计算 line markers）。 */
    @Suppress("DEPRECATION") // T-M52：restart(PsiFile) 在 253/262 标 deprecated，243 安全；无跨版本非废弃替代
    private fun refreshGutterIcons() {
        ProjectManager.getInstance().openProjects.forEach { project ->
            EditorFactory.getInstance().getAllEditors()
                .filter { it.project === project }
                .mapNotNull { PsiDocumentManager.getInstance(project).getPsiFile(it.document) }
                .distinct()
                .forEach { psiFile -> DaemonCodeAnalyzer.getInstance(project).restart(psiFile) }
        }
    }

    override fun reset() = panel.reset()

    /** M23.9：internal 测试访问器（仿 SqlPreviewDialog.panelForTest——createComponent 返回
     * 的是 getRoot() 的 JComponent，测试需经此取回表单面板断言 apply 幂等）。 */
    internal fun panelForTest(): PluginSettingsPanel = panel

    companion object {
        private val LOG = Logger.getInstance(PluginSettingsConfigurable::class.java)
    }
}
