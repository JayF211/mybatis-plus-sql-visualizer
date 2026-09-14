package com.intellij.mcpserver

/**
 * T-McpToolset 编译期桩（stub）：平台 MCP Server 插件（com.intellij.mcpServer）API 面。
 *
 * **为什么存在桩**：基线构建平台 IU 2024.3.7.1（243）不含 mcpServer 插件；而本机 262 内置
 * mcpserver.jar 的类文件是 major 69（Java 25），JDK 17 编译读不了（javac 17/21 报「错误的
 * 版本 69.0」，与 build.gradle.kts T-GifRecordSwitch 注释同源实证）。故以桩源码提供与真实
 * jar 完全一致的编译期签名，运行时类型一律来自平台内置插件。
 *
 * **签名权威**：本文件全部签名逐条 javap 自
 * `C:\Users\USER\AppData\Local\Programs\IntelliJ IDEA\plugins\mcpserver\lib\mcpserver.jar`
 * （IU-262.10315.125，2026-09-14）。253 分支 Marketplace 包（253.28294.336）EP 名与接口包名
 * 相同（调研报告 20260914-0317 §3.2 实测）。平台版本升级若漂移，须以新 jar 反编译同步本文件
 * 并跑 McpToolsetStubSignatureCrossVersionTest。
 *
 * **打包禁令**：mcpStub 源集产物绝不进入插件 jar（build.gradle.kts 只把桩产物挂进主编译
 * classpath，不进 runtime/打包产物）——运行时若插件 jar 携带 com.intellij.mcpserver.* 类，
 * 会与平台插件类冲突导致 instanceof/注解解析错乱。
 *
 * 真实接口默认实现（javap -c 字节码实证）：isEnabled()=true、isExperimental()=true、
 * alwaysIncluded()=false。Kotlin 接口默认方法编译为 Java default 方法，本桩以同语义覆写默认值
 * 表达（工具集实现类三钩子全部显式覆写，不依赖桩默认值）。
 */
interface McpToolset {
    /** 桩不表达 default 语义——真实接口此钩子默认返回 true。 */
    fun isEnabled(): Boolean = true

    /** 真实接口此钩子默认返回 true（实验态），稳定工具集必须显式覆写为 false。 */
    fun isExperimental(): Boolean = true

    /** 真实接口此钩子默认返回 false。 */
    fun alwaysIncluded(): Boolean = false
}
