---
name: project-update-channel-beta-refactor
description: 插件内部更新机制改为 Gitee 抢先体验通道（2026-08-22）——双 JSON 文件共存 + 忽略此版本 + 本地测试方案
metadata:
  node_type: memory
  type: project
  originSessionId: sess_15c7f18d-3ce4-4fcb-9e13-b7a17c341f44
---

**内部更新通道改造为「Gitee 抢先体验」**已实施并验证全绿（2026-08-22）。稳定版由 JetBrains Marketplace 推送，内部通道仅服务内测。

## 双 JSON 文件策略

| 文件 | 角色 | 内部 updater 检查？ | 谁写 |
|------|------|---------------------|------|
| `update/update.json` | **内测通道（正式检查）** | ✅ 是 | `writeUpdateManifest` Gradle 任务 |
| `update/preview.update.json` | **草稿/备用** | ❌ 否 | 开发者手动编辑 |

**工作流**：草稿写 `preview.update.json` → 发布时复制到 `update/update.json`（版本号改 `-beta.N`）→ 推 Gitee → 用户经内部通道收到。

## 版本号约定

| 通道 | 格式 | 示例 |
|------|------|------|
| 内测（同日多版） | `YYYY.M.D-beta.N` | `2026.8.22-beta.1` → `beta.2` → `beta.3` |
| 正式（Marketplace） | `YYYY.M.D` | `2026.8.22` |

semver 保证 `beta.N` 永远小于正式版；数字标识符按数值排（`beta.10 > beta.9` 正确）。

## 配置变更

- `autoCheckUpdate` 默认 `true` → `false`（新用户不自动检查，稳定版由 Marketplace 管）
- 新增 `ignoredUpdateVersions: String`（`;` 分隔的版本号集合，仅抑制自动启动通知）

## 忽略此版本功能

- **仅抑制自动启动通知**（跨重启持久化），手动在设置页检查更新不受影响
- 设置页**不展示忽略列表**、不提供取消忽略操作（简洁优先）
- 纯函数实现：`isVersionIgnored()` / `ignoreVersion()`（`update/VersionIgnore.kt`）
- 通知新增第 3 按钮：`[立即安装] [忽略此版本] [稍后]`

## 通知标题优化

- 标题保留插件名（notificationGroup displayName 在某些 UI 主题下不明显）：`MyBatis-Plus SQL Visualizer：发现可用更新`
- 版本号移到正文第一行：`版本 2026.8.22-beta.1（Gitee 通道）`
- `notificationContent()` 签名从 `changeNotes: String?` 改为 `manifest: UpdateManifest`

## 本地测试方案（不影响 Gitee 用户）

- `dev-support/local-manifest-server.py`：本地 HTTP 服务器返回测试清单
- `dev-support/run-ide-local-manifest.sh`：启动 Python → 运行 `runIde` → IDE 关闭后自动停服务器
- `.run/runIdeLocalManifest.run.xml`：IDEA 运行配置（ShellScript 类型）
- `build.gradle.kts` 中 `runIde` 检测 `-Dmpvs.update.manifest.url` 系统属性并转发给沙盒 IDE
- 用法：选「Run IDE (Local Manifest)」→ 自动启停，清单指向 `localhost:8919`

## 改动文件

- `update/UpdateSource.kt`（KDoc 更新，路径保持 `update/update.json`）
- `config/PluginSettings.kt`（默认值 + 新字段）
- `update/VersionIgnore.kt`（新建）
- `update/StartupUpdateCheck.kt`（忽略守卫 + 3 按钮 + 标题优化 + notificationContent 签名变更）
- `build.gradle.kts`（runIde 转发 + 注释更新）
- 双 bundle（9 项文案通用化 + `versionLine`/`ignore` 新增）
- 测试：`VersionIgnoreTest`（新建）+ `StartupUpdateCheckTest`（适配）+ `UpdateSectionTest`（默认值断言）