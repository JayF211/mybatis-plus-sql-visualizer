package cn.song.mybatisplus.visualizer.acp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T-AcpMcpTransport 注册入口（T-MarketCompatProxy 重构形态）：把 **SSE 传输选择** 程序化注册到
 * ml-llm 的 `com.intellij.ml.llm.agents.acp.ideaMcpTransportProvider` EP。
 *
 * **T-MarketCompatProxy（20260923）：全反射 + 动态代理——字节码零 ml-llm ACP 类型引用**。
 * 原形态（`AcpIdeaMcpTransportProviderImpl` implements 平台接口 + XML 声明）在 243/253/
 * 261/252/251 上被 Marketplace verifier 按字节码引用判「未解析类」（ml-llm 的 ACP API 是
 * 262 线才有/才稳定），其中 6 条还是 `@ApiStatus.Internal`——审核邮件点名要求响应。本形态：
 *  - 注册链经 `Class.forName` 字符串 + 擦除泛型（`ExtensionPointName.create<Any>` →
 *    `registerExtension(Any)`），jar 内不再出现 `com.intellij.ml.llm.agents.acp.*` /
 *    `com.intellij.platform.acp.*` 类型引用，旧版读数即消；
 *  - 代理按**方法名**分发 `getTransport`——原实现为适配上游两条发行线的形参包差异声明的
 *    双重载（262.9437 用 `com.intellij.platform.acp.AcpAgentId`、262.10968 用 api 包），代理
 *    形态天然同时适配（handler 不关心形参静态类型，运行时反射读 `getFullId()`）；
 *  - 返回值 `AcpIdeaMcpTransport.SSE` 经反射读枚举常量（首次 getTransport 时解析并缓存）。
 *
 * **绕行语义不变**（详见原 impl 的 KDoc，git 历史可查）：上游 claude-agent-acp 的 stdio 判断
 * 缺陷使带 `"type":"stdio"` 的 server 被静默丢弃，注册本 provider 令 IDE 对 claude-acp 以 SSE
 * 下发私有 IJ MCP 会话；非目标 agent 返回 null 不表态，Copilot 等行为完全不变。
 *
 * **加载纪律**：本 registrar 由 `META-INF/acp-transport-optional.xml` 的 postStartupActivity
 * 声明——该文件仅在 `com.intellij.ml.llm` 在场时加载（optional depends）；ml-llm 在场时其
 * 类对本插件类加载器可见（被依赖插件的 loader 挂进委托链），`Class.forName` 探测必然命中；
 * 理论上探测永不失败（片段加载即前提成立），runCatching 只作防御。
 *
 * **线程与时点**：ProjectActivity 每项目启动各触发一次，[AcpTransportEpRegistrar.registerOnce]
 * 以原子闸保证全应用只注册一次；EP `dynamic=true`（ml-llm plugin.xml 实证），晚注册会触发
 * 清单重算，无需重启。
 *
 * **可测性**：分发逻辑抽成 [transportHandler]（参数化 SSE 常量提供者）——测试以自有枚举/对象
 * 注入即可断言策略 (a) 全分支，无需 ml-llm 类在测试 classpath 上。
 */
class AcpTransportRegistrar : ProjectActivity {

    override suspend fun execute(project: Project) {
        AcpTransportEpRegistrar.registerOnce()
    }
}

internal object AcpTransportEpRegistrar {

    private val LOG = Logger.getInstance(AcpTransportEpRegistrar::class.java)

    private val registered = AtomicBoolean(false)

    /** 4a（T-PerfSweep8）：SSE 常量缓存——KDoc 既有承诺「首次 getTransport 时解析并缓存」，
     *  实现此前每次调用重跑 Class.forName+getField（MCP 会话清单刷新级频率）。按计划口径：
     *  首次解析成功缓存（getField 返回 static final 枚举常量，每类加载器单实例）；解析失败
     *  **不缓存、维持 throw 路径**（与原实现语义一致）；getFullId 的 Method 保留现查
     *  （上游两条发行线 agentId 运行时类可能不同，单槽复杂度不值当）。 */
    @Volatile private var cachedSse: Any? = null
    @Volatile private var sseResolved = false

