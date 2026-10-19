---
name: project-log-capture-lifecycle-design
description: SQL 日志自动监听生命周期设计——Project 级 capturing 状态位、多模块/多项目行为、双保险触发机制、100ms 快速轮询、lastConsoleIds 移除检测
metadata:
  node_type: memory
  type: project
  originSessionId: sess_05d0c319-36e4-4007-8750-7b8f61b6e808
---

**2026-08-18 最终版（含 ContentManagerListener 不可用实证 + 100ms 轮询 + lastConsoleIds 移除检测）**：

## 设计意图

`SqlLogManager` 是 `@Service(Service.Level.PROJECT)`，整个 Project **只有一个 capturing 状态位**，不区分模块：

- **按钮状态 = `manager.capturing`**：`CaptureAction.update()` 仅读这一标志位决定图标/文案
- **监听对象 = 本 Project 的 RunContentManager**：`bridge.attach(project)` 反射挂载到本项目运行控制台
- **多模块/多项目行为**：同 [[project-log-capture-lifecycle-design]]（单 Project 多模块共享 service，多项目独立）
- **设置页行为**：勾选后不立即监听已打开项目——双保险机制在项目打开时触发
- **项目关闭**：`dispose()` 自动停止（bridge.detach + capturing = false）

## ⚠️ ContentManagerListener 完全不可用（2026-08-18 实证）

**日志实证**：
```
attach: ContentManagerListener 不可用（com.intellij.execution.ui.RunContentManagerImpl）— 轮询兜底
```

**结论**：
- `RunContentManagerImpl` 类无 `addContentManagerListener` 方法 → `contentAdded` / `contentRemoved` **永远不会触发**
- 代码注释称「2024.3/2025.3 有 addContentManagerListener」**与实际不符**
- **所有控制台检测 100% 依赖轮询**（无事件回调）
- 影响：无法通过事件感知新控制台 / 控制台移除，必须用轮询 + identity 跟踪

## 轮询策略（最终版）

| 状态 | 间隔 | 说明 |
|------|------|------|
| `capturing=true` 无控制台 | **100ms** | 尽快捕获第一条 SQL |
| `capturing=true` 有控制台 | 2s | 常规兜底 |
| `capturing=false` | 停止 | 无项目运行时零开销 |

**实现**：
- `startPolling()` → `startFastPolling()`（100ms `catchUpTimer`）
- `scanAll()` 检测到新控制台 → `startNormalPolling()`（2s `pollTimer`）
- 无控制台时持续 100ms 轮询（不限次数，直到检测到控制台）

## lastConsoleIds 项目停止检测

**原理**：每次 `scanAll()` 记录控制台 `identityHashCode` 到 `lastConsoleIds`，下次扫描对比：

```kotlin
// SqlLogConsoleBridge.kt
private var lastConsoleIds = mutableSetOf<Int>()

private fun scanAll() {
    ...
    val currentConsoleIds = mutableSetOf<Int>()
    for (descriptor in descriptors) {
        ...
        val console = findConsoleView(component)
        if (console != null) currentConsoleIds.add(System.identityHashCode(console))
        scanComponent(component)
    }
    // 全部移除 → 项目停止
    if (lastConsoleIds.isNotEmpty() && currentConsoleIds.isEmpty()) {
        onConsoleRemoved()  // → SqlLogManager.stopCapture()
    }
    // 新控制台 → 自动重启
    if (currentConsoleIds.size > lastConsoleIds.size) {
        onConsoleAppeared()
        if (catchUpTimer != null) startNormalPolling()  // 100ms → 2s
    }
    lastConsoleIds = currentConsoleIds
}
```

## 双保险触发机制

```
项目打开
  ├── 第一道保险：postStartupActivity → LogStartupCapture.execute()
  │     ├── ProjectListenerRegistrar.registerIfNeeded()（messageBus listener）
  │     ├── 检查已打开项目（热加载兜底）
  │     └── 直接尝试 startCapture()
  └── 第二道保险：messageBus → ProjectManagerListener.projectOpened()
```

## ⚠️ Class.forName classloader 修复

```kotlin
private val platformClassLoader: ClassLoader =
    com.intellij.openapi.project.ProjectManager::class.java.classLoader

Class.forName(className, true, platformClassLoader)  // 正确
Class.forName(className)  // 错误 → ClassNotFoundException
```

涉及 3 处：`runContentManagerOrNull`、`attachFilter`、`isConsoleApiAvailable`。

## apply() 设置变更检测

```kotlin
// PluginSettingsConfigurable.kt
if (!autoCaptureBefore && settings.state.logAutoCaptureOnStart) {
    ProjectManager.getInstance().openProjects.forEach { project ->
        val manager = project.getService(SqlLogManager::class.java)
        if (manager.hasConsoles()) {  // 仅有控制台时才启动
            manager.startCapture()
        }
    }
}
```

`hasConsoles()` 通过 `SqlLogConsoleBridge.runContentManagerOrNull()` + `getAllDescriptors()` 检测。

## 文件清单

| 文件 | 作用 |
|------|------|
| `log/ProjectListenerRegistrar.kt` | object 单例，messageBus 注册 ProjectManagerListener |
| `log/LogStartupCapture.kt` | postStartupActivity + 注册 ProjectListenerRegistrar |
| `log/SqlLogManager.kt` | capturing 状态机 + onConsoleAppeared/Removed + hasConsoles |
| `log/SqlLogConsoleBridge.kt` | 反射桥 + 100ms/2s 轮询切换 + lastConsoleIds 移除检测 |
| `config/PluginSettingsConfigurable.kt` | apply() 边沿检测 + hasConsoles 守卫 |

