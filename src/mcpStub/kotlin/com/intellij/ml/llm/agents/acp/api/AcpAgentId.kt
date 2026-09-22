package com.intellij.ml.llm.agents.acp.api

/**
 * T-AcpMcpTransport 编译期桩（stub）：ACP agent 标识类型的**第二形态**。
 *
 * **为什么有两个 AcpAgentId 桩**：该类型的归属在 ml-llm 的两个制品间**来回搬过**，两处都在用户可达的
 * 发行线上，故两形态都必须支持（只支持一处会让另一线的 SSE 绕行整条失效）：
 *  - `262.9437.276`（ml-llm 自带 jar，宿主 2026.2.1 系）：provider 形参 = `com.intellij.platform.acp.AcpAgentId`
 *    （**类在发行版的 platform-acp-plugin 里，不在 ml-llm 内**——全量查过，ml-llm 各 jar 无此类）；
 *  - `262.10968.97`（装机 ml-llm，宿主 IU-262.10968.63）：provider 形参 = 本包类型，**类在 ml-llm 自己的
 *    jar 内**（`intellij.ml.llm.agents.acp.jar`）。
 *  两处 provider 的 FQCN 相同、方法名相同，只有形参包不同，故实现类必须**同时声明两个重载**才在两线上
 *  都真正实现该接口（`javap` 实测：两制品各自只声明自己那一形态；见
 *  [cn.song.mybatisplus.visualizer.acp.AcpIdeaMcpTransportProviderImpl] 的跨形态说明）。
 *
 * **签名权威**（2026-09-22 实测）：`javap -classpath
 * %APPDATA%/JetBrains/IntelliJIdea2026.2/plugins/ml-llm/lib/modules/intellij.ml.llm.agents.acp.jar
 * com.intellij.ml.llm.agents.acp.api.AcpAgentId` → `public interface`，含 `Companion`、
 * 三个 `public static final String`（`CUSTOM_PREFIX` / `REGISTRY_PREFIX` / `REMOTE_PREFIX`）与两个抽象
 * getter（`getRawId()` / `getFullId()`）。本桩只声明本项目**用到的** `rawId`/`fullId`（前缀常量与本项目
 * 无关，故意不声明——少一个签名漂移面；`CLAUDE_ACP_ID` 用内联字面量，理由见 impl 的 companion KDoc）。
 *
 * **与真类的刻意偏离**：桩不声明 `sealed`（真类含 PermittedSubclasses），理由同平台形态桩——桩的消费者是
 * 本项目自己的实现类与测试假件，sealed 会让跨模块实现无法编译。
 *
 * **打包禁令**：桩产物绝不进入插件 jar（接线与 mcpStub 同源集，详见 build.gradle.kts 的 mcpStub 注释）——
 * 插件 jar 若携带 `com.intellij.ml.llm.*` 类会与 AI Assistant 插件真类冲突。
 */
interface AcpAgentId {
    /** 注册表原始 id（如 `claude-acp`）。本项目未读取，保留为签名保真。 */
    val rawId: String

    /** 带前缀的完整 id（如 `acp.registry.claude-acp`）——provider 判定目标 agent 的唯一依据。 */
    val fullId: String
}
