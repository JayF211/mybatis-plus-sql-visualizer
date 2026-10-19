package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.SqlLogConfigurable
import cn.song.mybatisplus.visualizer.log.agent.AgentManager
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T-JumpCapture / O-66 T-GuideEnhancedFirst：源码位置缺失时的引导编排（实时日志 / 历史面板「跳转到源码」共用）。
 *
 * 跳转源码完全依赖捕获的真实调用位置（F-44-6/7）——两条采集通道都带：控制台通道靠
 * [SqlSourceInterceptor] 的标记行，增强捕获（agent 通道）靠应用 JVM 内 StackLocator 直取
 * （SqlEvent.sourceFile → AgentEventAdapter 直通面板条目）。条目无捕获位置时不再按表名搜索
 * Wrapper（同表名 ≠ 同调用点，命中即误导，已整体移除），而是弹框按**全局增强捕获状态**给出口：
 *
 * - 未开启（[GuideBranch.EnhanceFirst]）：默认按钮让位「开启增强捕获」——不改工程代码、
 *   开关打开即生效，对用户最省事；注入拦截器降为次选。
 * - 已开启（[GuideBranch.Legacy]）：维持原两出口（打开设置 / 注入拦截器），此时缺位置
 *   另有原因（帧过滤前缀命中 / 应用未重启 / agent 未挂载），message 末尾附一行实时状态（D1）。
 *
 * T-EnhancedCaptureDefaultOn（2026-10-19）：注入确认弹窗已移除，本引导的「开启增强捕获」
 * 只置开关并提示重启应用，不再走授权确认流。
 *
 * 注入结果四类弹窗亦收敛于此（原设置页硬编码文案 i18n 化迁入）；其中 UnsupportedProject 在
 * EnhanceFirst 分支复用本编排引导开启增强捕获（20260924 定案：不再指路手动配置），Legacy 维持普通警告。
 */
object SourceCaptureGuide {

    /** 引导弹框用户选择（纯函数映射，测试驱动不触 UI）。 */
    enum class GuideChoice { EnableEnhanced, OpenSettings, InjectNow, Cancel }

    /** 引导分支（[decideActions] 产出）：EnhanceFirst＝增强捕获未开启，Legacy＝已开启。 */
    internal enum class GuideBranch { EnhanceFirst, Legacy }

    /**
     * 全局增强捕获状态 → 引导分支（纯函数）。
     * 增强捕获未开启时，它是携带源码位置的通道里对用户最省事的一条（不注入工程代码），
     * 默认按钮让位；已开启时开关不再是原因，维持原出口。
     */
    internal fun decideActions(enabled: Boolean): GuideBranch =
        if (enabled) GuideBranch.Legacy else GuideBranch.EnhanceFirst

    /**
     * showDialog 返回码 → 选择，**序表随分支**（O-66）：EnhanceFirst 0=开启增强捕获，
     * Legacy 0=打开设置；两分支 1=立即注入。分支参数是硬约束——两条序表 0 号语义不同，
     * 无分支映射会把「开启增强捕获」错路由成「打开设置」。关闭/ESC/负值一律兜底取消。
     */
    internal fun resolveChoice(returnCode: Int, branch: GuideBranch): GuideChoice = when {
        returnCode == 0 && branch == GuideBranch.EnhanceFirst -> GuideChoice.EnableEnhanced
        returnCode == 0 -> GuideChoice.OpenSettings
        returnCode == 1 -> GuideChoice.InjectNow
        else -> GuideChoice.Cancel
    }

    /**
     * Legacy 分支 message＝基础文案末尾附一行增强捕获实时状态（D1 定案；复用链路诊断键
     * log.diag.enhanced）。无读数（null/空白）时原样返回——分支语义已由按钮表达，不硬凑一行。
     */
    internal fun legacyMessage(baseMessage: String, enhancedStatus: String?): String {
        val status = enhancedStatus?.takeIf { it.isNotBlank() } ?: return baseMessage
        return baseMessage + "\n\n" + MpSqlVisualizerBundle.message("log.diag.enhanced", status)
    }

    /** 注入防重入（弹框与设置页共用本编排，检测期间不重复触发）。 */
    private val injectionInProgress = AtomicBoolean(false)

    /** 无捕获位置时的引导弹框（实时日志 sourceFile==null / 历史面板 NoSource 共用入口）。 */
    fun showMissingSourceDialog(project: Project) {
        val state = PluginSettings.getInstance().state
        val branch = decideActions(state.enhancedCaptureEnabled)
        val message = when (branch) {
            GuideBranch.EnhanceFirst -> MpSqlVisualizerBundle.message("log.jump.guide.message.enhanceFirst")
            GuideBranch.Legacy -> legacyMessage(
                MpSqlVisualizerBundle.message("log.jump.guide.message"),
                runCatching { AgentManager.getInstance(project).statusSummary() }.getOrNull(),
            )
        }
        val buttons = when (branch) {
            GuideBranch.EnhanceFirst -> arrayOf(
                MpSqlVisualizerBundle.message("log.jump.guide.enableEnhanced"),
                MpSqlVisualizerBundle.message("log.jump.guide.injectNow"),
                MpSqlVisualizerBundle.message("log.jump.guide.cancel"),
            )
            GuideBranch.Legacy -> arrayOf(
                MpSqlVisualizerBundle.message("log.jump.guide.openSettings"),
                MpSqlVisualizerBundle.message("log.jump.guide.injectNow"),
                MpSqlVisualizerBundle.message("log.jump.guide.cancel"),
            )
        }
        val rc = Messages.showDialog(
            project,
            message,
            MpSqlVisualizerBundle.message("log.jump.guide.title"),
            buttons,
            0,
            Messages.getQuestionIcon(),
        )
        when (resolveChoice(rc, branch)) {
            GuideChoice.EnableEnhanced -> enableEnhancedCapture(project)
            GuideChoice.OpenSettings ->
                // T-SettingsFocusFromLink：打开主设置页并定位高亮到源码捕获行的设置项
                cn.song.mybatisplus.visualizer.config.PluginSettingsFolderConfigurable.openSqlLogSettingsFocused(
                    project,
                    MpSqlVisualizerBundle.message("settings.log.autoInjectInterceptor"),
                )
            GuideChoice.InjectNow -> runInjection(project)
            GuideChoice.Cancel -> Unit
        }
    }

