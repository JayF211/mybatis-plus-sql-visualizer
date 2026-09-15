---
name: t-explainfirstnative
description: 执行计划首点结果缺失（DBN 空名占位 Tab 恒判新）与 MySQL EXPLAIN 表格形态对齐原生（jar 反编译定案），已交付
  master 20260914
metadata:
  node_type: memory
  type: project
  originSessionId: sess_bcbac68e-b684-4a68-a8a8-cda38cb51fce
---

T-ExplainFirstNative（2026-09-14 交付 master，全量 4081/0/0/6 绿）。

**问题一根因（真机日志逐行实证，非推断）**：DBN 首次执行时先 `contentAdded` 一个**空名占位**结果 Content（displayName=""，约提交后 400ms），再异步改名 "Result N" 并构建网格；`DataSourceSelector.pickResultTab` 以「displayName 不在提交时刻快照」判新——空名 ∉ 空快照恒真 → 400ms 重试第 1 拍即 select+`refreshResultsComponent(forceSwapIn=true)`（tick=1 落点早于网格构建），重试随即停表，tick 2~8 因 `live === lastLiveResultSwapped` 不再换入，事后无人补换入 → 结果滞留原生 Database 控制台、预览面板空白；第二次点击时快照已含具名 Tab、占位阶段被跳过故正常。修复：pickResultTab 候选跳过空名 + attachResultsHook 空名 return + refreshResultsComponent 逐拍快照结果 Tab 名称集合、集合变化补换入 + 8s 监控 newTab 跳过空名。

**关键事实**：262 平台 contentAdded 监听全 session 只回调 1 次（首次创建控制台时），后续执行 DBN 建 "Result N" Tab **不触发监听**——与 [[project-test-conventions]]「ContentManagerListener 不可用、控制台检测 100% 依赖轮询」一致；换入主链=400ms startResultTabRetry 轮询，勿再往监听上加逻辑。

**问题二反编译定案（DatabaseTools 262.10315.125，为后续二期存底）**：IDEA 原生 MySQL 家族 Explain Plan 从不展示 JSON——MySQL 8.0+ `MysqlExplainPlanProvider`→`MysqlTreePlanModelBuilder`+`MysqlTreeRawPlanData`：执行 `EXPLAIN [ANALYZE] FORMAT=TREE <stmt>`，正则 `^\s*->(...)` 解析缩进文本建树；MySQL <8.0/MariaDB `MysqlBaseRawPlanData`：`EXPLAIN [EXTENDED] <stmt>` 表格行（PlanRow 直读 id/select_type/table/type/key_len/rows/filtered/Extra），按 id 嵌套建树（MetaNode SELECT/SUBQUERY）；渲染走原生 PlanView。本轮修复=MySQL/MariaDB `renderExplainStatement` 改裸 `EXPLAIN <sql>`（FORMAT=TREE 仅 8.0.16+ 且方言无版本感知不选）；**二期方向=反射调用原生 explain 机制（ExplainActionBase$Ui.explainStatement / ExplainPlanProvider.getDefaultProvider+createExplainRequest→PlanModel→自渲染 JTree）拿真树形**，需要真机验证。

**教训**：轮询换入的去重判据 `live !== lastLiveResultSwapped` 只认组件实例——DBN 复用同一容器实例异步填内容时恒 false，须加结果 Tab 名称集合快照做「内容已变」信号。动态代理可精确复现「先 add 空名后改名」行为（ResultTabSelectionTest 场景五）。
