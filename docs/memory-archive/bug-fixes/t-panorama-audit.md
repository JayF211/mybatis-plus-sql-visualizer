---
name: t-panorama-audit
description: 全分支功能全景审计报告已交付（20260916-0635）+ 四计划立项
  T-UxOnboard/T-ExplainIndex/T-BranchHygiene/T-PerfSweep3——八域资产×可见性、六优势六劣势、N-20~N-23/O-22~O-24
  建议
metadata:
  node_type: memory
  type: project
  originSessionId: sess_210e7a8b-ef48-4a17-a73a-6b3c28cd7fa6
---

全景审计报告 `docs/调研报告/20260916-0635-全分支功能全景审计与长期演进-调研报告.md` 已交付并提交 master（`6306cf8`，2026-09-16 二次核实；报告与 T-UxOnboard/T-ExplainIndex/T-BranchHygiene 三计划同提交入库，T-PerfSweep3 在 44161eb；20260916 待办盘点会话实测确认在库）。与五轮竞品报告分工：竞品向外、本报告向内——首次把全分支功能放进同一张表（八域 × 用户可见性三档）。

核心事实（实测 2026-09-16 06:20）：活线四条 master b6c0e1b/dev e587593(94 领先 29 落后)/M56 f2b2343(19/8, 无远端=最大敞口)/t-uinative 5ca0b76(8/1)；漂移已从 94/26 变 94/29、未推送 master 8 提交（T-MergeDev2Master 实施时以实测为准）；log-timestamp/log-search/t-listparamcollect/t-mcp-toolset2 均已合入宿主线；**K2J 线只有成本没有进展——主源仍 283 kt vs 3 java，移植本体 0/621 文件，锚点同步义务已在发生**（O-24 决策评审项：全量移植 vs 收窄为 Kotlin 运行时剥离，决策前冻结 k2j 增量同步）。

产出建议：N-20 Marketplace 视觉素材矩阵/N-21 MCP 生态目录收录（两市场动作不立计划随 N-17 发行窗口）+ 四计划：T-UxOnboard（20260916-0645 首用引导三件套：首锚点一次性通知/空态文案/首次捕获指路，禁进 gutter 热路径）、T-ExplainIndex（20260916-0647 EXPLAIN 索引缺失建议，前置=dev 合流后与 perf_advice 共用规则集，原生控制台结果读取面实施前必核实）、T-BranchHygiene（20260916-0649 分支工作树治理，t-mcp-toolset2 套硬挂 T-MD-04 dev 推送后）、T-PerfSweep3（20260916-0651 五项清账）。

**Why:** 审计结论「功能深度过剩、引导深度不足，短板全在第一次接触」是后续体验类任务的定盘星；需求池四状态全 0（2026-09-16 实抓）佐证冷启动是第一矛盾。

**How to apply:** 实施顺序=十计划既有顺序不动 → O-22 → N-22 → N-23 → O-23；红线沿用第五轮 §8.5 无新增修订。相关：[[t-competitive-round5]]、[[t-perf-round5-report]]、[[t-ui-native-research]]。
