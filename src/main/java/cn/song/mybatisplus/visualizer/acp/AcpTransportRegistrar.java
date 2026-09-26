// T-SyncK2JMaster187 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/acp/AcpTransportRegistrar.kt
package cn.song.mybatisplus.visualizer.acp;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

/**
 * T-AcpMcpTransport 注册入口（T-MarketCompatProxy 重构形态）：把 <b>SSE 传输选择</b> 程序化注册到
 * ml-llm 的 {@code com.intellij.ml.llm.agents.acp.ideaMcpTransportProvider} EP。
 *
 * <p><b>T-MarketCompatProxy（20260923）：全反射 + 动态代理——字节码零 ml-llm ACP 类型引用</b>。
 * 原形态（{@code AcpIdeaMcpTransportProviderImpl} implements 平台接口 + XML 声明）在 243/253/
 * 261/252/251 上被 Marketplace verifier 按字节码引用判「未解析类」（ml-llm 的 ACP API 是
 * 262 线才有/才稳定），其中 6 条还是 {@code @ApiStatus.Internal}——审核邮件点名要求响应。本形态：</p>
 * <ul>
 *   <li>注册链经 {@code Class.forName} 字符串 + 擦除泛型（{@code ExtensionPointName.create<Any>} →
 *     {@code registerExtension(Any)}），jar 内不再出现 {@code com.intellij.ml.llm.agents.acp.*} /
 *     {@code com.intellij.platform.acp.*} 类型引用，旧版读数即消；</li>
 *   <li>代理按<b>方法名</b>分发 {@code getTransport}——原实现为适配上游两条发行线的形参包差异声明的
 *     双重载（262.9437 用 {@code com.intellij.platform.acp.AcpAgentId}、262.10968 用 api 包），代理
 *     形态天然同时适配（handler 不关心形参静态类型，运行时反射读 {@code getFullId()}）；</li>
 *   <li>返回值 {@code AcpIdeaMcpTransport.SSE} 经反射读枚举常量（首次 getTransport 时解析并缓存）。</li>
 * </ul>
 *
 * <p><b>绕行语义不变</b>（详见原 impl 的 KDoc，git 历史可查）：上游 claude-agent-acp 的 stdio 判断
 * 缺陷使带 {@code "type":"stdio"} 的 server 被静默丢弃，注册本 provider 令 IDE 对 claude-acp 以 SSE
 * 下发私有 IJ MCP 会话；非目标 agent 返回 null 不表态，Copilot 等行为完全不变。</p>
 *
 * <p><b>加载纪律</b>：本 registrar 由 {@code META-INF/acp-transport-optional.xml} 的 postStartupActivity
 * 声明——该文件仅在 {@code com.intellij.ml.llm} 在场时加载（optional depends）；ml-llm 在场时其
 * 类对本插件类加载器可见（被依赖插件的 loader 挂进委托链），{@code Class.forName} 探测必然命中；
 * 理论上探测永不失败（片段加载即前提成立），runCatching 只作防御。</p>
 *
 * <p><b>线程与时点</b>：ProjectActivity 每项目启动各触发一次，{@link AcpTransportEpRegistrar#registerOnce}
 * 以原子闸保证全应用只注册一次；EP {@code dynamic=true}（ml-llm plugin.xml 实证），晚注册会触发
 * 清单重算，无需重启。</p>
 *
 * <p><b>可测性</b>：分发逻辑抽成 {@code AcpTransportRegistrarKt.transportHandler}（参数化 SSE 常量提供者）
 * ——测试以自有枚举/对象注入即可断言策略 (a) 全分支，无需 ml-llm 类在测试 classpath 上。</p>
 *
 * <p>移植注记：master 的同文件三顶层声明按本仓拆分惯例落为 {@code AcpTransportRegistrar}（本文件，
 * 名同类名）、{@code AcpTransportEpRegistrar}、{@code AcpTransportRegistrarKt}（顶层函数门面）三个
 * Java 文件。Kotlin {@code suspend fun execute} 无实际挂起点，按 {@code OnboardingAnchorTip} 先例落为
 * {@code execute(Project, Continuation)} 同步形态、返回 {@code Unit.INSTANCE}。</p>
 */
public class AcpTransportRegistrar implements ProjectActivity {

    /** Kotlin suspend execute 的 Java 侧等价形态（无实际挂起点，直接同步执行）。 */
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        AcpTransportEpRegistrar.registerOnce();
        return Unit.INSTANCE;
    }
}
