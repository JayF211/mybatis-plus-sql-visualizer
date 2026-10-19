---
name: t-autofold-plan
description: T-AutoFold SQL预览字段列表自动折叠（2026-09-12 已交付，六点位 + F0 公共应用器）：程序化
  FoldingModel 路线 + setExpanded(false)（非 setFolded）+ V1 runIde 走查留人工
metadata:
  node_type: memory
  type: project
  originSessionId: sess_6788f15d-681b-4ddc-9163-ec8a754c2c1b
---

T-AutoFold「字段较多时自动折叠」配置项（默认关）**2026-09-12 已实施（master a120bfa）**（计划 `docs/开发计划/20260911-1028-T-AutoFold-SQL预览字段列表自动折叠-开发计划.md`，§七有实施差异记录；clean 全量 3322/0/6 绿 + verifySmoke 通过）。

落地结构：
- `SqlAutoFoldPlanner`（ui/highlight/）纯逻辑规划器：SqlLexer 单遍状态机识别 SELECT 列清单/INSERT 列清单/VALUES 每组/UPDATE SET 四类顶层列表，顶层元素数 ≥ 阈值且跨 ≥ 2 行才折叠；嵌套外层命中即互斥；字符串/括号内逗号不误分段；纯 JUnit 12 例。
- `SqlAutoFolding.applyTo(editor, text)` 公共应用器唯一收口：本方区域清单挂 Editor UserData（`KEY_LAST_FOLD_REGIONS`）**只摘自己清单内的，绝不 getAllFoldRegions 全清**（控制台挂平台 ConsoleFolding 区域）；异常 runCatching 只 warn；开启态仅补设 isFoldingOutlineShown。
- 六点位：showSql 三分支 setText 后无条件 applyTo + 原生控制台（`nativeConsoleEditor` 引用登记于 onNativeEditorReady，detachConsole/clear/dispose 清空防 EditorFactory 泄漏）+ SqlClickPreview.buildContent 尾部 + LogToSqlPanel 结果区（convert/清空两处）+ SqlInputEditor（构造 + setSql，用户打字不触发）。
- 设置：autoFoldEnabled（默认 false）+ autoFoldFieldThreshold（默认 10）+ 面板复选框/阈值联动 + 台账 24→25 + SettingsIO 两键。

关键实证（反编译推翻计划）：243 `FoldRegion` **无 `setFolded`，收起是 `setExpanded(false)`**；`removeFoldRegion(FoldRegion)` 公共接口确认存在。SettingsIO 是手动逐字段 JSON（非 Gson），新增字段须显式补导出/导入两行。

遗留：V1 沙盒实证项（DBN 控制台折叠可见性/commitDocument 重算抹除/ConsoleFolding 干扰）与 runIde 真机走查留人工；降级范围按 v2 收窄到控制台支链①自身。同类陷阱：[[pitfall-k2j-stale-classes-hijack]]——State data class 加字段后旧编译类 `copy$default` NoSuchMethodError，build cache 会还原陈旧产物，须 `clean + --no-build-cache`。
