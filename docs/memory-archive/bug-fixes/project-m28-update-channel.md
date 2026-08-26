---
name: project-m28-update-channel
description: M28 内部更新机制已改造为 Gitee 抢先体验通道（2026-08-22）——双 JSON 文件、忽略此版本、本地测试工具
metadata:
  node_type: memory
  type: project
  originSessionId: sess_fd19d18c-11a4-44b3-a532-04594c03f0e9
---

# M28：内部更新通道改造（2026-08-22）

内部更新机制从「自更新」转为「Gitee 抢先体验通道」——稳定版由 JetBrains Marketplace 推送，内部通道仅服务内测/提前推送。

## 核心设计

**双 JSON 文件策略**：
- `update/update.json` = 内测通道（内部 updater 检查源，`UpdateSource.MANIFEST_PATH` 指向此文件）
- `update/preview.update.json` = 草稿（开发者随时编辑暂存，发布时复制到 update.json）
- `writeUpdateManifest` Gradle 任务输出到 `update/update.json`（名称不变，仅 description 更新）

**配置默认值变更**：
- `PluginSettings.State.autoCheckUpdate`: `true` → `false`（默认关闭自动检查）
- 新增 `ignoredUpdateVersions: String`（`;` 分隔的忽略版本列表）

**忽略此版本功能**（`update/VersionIgnore.kt`）：
- `isVersionIgnored(version, ignoredVersions)`: 纯函数判断
- `ignoreVersion(version, ignoredVersions)`: 纯函数添加（幂等）
- 仅抑制**自动启动通知**（`StartupUpdateCheck` 守卫），**手动设置页检查不受影响**
- 精确版本号匹配（忽略 `beta.1` 不影响 `beta.2` 提示）
- 持久化到 `mp-sql-visualizer.xml`，跨重启恢复

**通知弹窗优化**（`update/StartupUpdateCheck.kt`）：
- 标题：`{插件名}：发现可用更新`（去掉版本号，缩短一半）
- 正文第一行：`版本 {version}（Gitee 通道）`（新增 `update.notification.versionLine`）
- 3 个按钮：`[立即安装] [忽略此版本] [稍后]`
- `notificationContent(manifest: UpdateManifest)` 签名变更（原 `changeNotes: String?`）

**Bundle 文案通用化**：
- 「发现新版本」→「发现可用更新」（兼容 `-beta.N` 内测版与正式版提前推送）
- 开关文案：「抢先体验：启用 Gitee 更新通道」
- 新增：`update.notification.versionLine` / `update.notification.ignore`

## 版本号约定

| 通道 | 格式 | 示例 |
|------|------|------|
| 内测版（Gitee） | `YYYY.M.D-beta.N` | `2026.8.22-beta.1` |
| 正式版（Marketplace） | `YYYY.M.D` | `2026.8.22` |

semver 规则：`2026.8.22-beta.1 < 2026.8.22`（prerelease < 正式）。同日多版本 N 递增（`-beta.1` → `-beta.2`）。发布 Marketplace 必须去掉 `-beta.N` 后缀。

## 本地测试工具

- `dev-support/local-manifest-server.py`：读取 `update/update.json` 真实内容，本地 HTTP 服务（默认端口 8919）
- `.run/runIdeLocalManifest.run.xml`：GradleRunConfiguration 类型，vmOptions 传 `-Dmpvs.update.manifest.url=http://localhost:8919/update.json`
- `build.gradle.kts`：`runIde` 检测到此属性 → 转发到沙盒 IDE 的 `systemProperty`
- 不启动 Python 直接跑沙盒：连接失败 → `fetchManifest` 返回 null → `CheckResult.Failure` → 静默跳过（不报错，仅不弹通知）

**运行配置经验**：
- `.idea/runConfigurations/` 被 `.gitignore`（不会提交）
- `.run/` 被提交但 IntelliJ 只自动识别 `GradleRunConfiguration` 类型
- ShellScript 类型不被 `.run/` 目录自动发现 → 须通过 UI 手动添加
- `.idea/` 中 force-add 的文件从索引移除用 `git rm --cached -f` + `git update-index --assume-unchanged`

## 新增/修改文件

- 新建：`update/VersionIgnore.kt` / `update/VersionIgnoreTest.kt` / `dev-support/local-manifest-server.py` / `.run/runIdeLocalManifest.run.xml`
- 修改：`update/UpdateSource.kt` / `update/StartupUpdateCheck.kt` / `config/PluginSettings.kt` / `build.gradle.kts` / 双 bundle / `update/update.json` / 测试文件

## 与 Marketplace 的关系

- 内部通道 = 内测/提前推（Gitee），稳定版 = Marketplace 推送
- 两者互不干扰：Marketplace 审核期间可通过 Gitee 提前推（绕过 1-3 天审核延迟）
- 内测版走 `-beta.N`，正式推 Gitee 时用纯数字版本号

关联：[[project-marketplace-publishing]] [[project-overview]] [[project-architecture]]