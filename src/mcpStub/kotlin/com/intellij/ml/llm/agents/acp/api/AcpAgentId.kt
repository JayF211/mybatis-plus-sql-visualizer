package com.intellij.ml.llm.agents.acp.api

/**
 * T-AcpMcpTransport 编译期桩（stub）：AI Assistant 插件（com.intellij.ml.llm）的 ACP agent 标识类型。
 *
 * **为什么存在桩**：与同源集内 mcpStub 的 McpToolset 同一问题——基线构建平台 IU 2024.3.7.1（243）
 * 不含 AI Assistant 插件，而本机 262 的 ml-llm 模块 jar 类文件是 major 69（Java 25），JDK 17 编译
 * 读不了（javac 17/21/24 均报「错误的版本 69.0」）。故以桩源码提供编译期签名，运行时类型一律
 * 来自平台安装的 ml-llm 插件真类。
 *
 * **签名权威**（2026-09-19 实测）：反编译自
 * `%APPDATA%/JetBrains/IntelliJIdea2026.2/plugins/ml-llm/lib/modules/intellij.ml.llm.agents.acp.jar`
 * （插件 id `com.intellij.ml.llm`，宿主 IU-262.10968.63）。本机无 JDK 25 的 javap（jdk1.8/17/21/24
 * 的 javap 对 major 69 一律 Unsupported class file version），改用 JBR-25.0.3（gradle transforms
 * `idea-2026.2.1-win/jbr`）的 javac + `java.lang.classfile` API 逐类导出签名。实测形态：
 * `sealed interface`（类属性含 PermittedSubclasses）、带 Companion，抽象属性 rawId/fullId 两个
 * （`AcpRegistryAgentId` 实现中 `getFullId()` = 常量前缀 `"acp.registry."` + rawId，字节码实证）。
 *
 * **与真类的刻意偏离**：桩不声明 `sealed`——真类靠 PermittedSubclasses 封闭实现集合，而桩的
 * 唯一消费者是 headless 测试的假 agent id（[cn.song.mybatisplus.visualizer.acp] 侧），sealed 会让
 * 同模块外的测试实现类无法编译。本项目不实现该接口，只把它当参数类型用，链接层面无影响。
 *
 * **打包禁令**：桩产物绝不进入插件 jar（接线与 mcpStub 同款，只挂编译/测试 classpath，
 * 详见 build.gradle.kts 的 mcpStub 注释）——运行时若插件 jar 携带 com.intellij.ml.llm.* 类，
 * 会与 AI Assistant 插件真类冲突。
 */
interface AcpAgentId {
    /** 注册表原始 id（如 `claude-acp`）。本项目未读取，保留为签名保真。 */
    val rawId: String

    /** 带前缀的完整 id（如 `acp.registry.claude-acp`）——provider 判定目标 agent 的唯一依据。 */
    val fullId: String
}
