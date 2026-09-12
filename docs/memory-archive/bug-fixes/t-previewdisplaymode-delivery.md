---
name: t-previewdisplaymode-delivery
description: T-PreviewDisplayMode 已交付（master 7bf110e，2026-09-10，3237/0/6
  绿）——预览编辑器显示模式状态化，修竖向排列/关键字换行输入参数后形态消失；含"模型派生视图"设计定式
metadata:
  node_type: memory
  type: project
  originSessionId: sess_7004b492-8b23-4ed0-87cd-cf9afd1a7f33
---

**缺陷**（用户真机截图）：点「竖向排列」后在左侧参数行填值，右侧竖排形态立刻退回 format 形态；关键字换行必丢；格式化仅在设置「SQL 格式化输出」开启且非压缩时"看似保留"（设置关闭或压缩态同样丢）。

**根因定式（复用价值最高）**：预览编辑器是**模型派生视图**——参数输入/条件勾选/foreach 次数/压缩切换/刷新/新预览任一变更都经 `onParamChanged → renderSql` 从模型全量重建文本，再由 `showSql` 整篇 `setText` 覆盖。此结构下**只有挂在渲染管线里的变换能存活**：`format()` 恰好写在 `showSql` 一行条件里故被重新套用；`formatClauseLine/formatVertical` 只存在于按钮回调（对文档一次性变换），管线中无「当前显示模式」记录 → 首次重建即抹掉。判别口诀：**按钮里直接改文档 = 一次性变换 = 必被重建抹掉；要持久就得进管线。**

**修复**：`PreviewDisplayMode{NORMAL,FORMAT,KEYWORD_WRAP,VERTICAL}` + `displayMode` 字段；`applyDisplayMode()` 接入 `showSql` **唯一收敛点**（XML/批量/模型/旧文本四条重建路径自动全覆盖——选这里而非逐分支处理是避免漏改的关键）；**压缩模式短路优先**；三格式方法改为 `setDisplayMode(模式)` 薄封装（`toolbar()` 与 `Ctrl+Alt+L` 零改动即获持久语义，既有 3 条断言形态的用例不改自绿）；压缩态切换格式族模式**一并退出压缩**（否则短路致点击无可见效果）；`refreshView` 复位 NORMAL（延续 M19.3「刷新=回到默认状态」）。
设计取舍：`displayMode` 默认 NORMAL 且 NORMAL 仍交设置决定 → 默认观感与改动前逐字节一致，无需"初值派生"；FORMAT 因此获得"强制格式化"独立含义（正是条件性丢失分支的修复）。

**明确边界**：编辑器手动编辑仍会在参数变更时被重建覆盖（模型派生语义不变），保留手改需另立"手改即脱离派生"模型；模式不持久化到设置、无激活态高亮、再次点击不回默认（F5 留待反馈）。

**测试 7 条**：竖排/关键字换行参数变更后保持、设置关闭时格式化保持、压缩优先且切回多行恢复、压缩态切换退出压缩、刷新复位模式、动态条件勾选重建后模式不丢（断言物用**含两字段 ORDER BY 的模型**——竖排拆行而 format 同行，可真正区分模式是否生效，比"文档非空"式弱断言强）。新增用例在旧实现下必红。

**并行**：`SqlPreviewPanel.kt` 含 T-PreviewTopOverlap 在途改动 → hash-object + update-index 索引级拆分（8 hunk 逐个断言 anchor 在 HEAD 唯一命中，staged 无删行，对方改动留工作树）；跑前清 `build/classes/java/main` 陈旧类（当日第三次被劫持教训）。

计划：docs/开发计划/20260910-1349-T-PreviewDisplayMode-预览显示模式持久化-开发计划.md；截图存 memory/assets/t-vstackpersist-*.png。
关联 [[t-vstack-plan]] [[project-keyword-wrap-format]] [[parallel-session-index-commit-split]]。
