---
name: project-architecture-docs
description: 新增 docs/架构设计/ 三篇系列文档（Mermaid 架构图+流程图），面向新上手开发者与 AI 会话速览
metadata:
  node_type: memory
  type: project
  originSessionId: sess_10dc9686-c369-412f-9dd2-b5ab5bbe3710
---

2026-08-16 新建 `docs/架构设计/` 系列文档（仓库内，随代码演进更新），用于快速熟悉项目：

- `01-项目架构总览.md`：分层架构图（触发→编排→模型→转换→生成→展示六层）、包依赖图（13 包）、运行时注册图（plugin.xml + 3 个 @Service）、代码地图全量表、关键设计决策（ConverterRegistry SPI / 共享编排防漂移 / WrapperCache 双维度失效 / Community-Ultimate 反射隔离）
- `02-核心流程详解.md`：6 条链路流程图——正向预览（含 WrapperLocator 四定位策略、消费语义分支、逻辑删除/乐观锁/分页注入）、XML 子链路、注解子链路、反向 SQL→Java（含形态分发矩阵）、日志捕获、启动与自更新；每链路附关键类方法表
- `03-开发与测试指南.md`：构建命令（含提速实测 clean test 1m03s）、测试体系（76 类：Light ~28 / Heavy 5 / 纯 JUnit ~43，无 testData 全内联 fixture）、扩展指南（converter/方言/设置项/gutter 改动点）、文档三处同步、并行开发铁律

**Why:** 仓库此前无任何架构图/流程图文档（无 .mmd/.puml/.drawio），项目由 AI 开发、文件多（108 个 main Kotlin），新会话/新手理解成本高。

**How to apply:** 新会话接手本项目时，先读这三篇（尤其 02 链路图 + 03 §4 扩展点）再动代码，可省大量探索；文档更新随代码演进，改架构时同步维护。相关记忆：[[project-architecture]]、[[project-extension-points]]、[[project-doc-conventions]]
