---
name: pitfall-log-startup-capture-regression
description: 启动自动监听回归——最终根因 Class.forName 使用插件 classloader 而非平台 classloader；修复=显式传入 platformClassLoader
metadata:
  node_type: memory
  type: project
  originSessionId: sess_05d0c319-36e4-4007-8750-7b8f61b6e808
---

**2026-08-17 回归实证**：用户开启「项目启动时自动开始监听」后，项目启动成功后监听状态不变化、无 SQL 日志捕获。

**最终根因（2026-08-17 日志实证）**：
- `SqlLogConsoleBridge` 中所有 `Class.forName()` 调用默认使用**插件 classloader**
- 但 `com.intellij.execution.RunContentManager` / `ConsoleView$Filter` 等 execution API 类在**平台 classloader** 中
- 插件 classloader 是平台 classloader 的子类，双亲委派模型下子类 `Class.forName` 找不到父类已加载的类 → `ClassNotFoundException`
- 日志证据：`runContentManagerOrNull: com.intellij.execution.RunContentManager 不可用（ClassNotFoundException ... PluginClassLoader(...MyBatis-Plus SQL Visualizer...)）`

**修复**：所有 `Class.forName()` 改为显式传入平台 classloader：
```kotlin
private val platformClassLoader: ClassLoader =
    com.intellij.openapi.project.ProjectManager::class.java.classLoader

// Before
Class.forName(className)
// After
Class.forName(className, true, platformClassLoader)
```
涉及 3 处：`runContentManagerOrNull`（RCM 类）、`attachFilter`（Filter 接口）、`isConsoleApiAvailable`（探测）。

**修复演进**（2026-08-17~18）：
1. **v1（失败）**：LogStartupCapture 增加延迟重试 → 无效（postStartupActivity 不触发）
2. **v2（失败）**：尝试 `<listener topic="..."/>` → 平台不支持此扩展点语法
3. **v3（失败）**：`@Service(APP)` + messageBus → `@Service(APP)` 懒加载不实例化
4. **v4（失败）**：尝试 `@Service(lazyLoading = false)` → 2024.3 平台无此参数
5. **v5（部分有效）**：`ProjectListenerRegistrar` 改为 object + messageBus → 用户日志证实 `cyzc-platform` 成功（attach 成功 + capturing=true），但 `demo` 项目仍失败
6. **v6（部分修复）**：根因=Class.forName classloader 错误，修复=显式 platformClassLoader → attach 成功
7. **v7（修复）**：手动停止后运行项目不自动监听 → `onConsoleAppeared` 回调机制
8. **v8（修复）**：第一条 SQL 丢失 → 500ms 高频补捞（6次=3s）
9. **v9（修复）**：项目停止后不停止监听 → `contentRemoved` 回调
10. **v10（修复）**：设置「关→开」后已打开项目不自动监听 → `PluginSettingsConfigurable.apply()` 检测边沿 + 遍历 openProjects

**用户测试验证**（日志证实）：
- `cyzc-platform`（设置开启后打开）→ ✅ 自动监听成功（attach: RCM resolved via com.intellij.execution.ui.RunContentManager）
- `easy-code-demo`（设置开启后打开）→ ✅ 自动监听成功（capturing=true）
- `easy-code-demo`（手动停止后运行项目）→ ✅ `onConsoleAppeared` 检测到新控制台 → 自动重启捕捉

**第一条 SQL 丢失问题**（2026-08-18 实证）：
- **根因**：`attach()` 调用时控制台尚未创建，2s 轮询间隔内第一条 SQL 已输出 → 轮询检测到时第一条 SQL 已错过
- **修复**：`attach()` 后启动 500ms 高频补捞（6次=3s），确保第一条 SQL 输出前完成 filter attach
- `SqlLogConsoleBridge.startCatchUpPolling()` + `CATCH_UP_INTERVAL_MS=500` + `CATCH_UP_COUNT=6`
- 正常 `startPolling()` 启动时自动停止补捞

**项目停止后不停止监听**（2026-08-18 实证，最终方案）：
- **初试**：`ContentManagerListener` 无 `contentRemoved`（243 平台完全无 addContentManagerListener）→ 不可用
- **再试**：`ExecutionManager.EXECUTION_TOPIC.processTerminated` 反射挂载 → **误触发废弃**——对**所有**进程触发（HTTP 请求/profiler 断连），频繁 `stopCapture()` → 一条 SQL 显示两条 + 图标不同步
- **三试**：`scanAll` 「所有控制台移除」检测 → **无效**——`HttpSingleResponseConsole` 常驻（Spring Boot 停止也不消失），`currentConsoleIds` 永远非空
- **四试**：`scanAll` 「主控制台（ConsoleViewImpl）消失」检测 → **无效**——`getAllDescriptors()` 进程死后仍返回旧 tab，`hasMainConsole()` 始终为 true
- **五试**：`MessageBusConnection.subscribe(EXECUTION_TOPIC, proxy)` 反射订阅 → **回调从未触发**（日志无任何输出）
- **终修（实证有效）**：`hasMainConsole()` 新增**进程存活检查**——`RunContentDescriptor.getProcessHandler()` 反射获取 `ProcessHandler`，检查 `isProcessTerminated()` + `isProcessTerminating()`；进程死即视为无主控制台（`getAllDescriptors()` 返回旧 tab ≠ 进程存活）
- **附带 `.apply{}` 作用域陷阱**：`SqlLogManager` 构造时 `onConsoleRemoved = { onConsoleRemoved() }` 中 `onConsoleRemoved()` 解析为 bridge 自身字段（receiver 是 bridge）→ 回调未接通；须用 `{ this@SqlLogManager.onConsoleRemoved() }`

