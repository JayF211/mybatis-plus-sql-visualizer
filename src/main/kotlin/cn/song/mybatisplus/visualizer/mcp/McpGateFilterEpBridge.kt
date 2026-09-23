package cn.song.mybatisplus.visualizer.mcp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.emptyFlow

/**
 * T-McpGateDynamicFilter（O-68）注册桥：把 [McpToolGate] 的门控决策接进平台
 * `com.intellij.mcpServer.mcpToolFilterProvider` EP——**外部会话工具清单路径**（该链不经过
 * `McpToolset.isEnabled()`，字节码取证见 McpToolGate 头注）。
 *
 * **T-MarketCompatProxy（20260923）：全反射 + 动态代理——字节码零 `com.intellij.mcpserver.*`
 * 类型引用**。原形态（实现类 implements 平台接口）在 253/261/252/251 上被 Marketplace
 * verifier 按字节码引用判「未解析类」（每线 4 条；片段级 idea-version 与反射防火墙两轮对照
 * 实验均证伪，见 dev-support/verifier-marketplace-baseline.txt 头注）。本形态的注册链全部
 * 经 `Class.forName` 字符串 + 擦除泛型（`ExtensionPointName.create<Any>` →
 * `registerExtension(Any)`），jar 内不再出现平台过滤 API 的类型引用，旧版读数即消；运行期
 * 行为与原实现逐语义一致（见下）。
 *
 * **运行期语义（与删除的 SqlVisualizerToolGateFilterProvider 逐条对齐）**：
 *  - `applyFilters(context, …)`：反射调 `context.updateState(enabled=false, routerOnly=null,
 *    filter)`——filter 是 `kotlin.jvm.functions.Function1` 的动态代理（kotlin stdlib 公共类，
 *    非平台类型，引用无害），invoke 分发到决策核 [McpToolGate.shouldHideFromExternalToolsList]，
 *    工具名经 `getDescriptor().getName()` 反射读取；双布尔「null=不动该字段」语义与原实现一致
 *    （直接调三参本体，不走 $default 桥）；
 *  - `getUpdates(…)`：`emptyFlow`——本插件门控是设置页静态状态，平台每次列清单重走
 *    applyFilters，空流即「永不主动推送，下一次列表/调用即生效」；
 *  - 只对名字命中 [McpToolGate.TOOL_NAME_PREFIX] 且门控判定隐藏的自家工具置 enabled=false，
 *    其余工具（平台自带与其他插件）零触碰；零弹窗、零执行面。
 *
 * **加载纪律**：本对象只在 [McpGateFilterRegistrar.registerOnce] 的 `Class.forName` 探测
 * 通过后被触达；探测失败（253/261/252/251 及更早）时注册不发生，本类即便随 jar 装载也**无
 * 任何平台过滤类型的链接要求**（方法体零平台类型，平台 API 全部经字符串在运行时解析）。
 *
 * **签名权威**：`updateState(Boolean?, Boolean?, Function1)` 与 `getDescriptor()`/`getName()`
 * 逐条解析自 262.10968.63 mcpserver.jar（src/mcpStub/kotlin/com/intellij/mcpserver/
 * McpToolFilterProvider.kt 桩头注释）；空源性以「运行时实参 + 平台自带实现同源形态」为权威
 * （桩头 20260923 真机 NPE 教训），故 handler 全路径判空。
 */
internal object McpGateFilterEpBridge {

    private val LOG = Logger.getInstance(McpGateFilterEpBridge::class.java)

    private const val EP_NAME = "com.intellij.mcpServer.mcpToolFilterProvider"
    private const val FILTER_IFACE_FQN = "com.intellij.mcpserver.McpToolFilterProvider"
    private const val FUNCTION1_FQN = "kotlin.jvm.functions.Function1"

    fun register() {
        val filterIface = Class.forName(FILTER_IFACE_FQN)
        val loader = McpGateFilterEpBridge::class.java.classLoader
        val provider = Proxy.newProxyInstance(loader, arrayOf(filterIface), providerHandler())
        val ep = ExtensionPointName.create<Any>(EP_NAME)
        ep.getPoint(null).registerExtension(provider)
        LOG.info("O-68: sqlv gate filter registered onto mcpToolFilterProvider EP (proxy form)")
    }

    /** 平台对 provider 的全部调用分发（含 Object 基方法：代理不会绕过 handler）。 */
    private fun providerHandler() = InvocationHandler { proxy, method, args ->
        when (method.name) {
            "applyFilters" -> {
                applyFilters(args?.firstOrNull())
                null
            }
            "getUpdates" -> emptyFlow<Any>()
            "toString" -> "SqlVisualizerToolGateFilterProvider(proxy)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> args?.firstOrNull() === proxy
            else -> null
        }
    }

    /** 原实现 applyFilters 的反射等价体：改写 context 内各工具的 enabled 状态。 */
    private fun applyFilters(context: Any?) {
        if (context == null) return
        runCatching {
            val predicate = Proxy.newProxyInstance(
                McpGateFilterEpBridge::class.java.classLoader,
                arrayOf(Class.forName(FUNCTION1_FQN)),
            ) { _, method, args ->
                if (method.name == "invoke") {
                    // 决策核收非空名（与原实现传 descriptor.name 的口径一致）；名字读不出
                    // （理论不可达）时按平台默认不隐藏处理——宁漏隐藏不误隐藏。
                    readToolName(args?.firstOrNull())
                        ?.let(McpToolGate::shouldHideFromExternalToolsList) ?: false
                } else {
                    null
                }
            }
            val updateState = context.javaClass.getMethod(
                "updateState",
                Boolean::class.javaObjectType,
                Boolean::class.javaObjectType,
                Class.forName(FUNCTION1_FQN),
            )
            updateState.invoke(context, false, null, predicate)
        }.onFailure {
            LOG.warn("O-68: applyFilters reflection dispatch failed（门控对外部清单退化，其余功能不受影响）", it)
        }
    }

    /** 平台 `McpTool.descriptor.name` 的反射读取；任何一步失败返回 null（谓词按不隐藏处理）。 */
    private fun readToolName(tool: Any?): String? {
        val descriptor = runCatching {
            tool?.javaClass?.getMethod("getDescriptor")?.invoke(tool)
        }.getOrNull() ?: return null
        return runCatching {
            descriptor.javaClass.getMethod("getName").invoke(descriptor) as? String
        }.getOrNull()
    }
}
