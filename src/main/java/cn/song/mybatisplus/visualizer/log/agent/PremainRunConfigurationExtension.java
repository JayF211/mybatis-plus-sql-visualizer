// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/PremainRunConfigurationExtension.kt 移植
package cn.song.mybatisplus.visualizer.log.agent;

import com.intellij.execution.RunConfigurationExtension;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.openapi.diagnostic.Logger;

/**
 * T-M56Phase2-Premain：Java 运行配置准备期注入 {@code -javaagent}（「启动即捕获」补位）。
 *
 * 位置依据（243 javap 实证）：{@code JavaRunConfigurationExtensionManager.updateJavaParameters} 由
 * {@code com.intellij.execution.application.BaseJavaApplicationCommandLineState} 在<b>构建 JavaParameters
 * 之后、进程启动之前</b>调用（javap -c 常量池 #198），这正是 {@code -javaagent} 必须写入的时机——再晚
 * 进程已经起好，注入无效。EP 由 {@code com.intellij.java} 声明（{@code java-impl.jar!/META-INF/JavaPlugin.xml}
 * 的 {@code qualifiedName="com.intellij.runConfigurationExtension"}），本插件 plugin.xml:66 已有硬依赖，
 * 故注册形态就是一条 {@code <runConfigurationExtension implementation="…"/>}，无需 epName。
 * 平台自身同类先例（243 sources 实测）：Kotlin 插件 {@code CoroutineAgentConnector.kt}
 * 同样是 {@code params.vmParametersList.add("-javaagent:$jarPath")}。
 *
 * 零劣化约定：本类只做「问 AgentManager 要一个 VM 选项字符串，非空就加」这一件事，全链
 * {@code runCatching} 包裹——注入链任何异常（jar 落盘失败/端口协商失败/平台 API 意外形态）只记
 * WARN，绝不抛 {@code ExecutionException}、绝不弹窗、绝不动用户保存的配置（JavaParameters 是每次
 * 执行现建的瞬时对象，改动不落盘）。
 *
 * 与 attach 链的关系：本扩展只在「增强捕获开启 + 注入确认已达成」时产出参数（复用
 * {@link AgentManager#isEnhancedEnabled} / {@link AgentManager#isEnhancedConfirmed}），未确认态的首次
 * 知情同意仍由既有 attach 链在进程启动后弹一次（AgentManager.onProcessAppeared）——本任务
 * 不改动该语义，故首次运行必然走 attach，确认后的后续运行才吃到 premain。
 */
public class PremainRunConfigurationExtension extends RunConfigurationExtension {

    @Override
    public <T extends RunConfigurationBase<?>> void updateJavaParameters(
            T configuration, JavaParameters params, RunnerSettings runnerSettings) {
        try {
            AgentManager manager = configuration.getProject().getService(AgentManager.class);
            if (manager == null) {
                return;
            }
            String option = manager.preparePremainVmOption();
            if (option == null) {
                return;
            }
            params.getVMParametersList().add(option);
            LOG.info("premain 注入完成（config=" + configuration.getName() + "）：" + option);
        } catch (Throwable t) {
            LOG.warn("premain 注入链异常——本次不注入，回落 attach/桥（零劣化）", t);
        }
    }

    /**
     * 恒 true：本 EP 只在 Java 运行器链路上被求值（{@code JavaRunConfigurationExtensionManager} 由
     * Java state 调用），非 Java 配置根本不会进入；反过来若在此收窄（如只认 ModuleBasedConfiguration），
     * 会把 Spring Boot/自定义 Java 运行配置一并挡在门外。真正的「要不要注入」判定在
     * {@link AgentManager#preparePremainVmOption} 的开关与确认闸门里。
     */
    @Override
    public boolean isApplicableFor(RunConfigurationBase<?> configuration) {
        return true;
    }

    private static final Logger LOG = Logger.getInstance(PremainRunConfigurationExtension.class);
}
