// T-AcpMcpTransport 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/acp/AcpIdeaMcpTransportProviderImpl.kt 移植
package cn.song.mybatisplus.visualizer.acp;

import com.intellij.platform.acp.AcpAgentId;
import com.intellij.ml.llm.agents.acp.client.AcpIdeaMcpTransport;
import com.intellij.ml.llm.agents.acp.client.AcpIdeaMcpTransportProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * T-AcpMcpTransport：让 ACP 会话（Claude Agent）以 <b>SSE</b> 形式拿到 IDE 私有 IJ MCP 会话，
 * 绕过上游 {@code @agentclientprotocol/claude-agent-acp} 的 stdio 判断缺陷。
 *
 * <p><b>缺陷本体</b>（0.66.0 / 0.76.0 / 0.79.0 三版一致，{@code dist/acp-agent.js}）：
 * <pre>{@code
 * if ("type" in server && (server.type === "http" || server.type === "sse")) { … }
 * else if (!("type" in server)) { …stdio… }   // 注释写「有无显式 type 都行」，条件却要求没有 type
 * }</pre>
 * 而 AI Assistant 序列化 stdio server 必带 {@code "type": "stdio"}（{@code com.agentclientprotocol.model.McpServer$Stdio}
 * 标 {@code @SerialName("stdio")}）→ 两个分支都不进，<b>静默丢弃</b>。故 agent 工具表里没有任何 MCP 服务器。
 *
 * <p><b>绕行原理</b>：同一段代码的 {@code type === "sse"} 分支实现正确，而 {@code claude-agent-acp} 的 initialize
 * 响应声明 {@code mcpCapabilities: { http: true, sse: true }}。IDE 侧的选择逻辑（{@code AcpIdeaMcpProviderImpl$getMcpServer$3$1}
 * 字节码实证）：
 * <pre>{@code
 * preferred = AcpIdeaMcpTransportProvider.resolveTransport(agentId)   // firstNotNullOfOrNull，首个非 null 胜出
 * supported = when (preferred) { SSE -> mcpCapabilities.sse; STDIO -> true }
 * if (!supported) preferred = STDIO                                  // 能力不支持时自动回落
 * }</pre>
 * 注册本 provider 返回 SSE，即让私有 IJ MCP 会话改走 SSE 分支。
 *
 * <p><b>取值策略取 (a) 而非计划推荐的 (b)</b>（T-AMT-02，理由如下，结论以本注释为准）：
 * <ul>
 *   <li>上游缺陷<b>只在 claude-agent-acp 这一个包里</b>：计划 §1.4 的对照实验已证 {@code codex-acp@1.4.0} 的
 *     stdio schema 无 type 约束、带 {@code type: "stdio"} 亦能解析（实测通过）。即 (b) 的「覆盖面广」
 *     并不带来额外修复面——受该 bug 影响的 agent 就是 claude-acp 一个。</li>
 *   <li>而 (b) 会给<b>其余全部 ACP agent</b>（gemini / junie / codex / 自定义）改传输：凡声明
 *     {@code sse: true} 者一律从 STDIO 切到 SSE。<b>R3（SSE 通道 agent 侧可达性）尚未实证</b>，一旦不通，
 *     (b) 就把当前能用的 agent 一并改坏；(a) 的失败面只落在 claude-acp——它今天本就是坏的，
 *     零回归面。</li>
 *   <li>(b) 还会长期压过 JetBrains 自身对某个 agent 的传输选择（EP 是「首个非 null 胜出」，
 *     注册顺序不受我们控制），(a) 把这一副作用收窄到单个 agent。</li>
 *   <li>R4 的应对栏亦指向 (a)（「T-AMT-02 取 (a) 保守策略」）。</li>
 * </ul>
 * 若后续 SSE 通道经真机实证可达且稳定，再按计划 §七.4 评估是否放宽到 (b)。
 *
 * <p><b>不表态语义</b>：非目标 agent 返回 null，让位给后续注册者（官方 {@code AcpCopilotIdeaMcpTransportProvider}
 * 对非 Copilot agent 正是返回 null），Copilot 行为完全不变。
 *
 * <p><b>加载隔离</b>：本类只被 {@code META-INF/acp-transport-optional.xml} 引用，而该文件仅在
 * {@code com.intellij.ml.llm} 插件在场时加载（{@code plugin.xml} 的 {@code <depends optional="true" config-file=…>}）——
 * AI Assistant 缺席（含基线 243 与全部 CI 目标平台）时整文件不加载，本类永不被实例化/链接。
 * 编译期类型来自 mcpStub 桩源集（桩产物不进插件 jar）。
 *
 * <p>移植注记：master 的 Kotlin {@code class AcpIdeaMcpTransportProviderImpl} 的 Java 承载。Kotlin 属性
 * {@code agentId.fullId} 在 Java 侧经平台接口访问器 {@code agentId.getFullId()} 取值（真类
 * {@code AcpRegistryAgentId} 的字节码访问器同名）；companion object 的 {@code const val CLAUDE_ACP_ID}
 * 提升为 {@code public static final} 字段——Kotlin 侧 {@code const val} 本就编译为外层类的静态字段，
 * 故调用方写法（{@code AcpIdeaMcpTransportProviderImpl.CLAUDE_ACP_ID}）逐字不变。
 * 平台经 EP 反射实例化本类，公开无参构造器即 Kotlin 默认构造器。</p>
 */
public class AcpIdeaMcpTransportProviderImpl implements AcpIdeaMcpTransportProvider {

    /**
     * Claude Agent 的完整 agent id。字面量取值有两处权威实证：
     *  - {@code AcpBundledAgentUtils.CLAUDE_ACP_ID = "acp.registry.claude-acp"}（ml-llm 的
     *    {@code intellij.ml.llm.agents.acp.jar} 常量池，ACC_STATIC|ACC_FINAL 字符串常量）；
     *  - {@code AcpRegistryAgentId.getFullId()} = 常量前缀 {@code "acp.registry."} + rawId（字节码实证），
     *    与 {@code AcpAgentId.REGISTRY_PREFIX} 一致。
     * 故意内联字面量而不引 {@code AcpBundledAgentUtils}：少一个桩类型即少一处版本漂移面，
     * 而该常量的取值已由上述两处实证钉死。
     */
    public static final String CLAUDE_ACP_ID = "acp.registry.claude-acp";

    /**
     * 返回该 agent 应使用的传输方式：仅目标 agent（{@link #CLAUDE_ACP_ID}）取 {@link AcpIdeaMcpTransport#SSE}，
     * 其余一律返回 null（不表态，让位给后续注册者或 IDE 默认 STDIO）。
     *
     * <p>判定落在 {@code fullId} 上，不能退化成 rawId 后缀匹配——自定义 agent（{@code AcpCustomAgentId}）的
     * fullId 前缀是 {@code "acp."} 而非 {@code "acp.registry."}，rawId 同名亦不得命中。
     * Kotlin 侧 {@code agentId.fullId == CLAUDE_ACP_ID} 的 {@code ==} 是 {@code equals} 语义（且 null 安全），
     * Java 侧以常量作接收者的 {@code equals} 逐字等价。</p>
     */
    @Nullable
    @Override
    public AcpIdeaMcpTransport getTransport(@NotNull AcpAgentId agentId) {
        return CLAUDE_ACP_ID.equals(agentId.getFullId()) ? AcpIdeaMcpTransport.SSE : null;
    }
}
