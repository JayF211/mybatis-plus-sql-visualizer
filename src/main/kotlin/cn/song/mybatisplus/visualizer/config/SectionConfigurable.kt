package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import javax.swing.JComponent

/**
 * 设置子页面基类（左侧树导航的叶节点）。
 *
 * 每个实例创建一个只包含对应分区的 [PluginSettingsPanel]，
 * 委托 isModified/apply/reset 给面板。
 *
 * 自动监听「关→开」边沿联动（20260907-1617 自 PluginSettingsConfigurable 迁入——
 * 该类未注册进 plugin.xml 是死类，用户实际勾选走本基类的 apply 链；踩坑第九轮修复回归）：
 * apply 前读旧值，panel.apply() 后比对——「关→开」时清 suppressed + 调
 * [SqlLogManager.onAutoStartSettingEnabled] 按模式分流（进程级监听可用=被动不预 armed，
 * 降级才进 armed；20260907-2045 第四轮：08:33 会话反馈"开启设置未 Run 就自动监听"实证被动语义）。
 */
abstract class SectionConfigurable(
    private val sections: Set<String>,
    private val displayNameKey: String,
) : Configurable {

    private lateinit var panel: PluginSettingsPanel

    override fun getDisplayName(): String = MpSqlVisualizerBundle.message(displayNameKey)

    override fun createComponent(): JComponent {
        if (!::panel.isInitialized) {
            panel = PluginSettingsPanel(PluginSettings.getInstance(), sections)
        }
        return panel.getRoot()
    }

    override fun isModified(): Boolean = ::panel.isInitialized && panel.isModified()

    override fun apply() {
        if (!::panel.isInitialized) return
        // 边沿检测须在 panel.apply() 写回前读旧值
        val autoCaptureBefore = PluginSettings.getInstance().state.logAutoCaptureOnStart
        val languageChanged = panel.apply()
        if (languageChanged) panel.rebuild()
        val settings = PluginSettings.getInstance()
        LOG.info("SectionConfigurable.apply: logAutoCaptureOnStart=${settings.state.logAutoCaptureOnStart}, suppressed=${settings.state.logAutoCaptureSuppressed}")
        if (!autoCaptureBefore && settings.state.logAutoCaptureOnStart) {
            ProjectManager.getInstance().openProjects.forEach { project ->
                val manager = project.getService(SqlLogManager::class.java)
                runCatching { manager.onAutoStartSettingEnabled() }.onFailure {
                    LOG.warn("apply: 自动启动分流跳过（项目关闭/服务不可用）", it)
                }
            }
        }
    }

    override fun reset() {
        if (::panel.isInitialized) panel.reset()
    }

    override fun disposeUIResources() {
        if (::panel.isInitialized) {
            // 清理面板资源
        }
    }

    /** internal 测试访问器 */
    internal fun panelForTest(): PluginSettingsPanel = panel

    companion object {
        private val LOG = Logger.getInstance(SectionConfigurable::class.java)
    }
}

/** SQL 预览子页面（方言/参数替换/逻辑删除/多租户/数据权限/反向转换） */
class SqlPreviewConfigurable : SectionConfigurable(
    setOf(PluginSettingsPanel.SECTION_PREVIEW, PluginSettingsPanel.SECTION_CODE_GEN),
    "settings.section.preview"
)

/** 表名映射子页面（前缀/后缀/移除/忽略字段/推断策略） */
class TableMappingConfigurable : SectionConfigurable(
    setOf(PluginSettingsPanel.SECTION_TABLE_MAPPING),
    "settings.section.tableMapping"
)

/** SQL 日志子页面（前缀/自动捕获/时间戳/格式化/过滤/去重/最大条数/耗时/慢查询/Hibernate/Jdbc/自动保存） */
class SqlLogConfigurable : SectionConfigurable(
    setOf(PluginSettingsPanel.SECTION_SQL_LOG),
    "settings.section.sqlLog"
)

/** 界面与操作子页面（gutter图标/语言/快捷键） */
class UiActionsConfigurable : SectionConfigurable(
    setOf(PluginSettingsPanel.SECTION_UI),
    "settings.section.ui"
)

/** 关于子页面（缓存/更新/构建号/导入导出） */
class AboutConfigurable : SectionConfigurable(
    setOf(PluginSettingsPanel.SECTION_UPDATE_DIAGNOSTICS),
    "settings.section.about"
)
