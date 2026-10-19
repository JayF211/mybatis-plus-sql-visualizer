---
name: t-mergemasterintodev2-delivery
description: master→dev 第二轮增量合并交付（2026-09-12，merge
  28765fa）——v2026.9.11/T-DynUpdate/T-HistParam/T-ReverseParenWira/T-PickMpjErr/262
  签名护栏合入 dev，全量 3788 用例一次通过
metadata:
  node_type: memory
  type: project
  originSessionId: sess_d106252d-52d7-4707-b3a3-ceddaecb1e93
---

2026-09-12 master 合入 dev 第二轮增量合并交付（dev worktree 执行，立项 5d66df0 / merge 28765fa / 交付同步 ffe3ff7，备份 tag `dev-backup-pre-master-merge-20260912`）。merge-base 06d742a（v2026.9.10），master 独有 29 提交、dev 独有 114 提交；merge-tree 预演与实际零偏差（5 文件 5 冲突块）。

**逐块定案**：DataSourceSelector = master T-ReverseParenWira 的 getDocument ReadAction 包裹 + dev T-Perf-13 异步编辑器等待链**语义融合**（dev 独有 `prepareAfterEditorResolved` 已承载 master 内联续链全语义，master 0 引用该函数）；SqlLogAutoSaver = dev T-Perf-12 批量写结构 + master T-HistParam 条件 pd 行 union（`countPlaceholders` 系 LogEntryPreviewRecognizer.kt 顶层 internal 函数，同包免 import）；HistorySqlLogPanel 仅 import 并集；两文档时序并集去重。

**验收**：全量 295 类 / 3788 用例 / 0 失败 / 6 跳过**一次通过零修复**（dev 基线 3733 + master 55）；verifyPlugin 三目标 243/253/262 均 Compatible（deprecated 3/6/47、experimental 12 为 warning 级，增量来自 master 已过发行门禁的 update 域代码）；buildPlugin 出 2026.9.11.zip。

**要点**：上轮 CHANGELOG CRLF 归一的红利本轮兑现（零伪冲突，暂存 blob crlf=0）；dev 由此与 master 完全同步（此后 K2J 移植若重启，净增量应按 dev 新锚点计算，见 [[project-kotlin-to-java-port-sync]]）。关联 [[branch-port-loss-audit]]、[[pitfall-wira-not-in-readaction]]。
