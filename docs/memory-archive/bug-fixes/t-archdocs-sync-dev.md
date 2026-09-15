---
name: t-archdocs-sync-dev
description: docs/架构设计 五文件同步至 dev 代码基线已交付（dev
  6f9bd5e，2026-09-13）——范围定案：只写分支自身代码、三矩阵不事后追改、02 篇 §7 JOIN 新章重编号
metadata:
  node_type: memory
  type: project
  originSessionId: sess_008f61dd-8ad2-481a-ac90-dac7eccf92a9
---

2026-09-13 dev 工作树（`../mybatis-plus-sql-visualizer-dev`）提交 6f9bd5e：01-项目架构总览 / 02-核心流程详解 / 03-开发与测试指南 / 预览触发面清单 / M37M38 说明 五文件从 09-02/09-05 旧基线同步至 dev HEAD（adaa2f4 口径，测试基线 295 类/3788 用例 2026-09-12 全量）。触发面 gutter 9→13（plugin.xml 14 注册）、ConverterRegistry 10→11（+XbatisConverter；EasyQueryConverter/MdsConverter/QuerydslConverter 不进 SPI 走 SqlPreviewService 前置派发）、18 顶层包（新 feedback/psource）、JOIN 原生图/免重启热替换/历史参数 v2 全部入册。

**范围定案（下次文档同步沿用）**：

- 架构文档只写所在分支自身代码。master 09-12 后独有内容（T-CrudSql 全系列、T-AutoFold、T-SelToggle、v2026.9.14-beta.1 发行）未合 dev，M56 agent 在 feature/t-m56-01-agent-base 也未合——均不入册；下轮 master→dev 合并（T-MergeMasterIntoDev3）后须把这些补进架构文档。
- 三份 API 覆盖矩阵（MP-MPJ / JPA-Hibernate / 其他ORM）是交付时同步的交付物（行+交付快照惯例），不做事后追改——滞后由下次对应交付补。
- 02 篇重编号：新 §7「JOIN 关系图渲染链路」、原「启动与自更新」→§8、「常见问题速查」→§9；01 篇 §6 速查表交叉引用已按新编号，后续新增引用沿用。

**Why:** 架构文档基线曾落后代码 10 天（09-02 同步后合入了 JOIN 原生图、热替换、历史参数、psource 等一大批）；分支拓扑（master 领先 dev、M56 在 feature 分支）决定文档能写什么。
**How to apply:** 下次同步先 `git log master ^dev` 与 `git log <feature> ^dev` 定范围；相关 [[project-idea-diagram-framework]]（dev 分支差异节已作废）、[[pitfall-subagent-claims-verify]]（本轮子代理失真事实的拦截记录）。
