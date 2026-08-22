---
name: pitfall-plugin-update-jar-path-windows
description: 插件自更新在 Windows
  上静默失败（最终解法）。三处叠加断点：①getPluginJarPath()/codeSource.location 在 2026.2 返回 null
  ②路径未归一化 ③DeleteCommand 目标目录被误用 <id> FQN 而实际安装目录是 zip 顶层短名
metadata:
  node_type: memory
  type: project
  originSessionId: sess_3a5bcbe6-4b92-4117-b00d-189f4ec2835d
---

**插件自更新（M28）在 Windows 上静默失败（2026-08-20 多轮排查实证，最终修复）**：用户已装 2026.8.18，内置更新下载 2026.8.19「更新成功」并重启，版本仍是 2026.8.18。症状恒定：`lib/` 下新旧两 JAR 共存、旧 JAR 不删。

## 三处叠加断点（按排查顺序）

### 断点①：`codeSource.location` 在 2026.2 返回 null（真正的第一根因）

`PlatformPluginInstaller.getPluginJarPath()` 用 `protectionDomain.codeSource.location` 拿插件 JAR 路径——**2026.2.1 平台实证返回 null**（日志实证 `getPluginJarPath: codeSource.location is null`）。`?.let` 短路 → 始终返回 null → DeleteCommand 永不调度。

> ⚠️ 之前假设是「路径未归一化」（`file:` 前缀 → `Path.of` 抛异常），为此加 `normalizeDescriptorPath`——但这无法解决 null，因为 null 时 `?.` 链根本不进归一化分支。**遇到 codeSource.location 为 null，`getPath` 类工具无能为力**，必须换路径来源。

### 断点②：`UnzipCommand` 确实执行了，但只解压不删除

即便修好①，`addActionCommand(UnzipCommand)` 解压新 JAR，但缺 DeleteCommand → 旧 JAR 残留 → 新旧共存 → 从旧 JAR 读版本。

### 断点③：DeleteCommand 目标目录名误用 `<id>` FQN（二次修复才暴露）

回退方案 `PathManager.getPluginsDir() / PLUGIN_ID`，但 **插件 `<id>` 是 FQN `cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer`，而实际安装目录名是 zip 顶层目录名短名 `mybatis-plus-sql-visualizer`**（Install from Disk 剥顶层、目录名取自 zip 顶层）。按 FQN 删除 → 目录不存在 → 扑空 → 旧 JAR 依旧不删。

## 最终修复（PlatformPluginInstaller.kt + UpdateSource.kt）

1. **Fallback 到 `PathManager.getPluginsDir()`**：`codeSource.location` 为 null 时用平台目录 API 定位（三平台通用），不再依赖 classloader 实现细节。
2. **目录名用 `UpdateSource.INSTALL_DIR_NAME`（= zip 顶层短名 `mybatis-plus-sql-visualizer`）**，**不能用 `<id>` FQN**。
3. `DeleteCommand` 删除**整个插件目录**（递归），比删单个 JAR 更可靠。
4. 开发态守卫：`jarPath` 与 `pluginDir` 两个候选都检查构建特征段。

```kotlin
// UpdateSource.kt
const val INSTALL_DIR_NAME = "mybatis-plus-sql-visualizer"  // zip 顶层目录名，非 <id>

// PlatformPluginInstaller.kt
internal fun pluginDirPath(): Path =
    PathManager.getPluginsDir().resolve(UpdateSource.INSTALL_DIR_NAME)
```

**Why:** 安装目录名 = zip 顶层目录名，与 `<id>` 无关；代码里必须区分「插件 ID（标识）」「zip 顶层目录名（安装实体）」「classloader 路径（运行时细节）」三者。

**How to apply:** ①代码里凡是用插件「目录名」重构路径的，**直接查已安装 zip 顶层目录名**，不要臆断为 `<id>`；②`codeSource.location` 为 null 是平台差异，优先用 `PathManager` 系 API 兜底；③自更新重删旧装新，用诊断日志（`LOG.info` 打印 jarPath/pluginDir/DeleteCommand/UnzipCommand 目标）在真实 IDE 断点定位，headless 单元测试测不到真实路径形态。详见 [[pitfall-getJarPathForClass-243-missing]]、[[project-marketplace-publishing]]。
