---
name: project-dev-stale-test-failures-20260903
description: dev 分支 3
  处过期测试失败（PluginSettingsPanelBuildInfoTest/PluginSettingsPersistenceTest/SqlLogHistoryRoundTripTest）根因——生产代码正确、测试断言未随功能演进同步
metadata:
  node_type: memory
  type: project
  originSessionId: sess_90bb8d08-18f7-42d6-9553-2da553ad78f4
---

dev 分支全套测试 2875 例中 3 例失败（2026-09-03 实测，`git stash` 干净基线复现确认改动前即存在）。**三处全部是 stale test——生产行为正确，测试断言未随功能演进同步**。开发计划见 `docs/开发计划/20260903-1102-设置页与SQL历史三处过期测试修复-开发计划.md`。

## 三处失败根因

| 测试 | 期望 | 实际 | 根因 |
|------|------|------|------|
| `PluginSettingsPanelBuildInfoTest.testSettingsPanelSectionsTitles` | 8 个分区 | 9 个（多 `源码位置捕获`） | F-44-7 新增 `settings.log.tab.sourceCapture` 分区（`PluginSettingsPanel.kt:529`），测试 expected 列表未追加 |
| `PluginSettingsPersistenceTest.testLanguageWriteThroughRefreshesCheckboxTexts` | 18 个复选框 | 20 个 | `autoInjectInterceptorCheck`（源码捕获）+ `logShowDurationCheck`（耗时显示）加入后计数未同步；测试注释自身追溯停在 18 |
| `SqlLogHistoryRoundTripTest.真实链路_捕获条目自动保存后读取回环_字段全一致` | SQL 无分号 | SQL 有分号 | `SqlLogAutoSaver.writeEntry`（`SqlLogAutoSaver.kt:141`）调 `withTerminator()`（`SqlLogPanel.kt:74-75`）追加 `;`，测试断言未纳入该行为 |

## 修复方向（仅改测试，不动生产）

1. `PluginSettingsPanelBuildInfoTest.kt:74-84`：expected 列表追加 `settings.log.tab.sourceCapture`。
2. `PluginSettingsPersistenceTest.kt:147`：计数改 20 + 注释补 `F-44-7 +1；耗时显示 +1`。
3. `SqlLogHistoryRoundTripTest.kt:72`：断言改为 `withTerminator(captured.restoredSql)` 比较（推荐，影响最小）。

**Why:** 三处失败经 `git stash` 在干净 dev 基线（2bd4673）复现，确认非本次 T-CallerPicker 改动引入；生产行为均有明确功能意图（F-44-7 源码捕获、耗时显示、历史文件可直接执行）。
**How to apply:** 按开发计划 T-StaleTest-01~04 实施；修复后三个类单跑 + 全套验证。