    /**
     * 「开启增强捕获」动作（O-66，EnhanceFirst 分支默认按钮触发）。
     *
     * 总开关关闭时置 enabled=true 并发布 [PluginSettings.SETTINGS_CHANGE_TOPIC]——与设置页
     * Apply 同通道，AgentManager 的「关闭→再开启」上升沿据此扫描在跑进程补挂、CONFIG 下发链跟随；
     * 已开启时不得重复发布（设置页面板的 isModified 对比读同一 state，乱发布会制造脏态假象）。
     *
     * T-EnhancedCaptureDefaultOn（2026-10-19）：不再走注入确认流。开关打开即授权，本动作只
     * 展示「重启应用后生效」提示；挂载由下一次应用进程启动或上升沿补挂承接。
     */
    internal fun enableEnhancedCapture(
        project: Project,
        onEnabled: () -> Unit = { showEnhanceDone(project) },
    ) {
        val state = PluginSettings.getInstance().state
        if (!state.enhancedCaptureEnabled) {
            state.enhancedCaptureEnabled = true
            ApplicationManager.getApplication().messageBus
                .syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()
        }
        onEnabled()
    }

    /** 开启后的生效提示。 */
    private fun showEnhanceDone(project: Project) {
        Messages.showInfoMessage(
            project,
            MpSqlVisualizerBundle.message("log.jump.guide.enhanceDone"),
            MpSqlVisualizerBundle.message("settings.enhancedCapture.enableDone.title"),
        )
    }

    /**
     * 触发拦截器注入（EDT 调用安全：检测阶段在 [SqlSourceInterceptorAutoInjector.injectAsync] 后台执行）。
     *
     * @param onResult 结果回调（EDT）；缺省展示结果弹窗。防重入期间被拒绝时不回调。
     * @return true = 已受理；false = 上一次注入仍在进行
     */
    fun runInjection(
        project: Project,
        onResult: (InjectionResult) -> Unit = { showInjectionResult(project, it) },
    ): Boolean {
        if (!injectionInProgress.compareAndSet(false, true)) return false
        SqlSourceInterceptorAutoInjector.injectAsync(project) { result ->
            injectionInProgress.set(false)
            onResult(result)
        }
        return true
    }

    /**
     * 展示注入结果弹窗（四类结果统一文案，双语 bundle）。
     *
     * @return true = 注入成功（设置页据此决定是否保留勾选态）
     */
    fun showInjectionResult(project: Project, result: InjectionResult): Boolean {
        val title = MpSqlVisualizerBundle.message("log.inject.title")
        return when (result) {
            is InjectionResult.Success -> {
                Messages.showInfoMessage(project, result.message, title)
                true
            }
            is InjectionResult.AlreadyExists -> {
                Messages.showInfoMessage(
                    project,
                    MpSqlVisualizerBundle.message("log.inject.alreadyExists"),
                    title,
                )
                false
            }
            is InjectionResult.UnsupportedProject -> {
                val state = PluginSettings.getInstance().state
                if (decideActions(state.enhancedCaptureEnabled) == GuideBranch.EnhanceFirst) {
                    showUnsupportedWithEnhanceGuide(project, title)
                } else {
                    Messages.showWarningDialog(
                        project,
                        MpSqlVisualizerBundle.message("log.inject.unsupported"),
                        title,
                    )
                }
                false
            }
            is InjectionResult.CannotDetectStructure -> {
                Messages.showWarningDialog(
                    project,
                    MpSqlVisualizerBundle.message("log.inject.cannotDetect"),
                    title,
                )
                false
            }
            // T-ModPick：用户在模块勾选弹框取消——静默返回，无结果弹窗（用户主动退出非异常态）
            InjectionResult.Cancelled -> false
        }
    }

    /**
     * 注入识别不了项目类型时的兜底引导（20260924 定案）：不再指路手动配置示例（对用户太重），
     * 默认按钮让位「开启增强捕获」——agent 通道不依赖项目类型、不改工程代码，与缺位置引导
     * （[showMissingSourceDialog]）复用同一 [enableEnhancedCapture] 编排；取消/关闭静默返回。
     * 仅 EnhanceFirst 分支进入（Legacy 已开启已授权，「开启增强捕获」不再是有效出口，维持普通警告）。
     */
    private fun showUnsupportedWithEnhanceGuide(project: Project, title: String) {
        val rc = Messages.showDialog(
            project,
            MpSqlVisualizerBundle.message("log.inject.unsupported.enhanceFirst"),
            title,
            arrayOf(
                MpSqlVisualizerBundle.message("log.jump.guide.enableEnhanced"),
                MpSqlVisualizerBundle.message("log.jump.guide.cancel"),
            ),
            0,
            Messages.getWarningIcon(),
        )
        if (rc == 0) enableEnhancedCapture(project)
    }
}
