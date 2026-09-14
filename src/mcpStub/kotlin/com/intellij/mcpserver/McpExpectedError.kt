package com.intellij.mcpserver

import kotlinx.serialization.json.JsonObject

/**
 * T-McpToolset 编译期桩：业务性工具错误（真实类 javap 自 262 mcpserver.jar）。
 *
 * 真实签名（javap -classpath mcpserver.jar com.intellij.mcpserver.McpExpectedError）：
 * ```
 * public class McpExpectedError extends java.lang.Exception {
 *   public McpExpectedError(java.lang.String, kotlinx.serialization.json.JsonObject);
 *   public final java.lang.String getMcpErrorText();
 *   public final kotlinx.serialization.json.JsonObject getMcpErrorStructureContent();
 * }
 * ```
 * 第二参数在真实 Kotlin 声明中带默认值 null（McpToolKt.mcpFail 的 $default mask=2 实证），
 * 桩以可空类型表达同一擦除签名。工具实现抛出本异常时，MCP 框架把 [mcpErrorText] 作为
 * agent 可读错误返回（而非 Internal error）——零弹窗红线的拒绝通道。
 */
class McpExpectedError(
    val mcpErrorText: String,
    val mcpErrorStructureContent: JsonObject? = null,
) : Exception(mcpErrorText)
