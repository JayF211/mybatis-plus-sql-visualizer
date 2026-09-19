package com.intellij.mcpserver.annotations

/**
 * T-McpToolset 编译期桩：@McpTool 注解 DSL（真实注解 javap 自 262 mcpserver.jar，
 * 与 intellij-community master `plugins/mcp-server/src/com/intellij/mcpserver/annotations.kt` 一致）。
 *
 * 保留策略硬约束：真实注解为 RUNTIME retention（ReadToolset 等平台工具集方法的注解出现在
 * RuntimeVisibleAnnotations 表），框架运行时反射扫描 @McpTool 方法——桩必须同为 RUNTIME，
 * 否则编译产物落入 RuntimeInvisibleAnnotations 表，运行时框架看不到工具声明。
 */

/** 工具声明：标注在 McpToolset 实现的 suspend 方法上，方法名即工具名。 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class McpTool(
    /** 工具名（默认方法名）。本项目显式传 `sqlv_` 前缀工具名。 */
    val name: String = "",
    /** 工具标题（默认空串）。 */
    val title: String = "",
)

/** 工具描述（给 agent 的触发说明），方法级或参数级（RunPoint 参数级用法实证）。 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class McpDescription(val description: String)

/**
 * MCP ToolAnnotations 标准的 IDEA 三值实现（真实默认值全部 UNSPECIFIED，
 * AnnotationDefault 字节码实证；平台自带 ReadToolset 即「只覆写部分属性」的用法）。
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class McpToolHints(
    val readOnlyHint: McpToolHintValue = McpToolHintValue.UNSPECIFIED,
    val destructiveHint: McpToolHintValue = McpToolHintValue.UNSPECIFIED,
    val idempotentHint: McpToolHintValue = McpToolHintValue.UNSPECIFIED,
    val openWorldHint: McpToolHintValue = McpToolHintValue.UNSPECIFIED,
)

/** 三值布尔（真实枚举常量 UNSPECIFIED/TRUE/FALSE，javap 实证）。 */
enum class McpToolHintValue {
    UNSPECIFIED,
    TRUE,
    FALSE,
}
