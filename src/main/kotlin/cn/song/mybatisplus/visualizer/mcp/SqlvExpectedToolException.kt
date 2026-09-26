package cn.song.mybatisplus.visualizer.mcp

/**
 * MCP 工具业务性错误的**自有**异常类型（T-McpDynamicToolset：取代对平台 McpExpectedError 的
 * 直接引用）。语义与平台错误一致：agent 可读文本（[mcpErrorText]）经 MCP 框架作为工具错误
 * 返回（而非 Internal error）——零弹窗红线的拒绝通道。
 *
 * **平台转译在 [McpExpectedBridge.wrapExpected]**：工具方法对平台抛出的必须是平台异常类型，
 * 转译由 audit 边界以反射完成（本类型只在转译失败时原样抛出——最坏降级为 Internal error，
 * 不崩 IDE）。测试环境无平台类时走同一条降级路径，断言直接落在本类型上。
 */
class SqlvExpectedToolException(val mcpErrorText: String) : RuntimeException(mcpErrorText)
