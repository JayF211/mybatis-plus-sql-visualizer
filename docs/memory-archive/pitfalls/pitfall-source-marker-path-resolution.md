---
name: pitfall-source-marker-path-resolution
description: SqlSourceInterceptor 标记路径相对于源码根目录，resolveEntryJump 需在所有 sourceRoots 中搜索而非仅用 basePath
metadata:
  node_type: memory
  type: project
  originSessionId: sess_20260902-f447
---

F-44-7 源码标记跳转路径解析失败（2026-09-02 修复）。

## 问题

`SqlSourceInterceptor` 打印的标记路径如 `cn/example/service/UserService.java`，相对于源码根目录（`src/main/java`）。但 `SqlLogPanel.resolveEntryJump` 原实现用 `project.basePath` 拼接：

```kotlin
val fullPath = "$basePath/${entry.sourceFile}"
val vf = LocalFileSystem.getInstance().findFileByPath(fullPath)
```

对于 Maven/Gradle 项目，`basePath` 是项目根目录，实际文件在 `src/main/java/cn/example/Service.java`，所以永远找不到 → `LogJumpTarget.NotFound`。

## 修复

新增 `findSourceFile()` 双重搜索策略：
1. 先在 `basePath` 直接查找（兼容历史面板精确跳转场景）
2. 再遍历所有 module 的 sourceRoots 搜索（兼容 F-44-7 标记路径）

```kotlin
private fun findSourceFile(relativePath: String): VirtualFile? {
    // 策略 1：项目根目录
    val basePath = project.basePath
    if (basePath != null) {
        val direct = LocalFileSystem.getInstance().findFileByPath("$basePath/$relativePath")
        if (direct != null) return direct
    }
    // 策略 2：所有源码根目录
    val modules = ModuleManager.getInstance(project).modules
    for (module in modules) {
        val rootManager = ModuleRootManager.getInstance(module)
        for (sourceRoot in rootManager.sourceRoots) {
            val file = LocalFileSystem.getInstance().findFileByPath("${sourceRoot.path}/$relativePath")
            if (file != null) return file
        }
    }
    return null
}
```

**Why:** 标记中的路径来自 `StackTraceElement.getFileName()` + 包名转换，相对于源码根目录而非项目根。
**How to apply:** 所有从外部（拦截器、标记行）获取的相对路径都需要在 sourceRoots 中搜索，不能直接拼 basePath。