## ⚠️ 第一条 SQL 丢失真正根因（2026-08-18 FilterProxy 实证）

**现象**：项目启动很久后多次请求同 SQL，第一条 SQL「没反应」（延迟显示），第二条才出现。

**根因**（非 filter 挂错控制台，此假设已被推翻）：
```
11:57:07.943 - 第一条 SQL 的 Preparing + Parameters 行被 filter 收到（ConsoleViewImpl filter 早已 attach）
11:57:11.200 - 第二条 SQL 的 Preparing 到来 → 才闭合捕获第 1 条（= 第一条，延迟 ~4s）
11:57:13.868 - 第三条 SQL 的 Preparing 到来 → 闭合捕获第 2 条（= 第二条）
```

- **`MyBatisLogParser.feed()` 只在下一句 `Preparing:` 到来时才 `closePending()`** → 第一条 SQL 延迟到第二条请求才显示
- 两条相同 SQL 间隔 < `dedupeWindowMs`(60s) → 第二条被去重丢弃
- 修复：`Parameters:` 行喂入后**立即 `closePending()`**（见 [[pitfall-filter-wrong-console]]）

## ⚠️ 项目停止后监听不停止（最终修复 = ProcessHandler.isProcessTerminated）

**尝试过的方案（均失败/回退）**：
1. `ExecutionManager.EXECUTION_TOPIC.processTerminated` 反射挂载 → **已移除**——该事件对**所有**进程触发（含每次 HTTP 请求的 `HttpSingleResponseConsole`、profiler 断连等），导致 `stopCapture()` 被频繁误停 → **一条 SQL 显示两条**（filter 重复挂载）+ **图标不同步**（capturing 反复横跳）
2. `MessageBusConnection.subscribe(EXECUTION_TOPIC, proxy)` 反射订阅 → **回调从未触发**（日志无任何输出）
3. `scanAll` 的「所有控制台移除」检测 → **无效**——`HttpSingleResponseConsole`（HTTP 请求工具窗口）**常驻**，即使 Spring Boot 停止也不消失，`currentConsoleIds` 永远非空，移除检测永不触发
4. `scanAll` 的「主控制台（ConsoleViewImpl）消失」检测 → **无效**——`getAllDescriptors()` 在进程终止后**仍返回旧 tab**（tab 保留在 RunContentManager），`hasMainConsole()` 始终为 true，移除检测永不触发

**最终修复（2026-08-18 实证有效）**：`hasMainConsole()` 新增**进程存活检查**——通过 `RunContentDescriptor.getProcessHandler()` 反射获取 `ProcessHandler`，检查 `isProcessTerminated()` + `isProcessTerminating()`：

```kotlin
// SqlLogConsoleBridge.kt
fun hasMainConsole(): Boolean {
    val descriptors = ... // getAllDescriptors()
    for (descriptor in descriptors) {
        val component = ... // getComponent()
        val isMain = component.javaClass.name.contains("ConsoleViewImpl") ||
            !component.javaClass.name.contains("HttpSingleResponseConsole")
        if (!isMain) continue
        if (isProcessAlive(descriptor)) return true  // 进程存活才算主控制台
    }
    return false
}

private fun isProcessAlive(descriptor: Any?): Boolean {
    val processHandler = descriptor.javaClass.getMethod("getProcessHandler").invoke(descriptor)
    val terminated = processHandler.javaClass.getMethod("isProcessTerminated").invoke(processHandler) as Boolean
    val terminating = processHandler.javaClass.getMethod("isProcessTerminating").invoke(processHandler) as Boolean
    return !terminated && !terminating
}
```

**要点**：`getAllDescriptors()` 返回旧 tab ≠ 进程存活。进程终止后 tab 仍保留，必须通过 `ProcessHandler.isProcessTerminated()` 判断。反射失败时保守视为存活（避免误停）。

## ⚠️ Kotlin `.apply{}` 作用域陷阱（onConsoleRemoved 回调未接通）

```kotlin
// SqlLogManager.kt 构造时
SqlLogConsoleBridge(buffer, ::parserConfig, ::applySettings) { onConsoleAppeared() }.apply {
    onConsoleRemoved = { onConsoleRemoved() }  // ❌ apply 的 receiver 是 bridge，onConsoleRemoved() 解析为 bridge 自己的字段（递归/默认空实现）
}
// 修复：
onConsoleRemoved = { this@SqlLogManager.onConsoleRemoved() }  // ✅ 显式限定外层类
```

**症状**：`scanAll` 触发 `onConsoleRemoved` 时无任何 `SqlLogManager.onConsoleRemoved` 日志（回调被 bridge 默认空实现吞掉）→ 项目停止后监听不停止。

## 变更历史

- **2026-08-17**：修复前回归（Class.forName classloader、plugin.xml listener、@Service 懒加载）
- **2026-08-18 v1~v11**：双保险机制 → onConsoleAppeared → 500ms 补捞 → contentRemoved → apply 边沿检测
- **2026-08-18 v12**：ContentManagerListener 不可用实证 → 100ms 持续快速轮询 + lastConsoleIds 移除检测
- **2026-08-18 最终**：processTerminated 误触发已移除 → 主控制台检测（忽略常驻 HttpSingleResponseConsole）+ `.apply{}` 作用域修复
- **最新测试**：1221 全绿（commit ecf9e8a 已提交第一条 SQL 修复 + 机制重写）

**相关**：[[pitfall-log-startup-capture-regression]]、[[pitfall-execution-api-missing]]、[[pitfall-plugin-xml-listener-unsupported]]、[[pitfall-classloader-forname-execution-api]]