// T-SyncK2JMaster187 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpGateFilterRegistrar.kt（object McpGateFilterEpRegistrar 声明）
package cn.song.mybatisplus.visualizer.mcp;

import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * T-McpGateDynamicFilter（O-68）的探测式注册器（master 同文件 {@code object McpGateFilterEpRegistrar}
 * 的 Java 承载，{@code internal} 按本仓惯例升 public）：{@code Class.forName} 探测平台
 * {@code McpToolFilterProvider} 接口（253/261/252/251 及更早无此类 → 静默跳过），命中才触达
 * {@link McpGateFilterEpBridge} 的类型化注册；原子闸保证全应用只注册一次。
 * 背景与加载纪律见 {@link McpGateFilterRegistrar} KDoc。
 */
public final class McpGateFilterEpRegistrar {

    public static final McpGateFilterEpRegistrar INSTANCE = new McpGateFilterEpRegistrar();

    private McpGateFilterEpRegistrar() {
    }

    private static final Logger LOG = Logger.getInstance(McpGateFilterEpRegistrar.class);

    private static final AtomicBoolean registered = new AtomicBoolean(false);

    private static final String FILTER_IFACE_FQN = "com.intellij.mcpserver.McpToolFilterProvider";

    public static void registerOnce() {
        if (registered.get()) {
            return;
        }
        try {
            Class.forName(FILTER_IFACE_FQN);
        } catch (Throwable ignored) {
            return; // runCatching{...}.getOrNull() ?: return（探测失败静默跳过）
        }
        try {
            McpGateFilterEpBridge.register();
            registered.set(true);
            LOG.info("O-68: sqlv gate filter registered onto mcpToolFilterProvider EP");
        } catch (Throwable t) {
            LOG.warn("O-68: mcpToolFilterProvider EP 注册失败（外部会话门控退化，其余功能不受影响）", t);
        }
    }
}
