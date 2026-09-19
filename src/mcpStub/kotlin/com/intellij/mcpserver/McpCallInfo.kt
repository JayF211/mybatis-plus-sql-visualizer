package com.intellij.mcpserver

import com.intellij.openapi.project.Project
import kotlin.coroutines.CoroutineContext

/**
 * T-McpToolset 编译期桩：CoroutineContext 扩展（工具方法内取当前 Project）。
 *
 * **文件名即契约**：真实扩展声明在 intellij-community
 * `plugins/mcp-server/src/com/intellij/mcpserver/McpTool.kt` 侧编译产物 `McpCallInfoKt`
 * （javap -classpath mcpserver.jar com.intellij.mcpserver.McpCallInfoKt 实证：
 * `public static final Project getProject(CoroutineContext)`）。Kotlin 扩展属性静态绑定到
 * 声明文件类——桩文件必须命名为 McpCallInfo.kt 使编译产物同为 McpCallInfoKt，工具方法里
 * `coroutineContext.project` 生成的静态调用才能在运行时命中平台真类（桩本体不打包）。
 *
 * 桩 getter 体永不执行（运行时按 FQN 解析平台类），故以 error() 哨兵实现。
 */
val CoroutineContext.project: Project
    get() = error("stub: resolved to the platform mcpServer plugin at runtime")

val CoroutineContext.projectOrNull: Project?
    get() = null
