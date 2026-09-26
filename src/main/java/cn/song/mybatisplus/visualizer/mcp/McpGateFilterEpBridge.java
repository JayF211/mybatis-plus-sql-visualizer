// T-SyncK2JMaster187 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpGateFilterEpBridge.kt
package cn.song.mybatisplus.visualizer.mcp;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import kotlinx.coroutines.flow.FlowKt;

/**
 * T-McpGateDynamicFilter（O-68）注册桥：把 {@link McpToolGate} 的门控决策接进平台
 * {@code com.intellij.mcpServer.mcpToolFilterProvider} EP——<b>外部会话工具清单路径</b>（该链不经过
 * {@code McpToolset.isEnabled()}，字节码取证见 McpToolGate 头注）。
 *
 * <p><b>T-MarketCompatProxy（20260923）：全反射 + 动态代理——字节码零 {@code com.intellij.mcpserver.*}
 * 类型引用</b>。原形态（实现类 implements 平台接口）在 253/261/252/251 上被 Marketplace
 * verifier 按字节码引用判「未解析类」（每线 4 条；片段级 idea-version 与反射防火墙两轮对照
 * 实验均证伪，见 dev-support/verifier-marketplace-baseline.txt 头注）。本形态的注册链全部
 * 经 {@code Class.forName} 字符串 + 擦除泛型（{@code ExtensionPointName.create&lt;Any&gt;} →
 * {@code registerExtension(Any)}），jar 内不再出现平台过滤 API 的类型引用，旧版读数即消；运行期
 * 行为与原实现逐语义一致（见下）。</p>
 *
 * <p><b>运行期语义（与删除的 SqlVisualizerToolGateFilterProvider 逐条对齐）</b>：
 * <ul>
 *   <li>{@code applyFilters(context, …)}：反射调 {@code context.updateState(enabled=false, routerOnly=null,
 *       filter)}——filter 是 {@code kotlin.jvm.functions.Function1} 的动态代理（kotlin stdlib 公共类，
 *       非平台类型，引用无害），invoke 分发到决策核 {@link McpToolGate#shouldHideFromExternalToolsList}，
 *       工具名经 {@code getDescriptor().getName()} 反射读取；双布尔「null=不动该字段」语义与原实现一致
 *       （直接调三参本体，不走 $default 桥）；</li>
 *   <li>{@code getUpdates(…)}：{@code emptyFlow}——本插件门控是设置页静态状态，平台每次列清单重走
 *       applyFilters，空流即「永不主动推送，下一次列表/调用即生效」；</li>
 *   <li>只对名字命中 {@link McpToolGate#TOOL_NAME_PREFIX} 且门控判定隐藏的自家工具置 enabled=false，
 *       其余工具（平台自带与其他插件）零触碰；零弹窗、零执行面。</li>
 * </ul></p>
 *
 * <p><b>加载纪律</b>：本对象只在 {@code McpGateFilterEpRegistrar.registerOnce} 的 {@code Class.forName}
 * 探测通过后被触达；探测失败（253/261/252/251 及更早）时注册不发生，本类即便随 jar 装载也<b>无
 * 任何平台过滤类型的链接要求</b>（方法体零平台类型，平台 API 全部经字符串在运行时解析）。</p>
 *
 * <p><b>签名权威</b>：{@code updateState(Boolean?, Boolean?, Function1)} 与 {@code getDescriptor()}/{@code getName()}
 * 逐条解析自 262.10968.63 mcpserver.jar（src/mcpStub/kotlin/com/intellij/mcpserver/
 * McpToolFilterProvider.kt 桩头注释）；空源性以「运行时实参 + 平台自带实现同源形态」为权威
 * （桩头 20260923 真机 NPE 教训），故 handler 全路径判空。</p>
 *
 * <p>移植注记：master 的 {@code internal object} 按本仓惯例落为 final class + {@link #INSTANCE}
 * + 全静态成员，{@code internal} 升 public；{@code emptyFlow} 经 javap 权威落为
 * {@code kotlinx.coroutines.flow.FlowKt.emptyFlow()}（1.5.0/1.8.0 双版本门面一致）；
 * {@code register} 的 {@code Class.forName} 受检异常按 Java 规则显式声明（master 无受检概念，
 * 调用方 {@code McpGateFilterEpRegistrar} 以 catch Throwable 承接）。</p>
 */
