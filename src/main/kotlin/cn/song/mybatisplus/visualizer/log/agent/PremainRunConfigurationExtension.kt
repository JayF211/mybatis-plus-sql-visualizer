package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.util.FatalErrors
import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration

/**
 * T-M56Phase2-Premain：Java 运行配置准备期注入 `-javaagent`（「启动即捕获」补位）。
 *
 * 位置依据（243 javap 实证）：`JavaRunConfigurationExtensionManager.updateJavaParameters` 由
 * `com.intellij.execution.application.BaseJavaApplicationCommandLineState` 在**构建 JavaParameters
 * 之后、进程启动之前**调用（javap -c 常量池 #198），这正是 `-javaagent` 必须写入的时机——再晚
 * 进程已经起好，注入无效。EP 由 `com.intellij.java` 声明（`java-impl.jar!/META-INF/JavaPlugin.xml`
 * 的 `qualifiedName="com.intellij.runConfigurationExtension"`），本插件 plugin.xml:66 已有硬依赖，
 * 故注册形态就是一条 `<runConfigurationExtension implementation="…"/>`，无需 epName。
 * 平台自身同类先例（243 sources 实测）：Kotlin 插件 `CoroutineAgentConnector.kt`
 * 同样是 `params.vmParametersList.add("-javaagent:$jarPath")`。
 *
 * 零劣化约定：本类只做「按任务语义过范围闸门（T-GradleTaskInjectScope，[PremainTaskScope]）+
 * 问 AgentManager 要一个 VM 选项字符串，非空就加」这两件事，全链 `runCatching` 包裹——注入链
 * 任何异常（jar 落盘失败/端口协商失败/平台 API 意外形态）只记 WARN，绝不抛 `ExecutionException`、
 * 绝不动用户保存的配置（JavaParameters 是每次执行现建的瞬时对象，改动不落盘）。弹窗例外只有
 * T-FirstUsePremainConsent S1 这一种形态（未确认态的前置知情同意，委托 [AgentManager] 收口；
 * EDT/headless 前提不成立时静默回落，不跨线程弹窗），其余路径仍绝不弹窗。
 *
 * 与 attach 链的关系：本扩展只在「增强捕获开启 + 注入确认已达成」时产出参数（复用
 * [AgentManager.isEnhancedEnabled] / [AgentManager.isEnhancedConfirmed]）。T-FirstUsePremainConsent
 * S1 起，未确认态在**本扩展内先走一次前置确认**（[AgentManager.ensureConfirmedBeforePremain]，
 * 进程启动前唯一能按结果改 JavaParameters 的平台窗口）：同意则本轮即带 -javaagent，首轮不再
 * 必然走 attach 零捕获；EDT/headless 前提不成立或用户拒绝时回落既有 attach 链确认语义
 * （AgentManager.onProcessAppeared），attach 兜底挂载成功后由能力边界通知告知捕获范围（S2）。
 */
class PremainRunConfigurationExtension : RunConfigurationExtension() {

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?,
    ) {
        runCatching {
            // T-GradleTaskInjectScope：范围闸门前置——测试/IDE 任务的 fork JVM 不注入，
            // 且 skip 时不得走到 preparePremainVmOption（那会落盘 jar、开 ServerSocket）。
            when (val scope = decideScope(configuration)) {
                is PremainTaskScope.Decision.Skip -> {
                    LOG.info("premain 注入跳过（范围闸门 ${scope.reason}，config=${configuration.name}）——测试/IDE 任务 fork JVM 不挂 agent")
                    return
                }
                PremainTaskScope.Decision.Inject -> Unit
            }
            val manager = configuration.project.getService(AgentManager::class.java) ?: return
            // T-FirstUsePremainConsent S1：未确认态先前置确认（同意后本轮即可注入，首轮不再必走
            // attach 零捕获）；headless/非EDT/冷却/拒绝各态本入口静默返回，回落既有 attach 链语义。
            manager.ensureConfirmedBeforePremain()
            val option = manager.preparePremainVmOption() ?: return
            params.vmParametersList.add(option)
            LOG.info("premain 注入完成（config=${configuration.name}）：$option")
        }.onFailure { LOG.warn("premain 注入链异常——本次不注入，回落 attach/桥（零劣化）", it) }
    }

    /**
     * 恒 true：本 EP 只在 Java 运行器链路上被求值（`JavaRunConfigurationExtensionManager` 由
     * Java state 调用），非 Java 配置根本不会进入；反过来若在此收窄（如只认 ModuleBasedConfiguration），
     * 会把 Spring Boot/自定义 Java 运行配置一并挡在门外。真正的「要不要注入」判定在范围闸门
     * [decideScope]（任务语义）与 [AgentManager.preparePremainVmOption] 的开关与确认闸门里。
     */
    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean = true

    /**
     * T-GradleTaskInjectScope 范围闸门平台探针：识别 external-system 配置并取 taskNames，
     * 判定本体在纯函数 [PremainTaskScope.decide]。平台类为平台核心（243 `lib/app-client.jar` /
     * 262 `intellij.platform.externalSystem.*.jar`，javap 实证见计划文档 §四），编译期直引、
     * 无反射；探针任何异常按 [PremainTaskScope.Decision.Inject] 处理（回落既有行为，零突变）。
     */
    private fun decideScope(configuration: RunConfigurationBase<*>): PremainTaskScope.Decision = runCatching {
        val external = configuration as? ExternalSystemRunConfiguration
            ?: return@runCatching PremainTaskScope.decide(false, emptyList())
        PremainTaskScope.decide(true, external.settings.taskNames.orEmpty())
    }.getOrElse { t ->
        if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
        LOG.warn("premain 范围闸门探针异常——按既有行为注入（零突变）", t)
        PremainTaskScope.Decision.Inject
    }

    private companion object {
        private val LOG = Logger.getInstance(PremainRunConfigurationExtension::class.java)
    }
}
