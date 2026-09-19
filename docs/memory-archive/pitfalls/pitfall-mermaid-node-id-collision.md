---
name: pitfall-mermaid-node-id-collision
description: 同文档多张 Mermaid 图复用节点 ID（01 篇 §1 总览图与 §2 分层图的 S1/S2/S3 同名不同义），跨图连边不报错而是静默造孤立节点
metadata:
  node_type: memory
  type: project
  originSessionId: sess_168b44b7-cb06-4301-8457-f63d75296047
---

docs/架构设计/01-项目架构总览.md 的 §1 总览图与 §2 分层图复用同一批字母节点 ID 且含义不同——S1/S2/S3 在 §1 是附属能力块、在 §2 是编排层服务（SqlPreviewService / SqlToJavaConverter / SqlLogManager）。2026-09-16 双分支架构文档同步时给 §2 加边误写 `E2b --> S0`（S0 只存在于 §1），Mermaid 不报错，而是静默新建一个显示为「S0」的孤立节点混进渲染结果，靠逐边核对才当场抓到修正。

**Why:** Mermaid 节点 ID 作用域是单张图，边引用未定义 ID 等于现场建节点；大文档多图共用 E/S/M/C/G/U 这类字母编号时极易踩，且渲染不报错、肉眼难察觉。

**How to apply:** 改架构文档 Mermaid 图时，连边前先 sed/grep 输出当前图的完整节点与边集（不要凭通读记忆写）；新节点补边时逐条核对目标 ID 在同一张图内有定义。同步定案见 [[project-doc-conventions]] 架构文档分支同步节。