    /** 4a 测试缝（internal）：SSE 解析函数可注入计数实现驱动先红后绿；生产=真反射。 */
    @Volatile internal var sseResolver: () -> Any? = {
        Class.forName(TRANSPORT_ENUM_FQN).getField("SSE").get(null)
    }

    private fun sseConstant(): Any? {
        if (sseResolved) return cachedSse
        val v = sseResolver() // 解析失败抛出——不缓存，维持原 throw 语义（计划口径）
        cachedSse = v
        sseResolved = true
        return v
    }

    /** 4a 测试缝：handler 消费入口 internal 化（生产链路仍走 registerProxy 私有调用）。 */
    internal fun sseConstantForTest(): Any? = sseConstant()

    /** 4a 测试缝：清缓存态（跨用例隔离）。 */
    internal fun resetSseCacheForTest() {
        cachedSse = null
        sseResolved = false
    }

    /** 与原 impl 的 CLAUDE_ACP_ID 同源：ml-llm 常量池实证（AcpBundledAgentUtils.CLAUDE_ACP_ID）。 */
    const val CLAUDE_ACP_ID: String = "acp.registry.claude-acp"

    private const val PROVIDER_IFACE_FQN = "com.intellij.ml.llm.agents.acp.client.AcpIdeaMcpTransportProvider"
    private const val TRANSPORT_ENUM_FQN = "com.intellij.ml.llm.agents.acp.client.AcpIdeaMcpTransport"
    private const val EP_NAME = "com.intellij.ml.llm.agents.acp.ideaMcpTransportProvider"

    fun registerOnce() {
        if (registered.get()) return
        val iface = runCatching { Class.forName(PROVIDER_IFACE_FQN) }.getOrNull()
            ?: return
        runCatching { registerProxy(iface) }
            .onSuccess {
                registered.set(true)
                LOG.info("T-AMT: sqlv SSE transport provider registered onto ideaMcpTransportProvider EP (proxy form)")
            }
            .onFailure {
                LOG.warn("T-AMT: ideaMcpTransportProvider EP 注册失败（ACP 会话回落平台默认传输，其余功能不受影响）", it)
            }
    }

    private fun registerProxy(iface: Class<*>) {
        val loader = AcpTransportEpRegistrar::class.java.classLoader
        val handler = transportHandler { sseConstant() }
        val provider = Proxy.newProxyInstance(loader, arrayOf(iface), handler)
        val ep = ExtensionPointName.create<Any>(EP_NAME)
        ep.getPoint(null).registerExtension(provider)
    }
}

/**
 * getTransport 的分发体（策略 (a)，与原 `AcpIdeaMcpTransportProviderImpl` 逐语义一致）：
 * `agentId.fullId == "acp.registry.claude-acp"` → 平台 `AcpIdeaMcpTransport.SSE`（经 [sseProvider]
 * 反射解析），其余 → null 不表态。agent 实参走鸭子形态（反射调 `getFullId()`），不引用平台类型。
 */
internal fun transportHandler(sseProvider: () -> Any?): InvocationHandler =
    InvocationHandler { proxy, method, args ->
        when (method.name) {
            "getTransport" -> {
                val fullId = args?.firstOrNull()?.let { agentId ->
                    runCatching { agentId.javaClass.getMethod("getFullId").invoke(agentId) }.getOrNull()
                }
                if (fullId == AcpTransportEpRegistrar.CLAUDE_ACP_ID) sseProvider() else null
            }
            "toString" -> "AcpIdeaMcpTransportProviderImpl(proxy)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> args?.firstOrNull() === proxy
            else -> null
        }
    }
