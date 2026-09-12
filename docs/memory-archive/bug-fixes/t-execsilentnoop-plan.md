---
name: t-execsilentnoop-plan
description: T-ExecSilentNoop 计划（2026-09-10 立项未实施）：预览首次执行 SQL 静默不执行 + 误报凭据提示的根因与治理方案
metadata:
  node_type: memory
  type: project
  originSessionId: sess_001d8453-c130-46b4-8164-a102d2742328
---

预览面板点「执行 SQL」第一次无任何反应，8s/16s 后误报 `execute.credential.hint`（密码过期/驱动未下载），关闭弹窗重开再点即正常——2026-09-10 立项，计划文档 `docs/开发计划/20260910-0920-T-ExecSilentNoop-预览首次执行静默不执行与误报凭据提示-开发计划.md`。

沙盒日志（`.intellijPlatform/sandbox/mybatis-plus-sql-visualizer/IU-2024.3.7.1/log/idea.log` 09:10:43~09:11:37）实证：首次执行全程无 `DatabaseConnectionEstablisher - Connecting to:` 行 = 查询从未到连接阶段；差别只有两点——首次在 dumb 中枚举 runners 退化 4 个候选并静默复用 last-used `mpj_demo`（表 `legacy_item` 只在 jpa_demo），第二次 `exec psi cache hit` 后 `runners=1`（jpa_demo）。

javap 实证（`DatabaseTools/lib/database-plugin.jar`，`JdbcConsoleProvider` 在此不在 jdbc-console.jar）DBN 两条全静默出口：① `chooseStatements` 过滤后空集且 `execOutside==1` 时不消费 model 直接 return；② `ParametersHolder.beforeExecuteQueries` 遇 `model.statements().isEmpty()` 返回 false（`JdbcConsole.beforeExecuteQueries` 另有 `!isValid()` 分支）。且执行 model 来自 `console.getScriptModel()` 再 `adjustModelForSelection(..., getSelectionForConsole(editor, opt), opt)`——execSelection=2 无选区时是 `subModel(PositionRange(光标派生 offset))`，**与我们重建校验的 freshModel 无关**。

**Why:** 这类"什么都没发生"的失败没有任何日志/输出/异常，只能靠提前埋在提交链上的探针归因；错归因成凭据问题会把用户引向完全不相关的排查方向（Database 工具窗查密码/驱动），且同一文案会被 8s 监控与 40 拍窗口各报一次。

**How to apply:** 实施时先做 F1a 埋点（`[M36][EXEC]` 打 `Info.model.statements()` / 选区 / caret / `console.isValid()`、`isTargetValid()` 反射值 / history 长度），一次沙盒跑动分辨出撞的是哪条出口再改主因；兜底走现成的 `console.executeQueries(editor, freshModel, execOption)`（`:2202-2205`，注意会少做 `dbSrcSearchPath`/`ensureSearchPath`，见计划风险 1）。F3 动面板顶部显示数据源名与 [[t-previewtopoverlap-plan]]、[[t-vstack-plan]] 正面相撞须串行。相关：[[t-execcopyfix-plan]]（同一条执行链的失败可见化前例）、[[t-refreshdbsteal-plan]]（弹窗/控制台所有权治理）。