public final class McpGateFilterEpBridge {

    public static final McpGateFilterEpBridge INSTANCE = new McpGateFilterEpBridge();

    private McpGateFilterEpBridge() {
    }

    private static final Logger LOG = Logger.getInstance(McpGateFilterEpBridge.class);

    private static final String EP_NAME = "com.intellij.mcpServer.mcpToolFilterProvider";
    private static final String FILTER_IFACE_FQN = "com.intellij.mcpserver.McpToolFilterProvider";
    private static final String FUNCTION1_FQN = "kotlin.jvm.functions.Function1";

    public static void register() throws ClassNotFoundException {
        Class<?> filterIface = Class.forName(FILTER_IFACE_FQN);
        ClassLoader loader = McpGateFilterEpBridge.class.getClassLoader();
        Object provider = Proxy.newProxyInstance(loader, new Class<?>[] {filterIface}, providerHandler());
        ExtensionPointName<Object> ep = ExtensionPointName.create(EP_NAME);
        ep.getPoint(null).registerExtension(provider);
        LOG.info("O-68: sqlv gate filter registered onto mcpToolFilterProvider EP (proxy form)");
    }

    /** 平台对 provider 的全部调用分发（含 Object 基方法：代理不会绕过 handler）。 */
    private static InvocationHandler providerHandler() {
        return (proxy, method, args) -> {
            switch (method.getName()) {
                case "applyFilters": {
                    applyFilters(args != null && args.length > 0 ? args[0] : null);
                    return null;
                }
                case "getUpdates":
                    // emptyFlow：永不主动推送，下一次列表/调用即生效
                    return FlowKt.emptyFlow();
                case "toString":
                    return "SqlVisualizerToolGateFilterProvider(proxy)";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return args != null && args.length > 0 && args[0] == proxy;
                default:
                    return null;
            }
        };
    }

    /** 原实现 applyFilters 的反射等价体：改写 context 内各工具的 enabled 状态。 */
    private static void applyFilters(Object context) {
        if (context == null) {
            return;
        }
        try {
            InvocationHandler predicateHandler = (predicateProxy, predicateMethod, predicateArgs) -> {
                if (predicateMethod.getName().equals("invoke")) {
                    // 决策核收非空名（与原实现传 descriptor.name 的口径一致）；名字读不出
                    // （理论不可达）时按平台默认不隐藏处理——宁漏隐藏不误隐藏。
                    String toolName = readToolName(
                        predicateArgs != null && predicateArgs.length > 0 ? predicateArgs[0] : null
                    );
                    return toolName != null ? McpToolGate.shouldHideFromExternalToolsList(toolName) : Boolean.FALSE;
                }
                return null;
            };
            Object predicate = Proxy.newProxyInstance(
                McpGateFilterEpBridge.class.getClassLoader(),
                new Class<?>[] {Class.forName(FUNCTION1_FQN)},
                predicateHandler
            );
            // Kotlin Boolean::class.javaObjectType = 装箱 Boolean（updateState 形参为可空装箱值）
            Method updateState = context.getClass().getMethod(
                "updateState",
                Boolean.class,
                Boolean.class,
                Class.forName(FUNCTION1_FQN)
            );
            updateState.invoke(context, Boolean.FALSE, null, predicate);
        } catch (Throwable t) {
            LOG.warn("O-68: applyFilters reflection dispatch failed（门控对外部清单退化，其余功能不受影响）", t);
        }
    }

    /** 平台 {@code McpTool.descriptor.name} 的反射读取；任何一步失败返回 null（谓词按不隐藏处理）。 */
    private static String readToolName(Object tool) {
        if (tool == null) {
            return null;
        }
        Object descriptor;
        try {
            descriptor = tool.getClass().getMethod("getDescriptor").invoke(tool);
        } catch (Throwable ignored) {
            return null; // runCatching{...}.getOrNull() 语义
        }
        if (descriptor == null) {
            return null;
        }
        try {
            Object name = descriptor.getClass().getMethod("getName").invoke(descriptor);
            return name instanceof String ? (String) name : null; // Kotlin `as? String` 安全转译
        } catch (Throwable ignored) {
            return null;
        }
    }
}
