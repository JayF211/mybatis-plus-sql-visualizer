package cn.song.mybatisplus.visualizer.mcp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T-McpGateDynamicFilter（O-68）注册入口：把 [SqlVisualizerToolGateFilterProvider] 程序化注册到
 * 平台 `com.intellij.mcpServer.mcpToolFilterProvider` EP。
 *
 * **为什么是探测式程序化注册而非 XML 声明**：
 *  1. 该 EP 仅 262+ 存在（253 线 idea-2025.3.5 mcpserver.jar 本地实测无此类）——XML 声明在
 *     253 会按未知 EP 拒载，与十个 toolset 同文件即连带失联；
 *  2. 独立片段方案需要第二条 `com.intellij.mcpServer` optional depends，verifier 判
 *     "duplicated dependency" 结构缺陷（2026-09-23 verifyGate 实测）——同插件依赖不可声明两次。
 * 故挂在本文件于 mcp-optional.xml（mcpServer 在场才加载）的 postStartupActivity：
 * [Class.forName] 探测 EP 接口（253 无 → 静默跳过，行为与改动前一致），命中才走
 * [McpGateFilterEpBridge] 的类型化注册（桥类含 262-only 类型，仅在探测通过后被触达）。
 *
 * 线程与时点：ProjectActivity 每项目启动各触发一次，[McpGateFilterEpRegistrar.registerOnce]
 * 以原子闸保证全应用只注册一次；EP 层带 ExtensionPointListener（字节码实证），晚注册亦会
 * 触发清单重算，无需重启 IDE。
 */
class McpGateFilterRegistrar : ProjectActivity {

    override suspend fun execute(project: Project) {
        McpGateFilterEpRegistrar.registerOnce()
    }
}

internal object McpGateFilterEpRegistrar {

    private val LOG = Logger.getInstance(McpGateFilterEpRegistrar::class.java)

    private val registered = AtomicBoolean(false)

    fun registerOnce() {
        if (registered.get()) return
        val iface = runCatching { Class.forName("com.intellij.mcpserver.McpToolFilterProvider") }.getOrNull()
            ?: return
        runCatching { McpGateFilterEpBridge.register() }
            .onSuccess {
                registered.set(true)
                LOG.info("O-68: sqlv gate filter registered onto mcpToolFilterProvider EP")
            }
            .onFailure {
                LOG.warn("O-68: mcpToolFilterProvider EP 注册失败（外部会话门控退化，其余功能不受影响）", it)
            }
    }
}
