---
name: t-previewtopoverlap-plan
description: SQL预览顶部元信息与工具栏重叠根因（BorderLayout WEST/EAST 偏好宽之和超容器宽直接重叠）+
  低频按钮折叠「更多」JBPopup 菜单（2026-09-10 已实施未提交，3196 绿）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_2b498de0-345e-4345-9a1d-19232cdb141c
---

T-PreviewTopOverlap：预览弹窗顶部「类型/性能建议」两行文字压进右侧按钮行（用户真机截图 2026-09-10，计划文档 `docs/开发计划/20260910-0812-T-PreviewTopOverlap-预览顶部元信息与工具栏重叠-开发计划.md` 含实施记录回填）。**已实施未提交（工作树），全量 3196/0/0 绿；runIde 真机走查留人工**。

根因三层：① `SqlPreviewPanel.kt` topRow 用 BorderLayout，metaLabel 放 WEST、工具栏放 EAST——AWT 契约 WEST/EAST 各按 preferred 整块贴边绝不收缩，偏好宽之和超容器宽时直接互相重叠（截图同帧两种叠压方向 = 重绘竞态混合帧）；② metaLabel 两行 HTML（T-SqlPerf 1bb88bd4）preferred 宽 = 最宽行全文宽，无截断（「分行避免重叠」的旧缓解只减行高不减肥）；③ 工具栏涨到 7~8 个按钮约 450px。触发面：DimensionService 记忆窄弹窗（下限 640）+ 任一性能建议行。

**落地四件套**：F1 metaLabel WEST→CENTER（结构性零重叠，topRow/toolbarPanel 提字段）+ F2 低频按钮折叠「更多」JBPopup 菜单（导入参数/差异对比/分享三项；压缩/格式化/**关键字换行（用户定版直显）**/刷新直显，复制错误永不折叠——flashCopied 对号反馈依赖 JButton，[[t-execcopyfix-plan]]；API 先例 SqlClickPreview.kt:146、DataSourceSelector.kt:2577）+ F3 宽度预算截断（updateMeta 改原文分段缓存 metaLineText/metaPerfSegments，applyMetaTruncation 预算 = topRow 宽−工具栏理想宽−32 边距、下限 80px；meta 行复用 ellipsize、perf 行逐段丢尾加省略号；width≤0 先展全文；顺带修了 tooltip 直显 `<font>` 标签的旧瑕疵）+ F4 SqlPreviewTopRowLayoutTest 8 用例（窄宽零重叠断言为核心回归锁）。

**Why**：BorderLayout WEST/EAST 重叠是 AWT 既定行为不是渲染 bug；「把长文案换行」类缓解对 WEST 占位无效；折叠分组经用户确认定死后不需要 ActionToolbar 动态 overflow（迁移反需 javap 核实裁剪 API + 放弃 flashCopied）。

**How to apply**：新增顶部按钮先问频率——低频进 `moreActions()` 列表（文案键 + 委托体），勿直接 add 直显；截断测试不能依赖 componentResized（headless 不触发），走 `applyMetaTruncationForTest()` 直调；模态弹层（ParamImportDialog/ShareDialog/Diff）在单测不可 show，step 委托只测未命中路径。相关：[[t-perfbatch-plan]]。
