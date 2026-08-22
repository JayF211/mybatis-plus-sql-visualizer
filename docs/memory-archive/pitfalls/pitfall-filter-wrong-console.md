---
name: pitfall-filter-wrong-console
description: 一条 SQL 显示两条——attachFilter 同时挂到 ConsoleViewImpl 和 HttpSingleResponseConsole，processTerminated 误触发致 filter 重复挂载
metadata:
  node_type: memory
  type: project
  originSessionId: sess_05d0c319-36e4-4007-8750-7b8f61b6e808
---

**2026-08-18 实证**：控制台出现一条 SQL，SQL 日志面板显示两条；监听状态图标不同步（capturing 反复横跳）。

## 根因链

```
processTerminated 误触发（HTTP 请求/profiler 断连等短命进程）
  → stopCapture() → 清空 attachedConsoles（但 filter 仍留在控制台上，未 detach）
  → 重新 startCapture() → 再次 attachFilter 到同一控制台
  → 控制台累积多个 filter
  → 一条 SQL 被多个 filter 各捕获一次 → 面板显示两条
  → capturing 反复 true/false → 图标不同步
```

## 关键发现

1. **`ExecutionManager.EXECUTION_TOPIC.processTerminated` 对*所有*进程触发**——含每次 HTTP 请求的 `HttpSingleResponseConsole`、profiler 断连等，非仅 Spring Boot 主进程
2. **`stopCapture()` 不清 detach filter**——`attachedConsoles.clear()` 只清了 bridge 的跟踪 map，控制台上的 filter 实例仍在
3. **`HttpSingleResponseConsole` 常驻**——HTTP 请求工具窗口在 Spring Boot 停止后也不消失，干扰「所有控制台移除」检测

## 修复

1. **移除 `processTerminated` 监听**——该事件过于宽泛，是误停/重复的元凶
2. **项目停止检测改用 `ProcessHandler.isProcessTerminated()`**——`hasMainConsole()` 反射检查 `RunContentDescriptor.getProcessHandler().isProcessTerminated()`，进程死即视为无主控制台（`getAllDescriptors()` 进程死后仍返回旧 tab）
3. **filter 去重**——`attachedConsoles` 用 `IdentityHashMap` 按 console 对象去重，但需配合正确的停止机制避免累积

**相关**：[[pitfall-log-startup-capture-regression]]、[[project-log-capture-lifecycle-design]]、[[pitfall-contentmanagerlistener-unavailable]]
