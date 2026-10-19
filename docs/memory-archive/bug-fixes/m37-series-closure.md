---
name: m37-series-closure
description: M37 竞品融合系列（P-立项-06）全章闭环核实（20260916）——M37.1/2/3 已以 cherry-pick 移植进
  master、M37.4 生成测试实施后下线定案、M37.5 Flex 调研暂缓+M38 吸收落地
metadata:
  node_type: memory
  type: project
  originSessionId: sess_70b8a1b6-cdda-41a3-ba53-e650b496c141
---

M37.0~M37.5（P-立项-06 竞品精华融合）2026-09-16 核实**全系列闭环，无待办**：

- **M37.1 日志↔源码双向深链 / M37.2 真实 EXPLAIN 联动 / M37.3 会话感知捕获**：均已进 master。进法是 cherry-pick/移植（master 上为新 SHA：eb27063d、aa47fd2b；dev 原提交 83f15b6/eb27063 不被 master `--contains` 命中），M37.2 后续还有 T-ExplainDbl 修复（5cae17c，09-14）与汉化收尾（panel.explain zh 改「执行计划」）。M37.3 经 T-AutoShowMaster（6ede29d）移植。
- **M37.4 Wrapper→测试资产生成**：dev 曾实施（9b3207f TestAssetGenerator 双形态+预览面板「生成测试」入口），2026-09-07 1630 批次**整体下线**（28caf22 删 genTest bundle 14 键、f089954 CHANGELOG Removed + 功能文档 §2.4 标注）。下线定案=产物是静态 SQL 文本+占位断言骨架，不感知项目测试栈、断言需手工补齐，使用价值不足；重做方向=接项目真实测试框架的可执行用例生成（git 历史可找回）。
- **M37.5 MyBatis-Flex 可行性调研**：报告已交付（55f2d87），结论**暂缓**（结构性差异+同名锚点冲突+生态占位）；其反向吸收 M38「Flex 精华吸收」已实施（M38.1 锚点负例回归+修 2 真实误报、M38.2 81 场景金样矩阵+修 4 个 MP 语义偏差：or(Consumer) 组内连接词恒 AND、ne 渲染 `<>` 非 `!=`、负数字面量求值、set(false,col,val) 整体忽略等）。
- 「M37/M38 竞品融合新增功能使用说明」等功能文档在 dev 分支（master 无此文档）；M37.4/M37.5 结论详版见 dev 工作树 `docs/memory-archive/bug-fixes/project-m37-m38-competitive-flex-absorption.md`。

关联 [[t-competitive-round5]]、[[pitfall-cherrypick-port-contains-blindspot]]、[[pitfall-stale-session-git-snapshot]]。
