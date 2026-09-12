---
name: t-master-merge-into-dev-20260908
description: master 合入 dev 完成（a6570ce）——T-ListMode
  三显示模式嫁接片段管线（行高按模式分流）、runRunner silent×onError 双参融合、3618 绿 + verifyPlugin
  243/253/262 全 Compatible
metadata:
  node_type: memory
  type: project
  originSessionId: sess_8399868f-ef6c-4280-9a87-15b4780ebcfe
---

**2026-09-08 master 合入 dev 交付（merge commit a6570ce，dev worktree）**：master +34 提交（T-LogListFreeze04 片段管线/T-ClickPreview 五轮/T-JdbcMultiline/T-LogDedupeMpMark/T-DsCover/T-PreviewWiLock/T-Wia262/自动监听四轮 cherry-pick/v2026.9.8 发行）合入 dev；dev 独有 T-ListMode/T-TwoLine/T-ErrReport/hbm/设置导入导出全保全。全量 3618/0/0/6 绿（基线 3592+26）、verifyPlugin 三目标 Compatible（243/253/262）、buildPlugin 2026.9.8。备份锚点 `dev-backup-pre-master-merge-20260908`（=c8b6cad）；未 push。

**核心设计决策——三显示模式 × 固定行高分流**（T-LogListFreeze04「多行不保留」与 T-ListMode「默认关键字换行」两刻意决策相撞的定案）：
- SINGLE_LINE：固定行高（`applyListCellHeight`/`applySqlListCellHeight`），WideSelectionListUI O(1) 快路径，渲染直读不缓存。
- KEYWORD_WRAP/FORMATTED：`fixedCellHeight=-1` 可变行高 + `LogSqlEntry.cachedListLineFragments`（模式键 `cachedListLineFragmentsMode` 读取处比对自动失效）——旧 16s 冻结=HTML 解析×逐条测量；片段+缓存后只剩轻量组件布局。兜底预案（真机卡顿才做）：多行模式行数上限裁剪。
- 渲染器形态：元信息行 + **可伸缩 SQL 行组件池**（`ensureSqlLineCount`，首位恒保留作 `sqlLine` 测试缝），`SqlListFragmentRenderer.renderLineFragments`（整段单次词法 + 片段内按 \n 拆分、attrOf 注入测试缝）。

**合并实战要点**：
- runRunner 双参融合：`silent`（T-DsCover 预览静默+清 LAST_RUNNER_KEY）与 `onError`（T-Perf-01 面板行内红字）正交；静默路径 onError 回调后 return 不弹模态，显式执行模态+回调都走。
- 自动合并的语义陷阱实录：①CHANGELOG 把 dev Unreleased 条目卷进已发行 [2026.9.8] 区（违反每版本子标题唯一），重建两区；②HistoryCellRendererTest 混入 dev HTML 时代 `<br>` 断言用例（对 fragment API 无法编译），须重写；③SqlLogPanel `uiRefreshListener/capturingUiListener` 两侧各声明一份（conflicting declarations）；④dev 侧 T-Perf-12 已把 autoSaver.write 演进为 submit 批量写——「master 独有行」未必是 master 新，先查演进方向再取侧。
- merge-tree --write-tree 预演（无接触产出冲突 blob 逐块分析）→ 计划先行 → `git merge --no-commit` 实施的全流程再次验证高效。

**遗留人工**：runIde 真机——大日志多行模式滚动帧率、三模式切换、单击预览坐标（可变行高下）、自动监听回归。相关：[[project-dev-branch-state]]、[[t-previewlistreload-plan]]
