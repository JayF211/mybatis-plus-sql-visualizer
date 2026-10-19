---
name: project-plantuml-render-error
description: PlantUML JOIN 图渲染修复链（2026-08-23~25）——!theme plain 不兼容 8059 已修复；后续发现 linetype ortho 依赖 Graphviz、8059 SVG 输出恒为 Graphviz 错误图（详见 F-42）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_79d9d9ca-f51f-435d-a4d5-c2483a8b4dda
---

PlantUML JOIN 图渲染问题（2026-08-23~25 持续调试，**本文件记录第一波：移除 `!theme plain` 已验证；后续 F-42 又发现 ortho/SVG 问题，见 [[project-f42-join-diagram-batik]]**）。

## 2026-08-25 后续关键发现（F-42，重要）

1. **`skinparam linetype ortho` 同样必须禁用**：正交线型强制 Graphviz dot 引擎，无 Graphviz 渲染 "Dot Executable: null" 错误图（用户本机无 Graphviz 实证）
2. **PlantUML 8059 的 SVG 输出恒为 Graphviz 错误图**（2347 字节）：`!pragma layout smetana` / `skinparam graphvizDot vizjs` 只对 PNG 有效；主 IDE 无 add-opens 时 PNG 必摔 IllegalAccessError → 降级 SVG → 错误图。候选修复：Batik PNGImageEncoder 纯 Java 写 PNG

## 核心根因（最终定位）

**`!theme plain` 指令不兼容 PlantUML 8059**：
- `JoinDiagramGenerator.generatePlantUml()` 输出包含 `!theme plain` 指令
- PlantUML 8059 **不支持该指令**，静默渲染出 105x59 错误图（正常应 274x237+）
- 修复：移除 `!theme plain`，保留 `hide circle` + `skinparam linetype ortho`
- 验证：`unzip -p jar JoinDiagramGenerator.class | strings | grep '!theme plain'` 应返回 0

## 叠加修复的 4 个 Bug

### Bug 1：previewModel=null（无动态条件时）
- 根因：`buildPreviewResult()` 中 `if (views.isEmpty()) null else SqlPreviewModel(...)`
- 修复：始终创建 `SqlPreviewModel(statement, dialect, formatSql(), views)`

### Bug 2：indicator.fraction 异常阻断渲染
- 根因：`indicator.fraction = 0.0` 在 try 块外，indeterminate 进度条抛 `IllegalStateException`
- 修复：移除 `indicator.fraction` 调用

### Bug 3：swapContent 未正确替换组件
- 根因：`DiagramPanel.init` 直接 `add(placeholderLabel, CENTER)` 但未设 `currentCenter = placeholderLabel`
- 修复：改用 `swapContent(placeholderLabel)` 初始化

### Bug 4：catch Exception 遗漏 Error
- 根因：`IllegalAccessError` 是 `Error` 非 `Exception`，`catch (e: Exception)` 捕获不到
- 修复：`catch (e: Throwable)` + `renderWithFallback()` PNG→SVG 降级

## 最终代码状态（commit 3936207）

- `JoinDiagramGenerator.kt`：移除 `!theme plain`
- `SqlPreviewService.kt`：`previewModel` 始终非 null
- `SqlPreviewPanel.kt`：移除"复制可执行 SQL"工具栏按钮；Diff/Compare 按钮国际化；性能警告写入独立 `perfWarningLabel`（SOUTH 层）
- `DiagramRenderTask.kt`：`catch (e: Throwable)` + `renderWithFallback()` 方法
- `DiagramPanel.kt`：`init` 用 `swapContent(placeholderLabel)` 初始化；新增 `loadingPanel`（JProgressBar indeterminate）
- 双 bundle：新增 `diagram.render.loading`、`panel.diff`、`panel.compare`、`panel.copyExecutableSql`；补齐 23 条 perf 中文翻译

## 调试方法论沉淀
1. **渲染产物尺寸是判错关键信号**：105x59 vs 274x237 — 若渲染"成功"但图像极小，是渲染本身出了错误图
2. **后台线程异常兜底用 `Throwable` 非 `Exception`**（IllegalAccessError 是 Error 子类）
3. **BorderLayout 组件替换必须用 `remove()` + `add()`，且需正确维护 `currentCenter` 状态**
4. **PlantUML 8059 不支持 `!theme plain`**——生成 PlantUML 源时避开版本不支持的指令
5. **i18n 完整性需与代码同步维护**——`MpSqlVisualizerBundle.message()` 是唯一合法文案来源

## 沙盒部署关键教训
- `prepareSandbox` 文件锁失败 → 构建前必须 `taskkill /F /IM java.exe`
- `build/libs` 可能含旧 jar → 构建后验证：`unzip -p jar Class.class | javap -p | grep method`
- 沙盒 autoReload 不重建已打开 tab → 测试需清 sandbox 重启

## Demo 项目测试用例
`F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer-demo`
- `DeviceInfoServiceImpl.java:104` `joinListEntity()` —— 简单 LEFT JOIN（最佳测试用例）
- `MpjExtServiceImpl.java:42` `queryWrapperString()` —— 三表联查