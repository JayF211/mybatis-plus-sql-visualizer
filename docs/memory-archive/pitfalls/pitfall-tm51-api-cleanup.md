---
name: pitfall-tm51-api-cleanup
description: T-M51 废弃API清理——Marketplace Internal API 阻断的解决思路（Kotlin→Java 改写 /
  ProtectionDomain / 删除兜底）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_c219eaa6-0399-451c-bb66-5082ed34c3cc
---

## 问题

Marketplace 审核拒绝：`Your plugin uses the Internal API`。Plugin 中存在 Internal API + Scheduled-for-removal 使用。

## 解决思路

### 1. Kotlin 实现接口默认方法 → synthetic bridge

**根因**：Kotlin 编译器为实现接口的**全部默认方法**生成 synthetic bridge，Plugin Verifier 报告为 Internal API。

**思路**：检测源码是否真的需要覆写这些方法 → 不需要则改用 Java 实现（Java 编译器只为源码中显式声明的方法生成 override）。

**适用**：`ToolWindowFactory.getAnchor()` / `getIcon()` / `manage()` 等

### 2. 获取插件自身 descriptor 全部 Internal（262）

**根因**：262 上 `PluginManager` 所有返回 `IdeaPluginDescriptor` 的方法均标记 `@ApiStatus.Internal`。`PluginDetailsService` 是公开的但返回的不是 `IdeaPluginDescriptor`。

**思路**：回到需求本质——我们需要的是"知道自己的 jar 在哪 + 卸载自己"。
- 获取路径 → 标准 Java `ProtectionDomain.codeSource.location`（所有版本公开）
- 卸载 → `StartupActionScriptManager.DeleteCommand`（公开 API，内部就是删除 jar）
- 不需要 `IdeaPluginDescriptor` 对象本身

### 3. Scheduled-for-removal 无替代 → 评估是否可删

**思路**：当 API 标记 for-removal 且无公开替代时，不是找替代，而是**评估功能是否可由现有机制覆盖**。

- `projectOpened()` → 功能："IDEA 启动时自动监听"
- 已有 `LogStartupCapture`（postStartupActivity）+ `onConsoleAppeared`（控制台出现时自动捕捉）
- `startCapture()` 幂等 → 多触发不重复
- 结论：删除 `ProjectListenerRegistrar`，功能等价

### 4. 验证方法

- 用 `javap -cp <jar> -verbose <class>` 直接反编译检查 `@ApiStatus.Internal` / `@Deprecated(forRemoval=true)` 注解
- 官方文档 `api-internal.html` 替换表**不完整**，不能只依赖文档
- 标准 Java API（ProtectionDomain、CodeSource）在所有平台公开，优先使用

## 关键教训

1. **消除 Internal 的第一选择：语言切换**（Kotlin → Java 消除 synthetic bridge）
2. **回到需求本质**（要的是 jar 路径，不是 descriptor 对象）比找 API 替代更有效
3. **Scheduled-for-removal 无替代时，评估删除比重构更安全**
4. **Plugin Verifier 的 "scheduled for removal" 可能无替代**——这是平台缺陷，不是代码问题

**相关**：[[project-marketplace-publishing]]
