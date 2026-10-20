// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/PremainInjection.kt 移植
package cn.song.mybatisplus.visualizer.log.agent;

/**
 * T-M56Phase2-Premain：premain 注入决策与 VM 选项拼装（纯函数，无平台依赖）。
 *
 * 拆出来的理由有两条：一是注入链要过四道闸门（增强开关 / 注入确认 / agent jar 可用 / 端口协商），
 * 组合面靠真机跑不现实，抽成纯函数后表驱动一次覆盖；二是注入发生在<b>用户运行配置的准备期</b>，
 * 任何一步失败都不能影响用户 Run——判定与副作用分离，调用方（{@link AgentManager}）只在
 * {@link Decision.Inject} 时落盘/协商，其余分支只留日志。
 *
 * 闸门顺序即求值顺序（{@link #decide} 的分支序）：前两道为假时调用方<b>不得</b>触发 jar 提取与
 * 端口协商（后者会开 ServerSocket），{@code agentJarAvailable} / {@code port} 传占位值即可。
 */
public final class PremainInjection {

    private PremainInjection() {
    }

    /** 增强捕获总开关关闭（复用 {@link AgentManager#isEnhancedEnabled} 语义）。 */
    public static final String REASON_DISABLED = "enhanced-disabled";

    /** agent jar 解压落盘失败（资源缺失/磁盘不可写）。 */
    public static final String REASON_NO_JAR = "agent-jar-unavailable";

    /** 端口前置协商失败（loopback 绑定失败或挂起通道已达上限）。 */
    public static final String REASON_NO_PORT = "port-negotiation-failed";

    /** 注入判定结果（Kotlin 嵌套形态：Inject/Skip 为 Decision 的嵌套类型，调用点 `Decision.Skip` 双形态对齐）。 */
    public sealed interface Decision {

        /** 注入：{@code port} 为已前置协商并处于监听态的 loopback 端口。 */
        record Inject(
                /** 已前置协商并处于监听态的 loopback 端口。 */
                int port
        ) implements Decision {
        }

        /** 跳过：{@code reason} 取本类常量，仅用于日志与测试断言。 */
        record Skip(
                /** 跳过原因（取 {@link PremainInjection} 的 REASON_* 常量）。 */
                String reason
        ) implements Decision {
        }
    }

    /**
     * 三闸门判定（纯函数）。{@code port} 为 null/非正即视为协商失败——端口 0 在 agent 侧
     * {@code AgentArgs.parse} 后会被 {@code AgentMain.install} 判为「无端口可上报」直接放弃挂载
     * （AgentMain.java:45-48），故这里同样拒绝。
     *
     * <p>T-EnhancedCaptureDefaultOn（2026-10-19）：注入确认闸门已移除——开关打开即注入。</p>
     */
    public static Decision decide(boolean enhancedEnabled, boolean agentJarAvailable, Integer port) {
        if (!enhancedEnabled) {
            return new Decision.Skip(REASON_DISABLED);
        }
        if (!agentJarAvailable) {
            return new Decision.Skip(REASON_NO_JAR);
        }
        if (port == null || port <= 0) {
            return new Decision.Skip(REASON_NO_PORT);
        }
        return new Decision.Inject(port);
    }

    /** 三闸门判定（agentJarAvailable/port 均缺省，Kotlin 默认参数的便捷重载）。 */
    public static Decision decide(boolean enhancedEnabled) {
        return decide(enhancedEnabled, true, null);
    }

    /**
     * {@code -javaagent} VM 选项：{@code -javaagent:<jar 绝对路径>=port=<n>;logLevel=warn;stackFrames=<n>}。
     *
     * args 段直接复用 {@link AgentProtocol#agentArgs(int, String, int)}（AgentProtocol.kt:62），与 agent 侧
     * {@code AgentArgs.parse}（AgentArgs.java:20-55）的 {@code k=v} 分号契约逐字对齐——两侧任一侧改动
     * 都会被 {@code AgentProtocolSyncTest} 的常量对齐守卫拦下。
     * T-StackHoverUi：{@code stackMaxFrames} 由调用方传设置值（本类保持纯函数不依赖 config 包；
     * 缺省 24 与 PluginSettings 缺省同值）。
     */
    public static String vmOption(String jarPath, int port, int stackMaxFrames) {
        return "-javaagent:" + jarPath + "=" + AgentProtocol.agentArgs(port, "warn", stackMaxFrames);
    }

    /** {@code -javaagent} VM 选项（stackMaxFrames 缺省 24，Kotlin 默认参数的便捷重载）。 */
    public static String vmOption(String jarPath, int port) {
        return vmOption(jarPath, port, 24);
    }
}