**设置「关→开」回归**（2026-08-18 实证）：
- **根因**：`easy-code-demo` 在设置开启前已打开，`postStartupActivity` 只触发一次（设置未开→跳过），设置变更后不重新触发
- **日志证据**：`08:40:19 easy-code-demo 打开(logAutoCaptureOnStart=false) → 跳过；08:41:29 设置开启；08:43:21 cyzc-platform 打开(logAutoCaptureOnStart=true) → 成功`
- **修复**：`PluginSettingsConfigurable.apply()` 检测「关→开」边沿，遍历 `openProjects` 调用 `startCapture()`（幂等守卫）

**手动停止后重启仍自动开启**（2026-08-18 实证）：
- **根因**：`CaptureAction.stopCapture()` 仅设 `capturing=false`，但 `logAutoCaptureOnStart` 设置仍为 `true`；重启后 `LogStartupCapture.execute()` 检查设置仍为 `true` → 自动开启
- **修复**：`CaptureAction.actionPerformed()` 手动停止时同步设置 `logAutoCaptureOnStart=false` + `ApplicationManager.getApplication().saveSettings()` 强制持久化（`PersistentStateComponent` 延迟保存，不立即写盘）
- **诊断日志**：`CaptureAction` 全流程日志（原设置值 → 设置后 → saveSettings 调用）+ `isProcessAlive` 反射日志

**已确认的平台约束**（2026-08-17~18 实证）：
- `<listener topic="..."/>` 扩展点语法不支持
- `@Service(APP)` 在 262 平台默认懒加载
- `@Service` 注解无 `lazyLoading` 参数（2024.3 平台）
- `postStartupActivity` 在 262 平台不可靠（不触发）
- `Class.forName()` 默认使用插件 classloader，execution API 须显式传 platformClassLoader
- 2s 轮询间隔太慢（Spring Boot 首条 SQL <1s），需 500ms 高频补捞
- `ContentManagerListener` 有 `contentRemoved` 回调（可用于检测控制台移除）
- `getAllDescriptors()` 进程死后仍返回旧 tab（须额外检查 `ProcessHandler.isProcessTerminated()`）
- `processTerminated` 对所有进程触发（含 HTTP 请求/profiler），不可用于项目停止检测
- `MessageBusConnection.subscribe` 反射订阅 `EXECUTION_TOPIC` 回调不触发

**onConsoleAppeared 机制**（2026-08-17 新增）：
- `stopCapture()` 仅设置 `capturing=false` + 清空 `attachedConsoles` + 按平台停止轮询（2024.3 停止，2026.2+ 保持兜底）
- 新控制台出现时（`ContentManagerListener.contentAdded` 或 2s 轮询 `scanAll`），调用 `onConsoleAppeared()`
- `onConsoleAppeared()` 检查：`capturing=false` + 设置开启 → 自动调用 `startCapture()`
- `dispose()` 仍调用 `bridge.detach()`（项目关闭时清理）
- **性能优化**：`SqlLogConsoleBridge.contentListenerRegistered` 标志区分平台——2024.3 停止轮询改用事件回调（零开销），2026.2+ 保持轻量轮询兜底（<1ms/2s）

**已落地的改动**（保留）：
- `dispose()` 补 `capturing = false`（状态机闭环）
- 删除 `startAutoCaptureForOpenProjects` 方法（已无调用者）
- `ProjectListenerRegistrar`（object）+ messageBus + 重试逻辑（兜底机制）
- `LogStartupCapture`（postStartupActivity）+ 注册 ProjectListenerRegistrar + 重试逻辑（第一道保险）
- 所有 `Class.forName()` 显式使用 `platformClassLoader`
- `SqlLogConsoleBridge` 新增 `onConsoleAppeared` 回调参数
- `SqlLogManager` 新增 `onConsoleAppeared()` 方法 + `stopCapture()` 不再 detach

**相关**：[[pitfall-execution-api-missing]]、[[project-log-capture-lifecycle-design]]、[[pitfall-plugin-xml-listener-unsupported]]、[[pitfall-classloader-forname-execution-api]]