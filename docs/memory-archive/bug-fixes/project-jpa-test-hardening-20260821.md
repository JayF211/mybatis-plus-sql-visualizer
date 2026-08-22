---
name: project-jpa-test-hardening-20260821
description: JPA 测试体系三层加固（2026-08-21）——组件契约升级+端到端集成+面板行为测试，防回归
metadata:
  node_type: memory
  type: project
  originSessionId: sess_60987762-1fde-4fe9-8079-1869188fbcbb
---

v2026.8.20 发行后用户 demo 项目实测发现 JPA 识别多轮修复暴露测试防回归不足。根因：测试按组件隔离写、断言不充分（只 assertNotNull）、无端到端测试、无面板行为测试。

**三层测试体系**（开发计划 20260821-0031）：
1. **组件契约测试升级**：断言 = 下游组件所有 `result.xxx` 读取点，不为空只是最低要求
2. **端到端集成测试**：JPQL→SQL→参数回填完整链路，PSI 方法→PreviewResult 各字段
3. **面板行为测试**：实例化真实 SqlPreviewPanel，EDT 环境模拟参数输入和警告显示

**Why:** 三轮修复（P0 gutter/P1 HQL/P2 DELETE）每次测试都没防住回归，同类 bug 反复出现。

**How to apply:** 新增 JPA 测试时遵循三层策略——不只验证"有没有"，要验证"多不多/对不对"；端到端测试覆盖 demo 项目所有 @Query 方法；面板测试用 `runInEdt {}` + `BasePlatformTestCase`。详见 [[project-hibernate-jpa-support]]。