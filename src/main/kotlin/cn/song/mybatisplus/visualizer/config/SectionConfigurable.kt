package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.log.sqlLogManagerOrNull
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
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
            linkageAutoStartForProjects(ProjectManager.getInstance().openProjects.toList())
        }
    }

    /**
     * 「关→开」边沿联动：对每个打开项目调 [SqlLogManager.onAutoStartSettingEnabled] 按模式分流。
     *
     * **T-ST-02**：`getService` 与 `onAutoStartSettingEnabled()` 在同一个降级面内——正在关闭 /
     * 测试框架 temporarily-disposed 窗口内的项目，`getService` 会抛 `AlreadyDisposedException`
     * （项目服务构造期订阅 `project.messageBus`，`MessageBusImpl.checkOwnerDisposed` 判
     * `owner.isDisposed` 为真，容器服务创建链再归一成 "Container is already disposed"）。
     * 原先只包住 `onAutoStartSettingEnabled()`，该异常会直接穿出 `apply()` 打断设置写回。
     *
     * ## ⚠️ 为什么必须在 `getService` **之前**先判 [Project.isDisposed]（T-ST-02b2，勿「优化」回去）
     *
     * `runCatching` 只吞掉这一次的结果，**阻止不了中毒**——它仍然发起了那次注定失败的创建。
     * 平台服务容器**一次创建失败即把该服务在该项目实例上永久毒化**（独立复核者字节码级结论）：
     *
     *  - `LazyInstanceHolder.initialize$1` 的异常表是 `catch java.lang.Throwable`（**无
     *    `CancellationException` 过滤**），catch 后无条件 `new CannotInitialize(instanceClass, throwable)`
     *    + `access$complete`；
     *  - 此后 `LazyInstanceHolder.tryGetInstance()` 只要见 `_state instanceof CannotInitialize`
     *    就 `throw getInitializationError()`——**每次 `getService` 都重抛同一个缓存异常**
     *    （每次再被归一成新的 "Container is already disposed"）。
     *
     * 旁证：本仓 `AlreadyDisposedDiagnostics` 采到的 8 条 WARN 全部打印
     * `project.isDisposed=false`，而消息是 "Container is already disposed"——抛出时刻
     * `owner.isDisposed` 必为真、catch 时刻同一对象为假，只有「缓存重抛」能自洽。
     *
     * 而 [Project.isDisposed] 在 temporarily-disposed 窗口内为真（平台 `ProjectImpl.isDisposed()`
     * = `super.isDisposed() || isTemporarilyDisposed`），故「窗口内不取服务」既避开中毒，
     * 又不会误伤正常项目。**平台侧的毒化机制与 temporarily-disposed 窗口本身未触及**——
     * 本条只保证本调用点不再发起那次失败创建，**不宣称根治**。
     *
     * **测试缝（internal）**：项目列表由调用方给定，便于以项目桩驱动「项目已 dispose」分支
     * （`ProjectManager.getInstance().openProjects` 是全局单例，测试无法注入）。
     */
    internal fun linkageAutoStartForProjects(projects: List<Project>) {
        projects.forEach { project ->
            // T-ST-02b2：取服务前先判 isDisposed（经 sqlLogManagerOrNull 统一收口，见其 KDoc 的毒化论证）
            val manager = sqlLogManagerOrNull(project)
            if (manager == null) {
                // 沿用既有 warn 文案（未取服务，无异常可挂）
                LOG.warn("apply: 自动启动分流跳过（项目关闭/服务不可用）")
                return@forEach
            }
            runCatching { manager.onAutoStartSettingEnabled() }
                .onFailure {
                    LOG.warn("apply: 自动启动分流跳过（项目关闭/服务不可用）", it)
                }
        }
    }

    override fun reset() {
        if (::panel.isInitialized) panel.reset()
    }

    override fun disposeUIResources() {
        if (::panel.isInitialized) {
            // T-SettingsTruth P2：断开面板 messageBus 连接（增强捕获状态行订阅），防 IDE 退出 leak 告警
            panel.disposeResources()
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

/** T-ShortcutSettings：快捷键统一管理子页面（5 个动作快捷键内嵌录制编辑） */
class ShortcutsConfigurable : SectionConfigurable(
    setOf(PluginSettingsPanel.SECTION_SHORTCUTS),
    "settings.section.shortcuts"
)
