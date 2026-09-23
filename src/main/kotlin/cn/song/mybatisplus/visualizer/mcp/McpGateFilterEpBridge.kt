package cn.song.mybatisplus.visualizer.mcp

import com.intellij.mcpserver.McpToolFilterProvider
import com.intellij.openapi.extensions.ExtensionPointName

/**
 * T-McpGateDynamicFilter（O-68）注册桥：类型化触达 262-only 平台类型。
 *
 * **加载纪律**：本对象的方法体与签名引用 `McpToolFilterProvider` 等 253 不存在的平台类，
 * 只允许在 [McpGateFilterEpRegistrar.registerOnce] 的 `Class.forName` 探测通过后被首次触达
 * （独立顶层类，Kotlin object 首次访问才加载，外层探测失败时本类永不解析）。
 *
 * EP 名 `com.intellij.mcpServer.mcpToolFilterProvider` 为 262.10968 mcpserver.jar 内
 * `McpToolFilterProvider.Companion.getEP` 的 ldc 常量实证；注册走最简
 * `registerExtension(extension)` 重载（本插件非动态卸载插件，随 IDE 生命周期同生共死）。
 */
internal object McpGateFilterEpBridge {

    fun register() {
        val ep = ExtensionPointName.create<McpToolFilterProvider>("com.intellij.mcpServer.mcpToolFilterProvider")
        ep.getPoint(null)
            .registerExtension(SqlVisualizerToolGateFilterProvider())
    }
}
