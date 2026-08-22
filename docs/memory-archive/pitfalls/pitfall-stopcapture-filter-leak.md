---
name: pitfall-stopcapture-filter-leak
description: SQL 监听 stop 后仍持续捕获的完整根因与三层防御修复（2026-08-22）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_d4995f46-673e-4b3d-9f98-67c0b332ca15
---

**BUG**：用户点击「停止」按钮后，按钮图标切换为「捕捉」，但项目运行时调用接口，SQL 列表仍在持续增加条目。

**根因分析（三层缺陷）**：
1. **旧 Filter 未移除**：`stopCapture()` 只清 `attachedConsoles` 追踪 map，Filter 仍挂在控制台
2. **`detachAllFilters()` 反射失败**：`removeMessageFilter` 签名不匹配时静默失败（DEBUG 日志），Filter 仍在捕获
3. **`onConsoleAppeared()` 忽略 suppressed**：`autoStart=true` 时，任何控制台事件（contentAdded/轮询）都会重启捕捉，即使用户已显式停止

**修复方案（三层防御）**：
1. **Filter 生命周期治理**：`attachedConsoles` 存 `Pair<filter, ifaceClass>` 引用，`detachAllFilters()` 反射移除 + 尝试所有候选接口 + WARN 日志
2. **buffer.paused 安全网**：`stopCapture()`/`dispose()` 设置 `buffer.paused=true`，`startCapture()` 设置 `false`。即使 Filter 未被移除也无法入队
3. **事件监听清理 + suppressed 传递**：`stopCapture()` 调用 `bridge.detachContentListener()` 移除 contentListener；`onConsoleAppeared()` 检查 `suppressed` 标志阻止重启

**Why:** 停止后按钮与实际行为不一致，用户"停止"意图被违反。单一防御不够：Filter 移除依赖反射（可能失败），paused 标志是最终兜底。
**How to apply:** 停止逻辑必须多层防御——先置 capturing=false（防新挂载），再 detachAllFilters（移除旧 Filter），再 stopPolling（停轮询），再 detachContentListener（停事件），最后 paused=true（兜底）。
